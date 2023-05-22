import androidx.compose.material.MaterialTheme
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jpexs.decompiler.flash.*
import com.jpexs.decompiler.flash.configuration.Configuration
import com.jpexs.decompiler.flash.exporters.FrameExporter
import com.jpexs.decompiler.flash.exporters.modes.FrameExportMode
import com.jpexs.decompiler.flash.exporters.settings.FrameExportSettings
import com.jpexs.decompiler.flash.treeitems.OpenableList
import com.jpexs.helpers.CancellableWorker
import com.jpexs.helpers.Path
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.multipdf.PDFMergerUtility
import java.awt.FileDialog
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import javax.swing.JFileChooser

@Composable
@Preview
fun FrameWindowScope.App() {
    var log by remember { mutableStateOf("") }

    // Log text prefixed with formatted current local date & time with format: yyyy-MM-dd HH:mm:ss
    fun log(text: String) {
        log = "[${
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }] $text\n" + log
    }

    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top)) {
            Button(
                // Show a file picker dialog to select a file with extension filter *.exe
                onClick = {
                    // Show a file picker dialog to select a file with extension filter *.exe
                    val inputFile = FileDialog(
                        this@App.window,
                        "Select the book",
                        FileDialog.LOAD,
                    ).apply {
                        file = "*.exe"
                        isVisible = true
                    }.files.firstOrNull()

                    if (inputFile == null) {
                        log("No input file selected")
                        return@Button
                    }

                    // Show a save file dialog to select a file with extension filter *.pdf
                    val outputFile = JFileChooser().apply {
                        dialogTitle = "Select the output file"
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("PDF", "pdf")
                        currentDirectory = inputFile.parentFile
                    }.let {
                        if (it.showSaveDialog(this@App.window) == JFileChooser.APPROVE_OPTION) {
                            if (it.selectedFile.extension == "") {
                                it.selectedFile = File(it.selectedFile.absolutePath + ".pdf")
                            } else if (it.selectedFile.extension != "pdf") {
                                log("Invalid output file extension")
                                return@Button
                            }
                            it.selectedFile
                        } else {
                            log("No output file selected")
                            return@Button
                        }
                    }

                    // Process the input file and save the output file
                    Thread {
                        try {
                            process(inputFile, outputFile, ::log)
                        } catch (e: Exception) {
                            log("Error: ${e.message}")
                            log("Stacktrace: ${e.stackTraceToString()}")
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Convert Book")
            }

            Text("Log:")

            OutlinedTextField(
                value = log,
                onValueChange = {},
                modifier = Modifier.fillMaxSize(),
                readOnly = true,
            )
        }
    }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Book Extractor",
    ) {
        App()
    }
}

fun process(inputFile: File, outputFile: File, log: (String) -> Unit) {
    log("Processing $inputFile")

    var i = 0
    var tempOutputDir = File(outputFile.parentFile, "temp$i")
    while (tempOutputDir.exists()) {
        i++
        tempOutputDir = File(outputFile.parentFile, "temp$i")
    }

    val outputDir = tempOutputDir

    try {
        outputDir.mkdirs()

        log("Extracting pages...")
        RandomAccessFile(inputFile, "r").use { randomAccessFile ->
            val fileSize = randomAccessFile.length()

            // read data info
            randomAccessFile.seek(fileSize - 13)
            val filesCount = randomAccessFile.readIntLE() - 1
            randomAccessFile.readByte()
            val dataOffset = fileSize - randomAccessFile.readIntLE()

            println("$filesCount - $dataOffset")
            // read files sizes
            randomAccessFile.seek(fileSize - (filesCount * 4) - 13)
            val filesSizes = (0 until filesCount).map {
                randomAccessFile.readIntLE()
            }

            // read files names
            randomAccessFile.seek(dataOffset)
            val files = (0 until filesCount).map {
                randomAccessFile.readLine().split("=")
            }.sortedBy { it[1].toInt() }.map { it[0] }

            files.forEachIndexed { i, file ->
                val bytes = ByteArray(filesSizes[i])
                randomAccessFile.read(bytes)
                val outFile = File(outputDir, file)
                outFile.parentFile.mkdirs()
                outFile.writeBytes(bytes)
            }
        }

        val convertDir = File(outputDir, "converted")
        val convertedFile = File(convertDir, "frames.pdf")

        val filesDir = File(outputDir, "files/large")

        if (filesDir.isDirectory) {
            convertDir.mkdirs()
            log("Converting pages to PDF...")

            val merger = PDFMergerUtility()
            merger.destinationFileName = outputFile.absolutePath

            filesDir.listFiles()!!.sortedBy { Path.getFileNameWithoutExtension(it).toInt() }.forEach { file ->
                val exportedFile = File(convertDir, Path.getFileNameWithoutExtension(file) + ".pdf")
                log("Converting ${file.name}")
                export(file, convertDir)
                convertedFile.renameTo(exportedFile)
                merger.addSource(exportedFile)
            }

            log("Merging pages to output file...")
            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
        }
    } finally {
        outputDir.deleteRecursively()
    }
}

fun RandomAccessFile.readIntLE() = Integer.reverseBytes(readInt())

private fun export(
    inFile: File,
    outDir: File,
) {
    if (!inFile.exists()) {
        System.err.println("Input SWF file does not exist!")
    }

    try {
        val sourceInfo = OpenableSourceInfo(null, inFile.absolutePath, inFile.name)
        val swf: SWF = try {
            SWF(FileInputStream(inFile), sourceInfo.file, sourceInfo.fileTitle, Configuration.parallelSpeedUp.get())
        } catch (ex: FileNotFoundException) {
            // FileNotFoundException when anti virus software blocks to open the file
//                logger.log(Level.SEVERE, "Failed to open swf: " + inFile.name, ex)
            return
        } catch (ex: SwfOpenException) {
//                logger.log(Level.SEVERE, "Failed to open swf: " + inFile.name, ex)
            return
        }
        swf.openableList = OpenableList()
        swf.openableList.sourceInfo = sourceInfo

        swf.addEventListener(object : EventListener {
            override fun handleExportingEvent(type: String?, index: Int, count: Int, data: Any) {
//                    if (level.intValue() <= Level.FINE.intValue()) {
//                        var text = "Exporting "
//                        if (type != null && type.length > 0) {
//                            text += "$type "
//                        }
//                        println("$text$index/$count $data")
//                    }
            }

            override fun handleExportedEvent(type: String?, index: Int, count: Int, data: Any) {
//                    var text = "Exported "
//                    if (type != null && type.length > 0) {
//                        text += "$type "
//                    }
//                    println("$text$index/$count $data")
            }

            override fun handleEvent(event: String?, data: Any?) {}
        })

        // Here the exportFormats array should contain only validitems
        val evl: EventListener = swf.exportEventListener

        val frameExporter = FrameExporter()

        val frames = listOf(0)
        val fes = FrameExportSettings(FrameExportMode.PDF, 1.0)
        frameExporter.exportFrames(
            object : AbortRetryIgnoreHandler {
                override fun handle(p0: Throwable?): Int {
                    return 0
                }

                override fun getNewInstance(): AbortRetryIgnoreHandler {
                    return this
                }
            },
            outDir.absolutePath,
            swf,
            0,
            frames,
            fes,
            evl
        )

        swf.clearAllCache()
        CancellableWorker.cancelBackgroundThreads()
    } catch (ex: OutOfMemoryError) {
        System.err.print("FAIL: Exporting Failed on Exception - ")
//        logger.log(Level.SEVERE, null, ex)
    } catch (ex: Exception) {
        System.err.print("FAIL: Exporting Failed on Exception - ")
//        logger.log(Level.SEVERE, null, ex)
    }
}