package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.plumeConfigDirectory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A log file, because a tray application has nowhere else to say anything.
 *
 * Launched from a desktop entry there is no terminal: stdout goes nowhere, an uncaught exception
 * kills a thread in silence, and the user is left with "it stopped working" and nothing to send.
 * That is not a state a background application should be able to reach.
 *
 * Deliberately small. It records what happened and what went wrong, not the text being worked on —
 * that is the user's writing, and it has no business in a file that gets attached to bug reports.
 */
object PlumeLog {

    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    /** Rotated at this size, keeping one previous file. A log must not fill a disk. */
    private const val MAX_BYTES = 512 * 1024

    val directory: File by lazy { File(plumeConfigDirectory(), "logs").apply { mkdirs() } }

    val file: File by lazy { File(directory, "plume.log") }

    private val lock = Any()

    /**
     * Starts logging and makes sure nothing dies quietly.
     *
     * The uncaught-exception handler is the point: without it a failure on a background thread
     * leaves no trace anywhere, which is exactly the case that is hardest to report.
     */
    fun install(version: String) {
        // The pid, because every copy that starts appends to the same file: without it "Plume ran
        // twice" cannot be told from one process logging twice.
        write(
            "Plume $version starting as pid ${ProcessHandle.current().pid()} on " +
                "${System.getProperty("os.name")} (${System.getProperty("os.arch")}), " +
                "Java ${System.getProperty("java.version")}",
        )

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            error("Uncaught exception on ${thread.name}", error)
            existing?.uncaughtException(thread, error)
        }
    }

    fun info(message: String) = write(message)

    fun error(message: String, error: Throwable? = null) {
        val detail = error?.let {
            val trace = StringWriter()
            it.printStackTrace(PrintWriter(trace))
            "\n$trace"
        }.orEmpty()
        write("ERROR $message$detail")
    }

    /** The most recent lines, for showing in the app without opening a file manager. */
    fun tail(lines: Int = 200): List<String> = synchronized(lock) {
        runCatching { file.readLines().takeLast(lines) }.getOrDefault(emptyList())
    }

    private fun write(message: String) {
        val line = "${timestamp.format(Instant.now())}  $message"
        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                file.appendText(line + "\n")
            }
        }
        // Also to the console, so `plume` in a terminal shows it live.
        println(line)
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val previous = File(directory, "plume.log.1")
        previous.delete()
        file.renameTo(previous)
    }
}
