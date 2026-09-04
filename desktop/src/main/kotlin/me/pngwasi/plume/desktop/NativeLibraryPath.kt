package me.pngwasi.plume.desktop

import java.io.File

/**
 * Tells JNA where the packaged Rust library is before anything tries to load it.
 *
 * jpackage puts `appResourcesRootDir/common` beside the runtime and exposes it as
 * `compose.application.resources.dir`; running from Gradle there is no such directory, so the
 * cargo output is used directly. Both are appended rather than replacing `jna.library.path`, so a
 * system-installed copy still wins if someone has one.
 */
object NativeLibraryPath {

    fun configure(projectRoot: File? = null) {
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(File(it)) }
            val root = projectRoot ?: File(System.getProperty("user.dir"))
            add(File(root, "native/target/release"))
            add(File(root, "../native/target/release"))
        }

        val existing = candidates.filter { it.isDirectory }.map { it.absolutePath }
        if (existing.isEmpty()) return

        val current = System.getProperty("jna.library.path")
        val combined = (listOfNotNull(current) + existing).joinToString(File.pathSeparator)
        System.setProperty("jna.library.path", combined)
    }
}
