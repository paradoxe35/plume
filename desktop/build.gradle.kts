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
    // A tray that uses the desktop's own status-notifier protocol. AWT's PopupMenu is a
    // heavyweight X11 widget drawn in Motif style: it ignores the GTK theme and cannot be styled.
    implementation(libs.compose.native.tray)

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

/** Also decides the Linux window class the desktop entry has to name; see [awtWindowClass]. */
val mainClassName = "me.pngwasi.plume.desktop.MainKt"

compose.desktop {
    application {
        mainClass = mainClassName

        // Minification is opt-in, through the `packageRelease*` tasks. Most of the download is the
        // bundled JVM and Skia, neither of which ProGuard can touch, so this trims our own jars
        // rather than transforming the size.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            // jpackage only produces packages for the host it runs on, so shipping all of these
            // means one CI runner per operating system.
            //
            // Windows gets both: the .msi is what deploys through group policy, the .exe is what
            // someone downloading it expects to double-click. Both go through WiX 3.
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Rpm,
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Dmg,
            )

            packageName = "Plume"
            packageVersion = "1.0.0"
            description = "AI revision and translation, anywhere you can select text"
            vendor = "pngwasi"
            copyright = "© pngwasi"

            // Without these jpackage substitutes Compose's own logo, so the installed app shows a
            // Kotlin icon. Regenerate with `python3 desktop/icons/generate.py`.
            linux {
                iconFile.set(project.file("icons/plume.png"))
                menuGroup = "Utility"
                appCategory = "Utility"
                debMaintainer = "noreply@pngwasi.me"
                // The tray icon and the hotkey listener both need the app to keep running with no
                // window open.
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("icons/plume.icns"))
                bundleID = "me.pngwasi.plume"
                // Menu-bar app: no Dock icon, no window on launch.
                infoPlist { extraKeysRawXml = "<key>LSUIElement</key><true/>" }
            }
            windows {
                iconFile.set(project.file("icons/plume.ico"))
                menu = true
                shortcut = true
                upgradeUuid = "6d1a3f18-8c2f-4d0a-9d69-1f3f3a2e1b77"
            }
        }
    }
}

/**
 * What AWT will report as the window's `WM_CLASS`, which is how a Linux dock matches the settings
 * window to the installed launcher.
 *
 * AWT names the window after the class holding the bottom stack frame — the application's main
 * class — with dots turned into dashes, and offers no supported way to set it. Derived here rather
 * than written out in the maintainer script, so renaming the entry point cannot quietly leave the
 * dock showing the launcher and an unmatched window side by side.
 */
val awtWindowClass: String = mainClassName.replace('.', '-')

/**
 * Replaces the maintainer scripts jpackage writes into the `.deb`.
 *
 * jpackage's postinst calls `xdg-desktop-menu install`, which exits non-zero when there is no
 * writable menu directory. A failing postinst leaves the package half-configured: the files land,
 * the application runs, and nothing ever appears in the launcher — which is exactly how "installed
 * but no icon" happens, with no error the user is likely to notice.
 *
 * jpackage can be given replacements through `--resource-dir`, but the Compose plugin owns that
 * directory and clears it as part of its own task, so the package is rewritten afterwards instead.
 */
fun Task.rewriteDebScripts(debDirectory: Provider<Directory>) {
    // Declared so that editing a maintainer script or an icon rebuilds the package rather than
    // leaving a stale one in place.
    inputs.dir(layout.projectDirectory.dir("jpackage"))
    inputs.dir(layout.projectDirectory.dir("icons/hicolor"))
    doLast {
    val debs = debDirectory.get().asFile.listFiles { file -> file.extension == "deb" }.orEmpty()
    debs.forEach { deb ->
        val unpacked = File(temporaryDir, deb.nameWithoutExtension)
        unpacked.deleteRecursively()

        providers.exec {
            commandLine("dpkg-deb", "--raw-extract", deb.absolutePath, unpacked.absolutePath)
        }.result.get().assertNormalExitValue()

        listOf("postinst", "prerm").forEach { script ->
            val replacement = layout.projectDirectory.file("jpackage/$script").asFile
            val target = File(unpacked, "DEBIAN/$script")
            target.parentFile.mkdirs()
            target.writeText(replacement.readText().replace("@WM_CLASS@", awtWindowClass))
            target.setExecutable(true, false)
        }

        // The icon theme, as packaged files rather than something postinst copies: dpkg then owns
        // them and takes them away on removal. jpackage ships a single 256px icon, which leaves
        // every desktop to shrink it for the panel and the dash itself.
        layout.projectDirectory.dir("icons/hicolor").asFile
            .copyRecursively(File(unpacked, "usr/share/icons/hicolor"), overwrite = true)

        // `--root-owner-group` because unpacking as an ordinary user rewrites every file to that
        // user, and rebuilding would then record it: the installed application would be owned by
        // whoever happens to hold uid 1000 on the target machine rather than by root.
        providers.exec {
            commandLine(
                "dpkg-deb",
                "--root-owner-group",
                "--build",
                unpacked.absolutePath,
                deb.absolutePath,
            )
        }.result.get().assertNormalExitValue()

        logger.lifecycle("Rewrote the maintainer scripts in ${deb.name}")
        }
    }
}

tasks.matching { it.name == "packageDeb" }.configureEach {
    rewriteDebScripts(layout.buildDirectory.dir("compose/binaries/main/deb"))
}

tasks.matching { it.name == "packageReleaseDeb" }.configureEach {
    rewriteDebScripts(layout.buildDirectory.dir("compose/binaries/main-release/deb"))
}

tasks.named("processResources") { dependsOn(copyNativeLibrary) }

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("native"))

}
