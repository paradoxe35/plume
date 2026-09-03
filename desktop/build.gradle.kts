import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Compose Desktop runs on the AWT event thread, so coroutines need the Swing dispatcher to
    // hand results back to it.
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * The Rust library is built by cargo, not Gradle, so it has to be carried into the jar.
 *
 * JNA looks on the classpath under a platform-specific prefix before it looks anywhere else, so
 * placing it there makes one mechanism work for `run`, for the packaged app image, and for the
 * installers — with no library path to set and nothing to go missing in the jpackage step.
 */
val nativeLibraryDir = rootProject.layout.projectDirectory.dir("native/target/release")

val jnaResourcePrefix: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    when {
        os.contains("win") -> "win32-$arch"
        os.contains("mac") || os.contains("darwin") -> "darwin-$arch"
        else -> "linux-$arch"
    }
}

val copyNativeLibrary by tasks.registering(Copy::class) {
    from(nativeLibraryDir) {
        include("*.so", "*.dylib", "*.dll")
    }
    into(layout.buildDirectory.dir("native/$jnaResourcePrefix"))
}

compose.desktop {
    application {
        mainClass = "me.pngwasi.plume.desktop.MainKt"

        nativeDistributions {
            // jpackage only produces packages for the host it runs on, so shipping all of these
            // means one CI runner per operating system.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)

            packageName = "Plume"
            packageVersion = "1.0.0"
            description = "AI revision and translation, anywhere you can select text"
            vendor = "pngwasi"
            copyright = "© pngwasi"

            linux {
                menuGroup = "Utility"
                appCategory = "Utility"
                debMaintainer = "noreply@pngwasi.me"
                // The tray icon and the hotkey listener both need the app to keep running with no
                // window open.
                shortcut = true
            }
            macOS {
                bundleID = "me.pngwasi.plume"
                // Menu-bar app: no Dock icon, no window on launch.
                infoPlist { extraKeysRawXml = "<key>LSUIElement</key><true/>" }
            }
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "6d1a3f18-8c2f-4d0a-9d69-1f3f3a2e1b77"
            }
        }
    }
}

tasks.named("processResources") { dependsOn(copyNativeLibrary) }

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("native"))
}
