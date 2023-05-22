import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

group = "me.msdarwish"
version = "1.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(fileTree(mapOf("dir" to "libs/ffdec_lib_18.4.1", "include" to listOf("*.jar"))))

                implementation("commons-logging:commons-logging:1.2")
                implementation("org.apache.pdfbox:pdfbox:2.0.28")

                implementation(compose.desktop.currentOs)
            }
        }
        val jvmTest by getting
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "BookExtractor"
            packageVersion = "1.0.0"
        }

        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}