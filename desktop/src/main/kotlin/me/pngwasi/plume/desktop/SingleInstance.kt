package me.pngwasi.plume.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.concurrent.thread

/**
 * One Plume per user, and a second launch reaches the first instead of joining it.
 *
 * Two copies is not a cosmetic problem: both register the same global shortcuts, so which one
 * answers is a race, and both write the same settings file. It is easy to end up with two — the
 * launcher, the login item and a restart can all fire at once — and with no window on screen there
 * is nothing to notice.
 *
 * A lock file for the exclusion, because the operating system releases it however the process ends,
 * including a crash; a stale pid file would not. A loopback socket for the handover, so the second
 * launch can hand its "show yourself" over instead of dying quietly and looking like a launcher
 * that does nothing.
 */
class SingleInstance(private val directory: File) {

    private var lockFile: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    private val lockPath: File get() = File(directory, "instance.lock")
    private val portPath: File get() = File(directory, "instance.port")

    /**
     * True when this process is the one that runs.
     *
     * False when another copy holds the lock: it has been asked to show itself and this one should
     * stop. Anything unexpected returns true — refusing to start is a worse failure than briefly
     * running twice, so the lock never becomes a reason the app will not open.
     */
    fun claim(onShowRequested: () -> Unit): Boolean {
        directory.mkdirs()
        val file = runCatching { RandomAccessFile(lockPath, "rw") }.getOrElse {
            PlumeLog.error("Could not open the instance lock, so starting anyway", it)
            return true
        }

        val held = try {
            file.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (e: Exception) {
            // Some network filesystems have no locks at all.
            PlumeLog.error("The filesystem refused an instance lock, so starting anyway", e)
            runCatching { file.close() }
            return true
        }

        if (held == null) {
            runCatching { file.close() }
            PlumeLog.info("Plume is already running; asking that copy to show itself")
            if (handOver()) return false

            // The lock is held by something that will not answer — a copy wedged with no window,
            // and no way for the user to reach it. Two Plumes can be quit; an application that can
            // never be opened again cannot, so this one starts without the lock.
            PlumeLog.error("The running Plume did not answer, so this launch is starting anyway")
            return true
        }

        lockFile = file
        lock = held
        listen(onShowRequested)
        Runtime.getRuntime().addShutdownHook(Thread { release() })
        return true
    }

    fun release() {
        runCatching { server?.close() }
        runCatching { lock?.release() }
        runCatching { lockFile?.close() }
        // Left behind it would point a later launch at whatever process next takes the port.
        runCatching { portPath.delete() }
        server = null
        lock = null
        lockFile = null
    }

    /**
     * Port 0 so the system picks one: a fixed port would be someone else's on some machine.
     *
     * Bound to the loopback address rather than every interface, which is both the only thing
     * needed and what keeps macOS from asking the user whether Plume may accept connections.
     */
    private fun listen(onShowRequested: () -> Unit) {
        val socket = runCatching {
            ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
        }.getOrElse {
            PlumeLog.error("No local listener, so a second launch cannot reach this one", it)
            return
        }
        server = socket
        runCatching { portPath.writeText(socket.localPort.toString()) }

        thread(isDaemon = true, name = "plume-instance") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                val request = client.use {
                    runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull()
                }
                if (request?.trim() == SHOW) runCatching { onShowRequested() }
            }
        }
    }

    /**
     * True when the running copy took the request. Retried, because it may still be starting and
     * may not have written its port yet.
     */
    private fun handOver(): Boolean {
        repeat(HANDOVER_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(HANDOVER_PAUSE)
            val port = runCatching { portPath.readText().trim().toInt() }
                .getOrNull()
                ?.takeIf { it in 1..65535 }
                ?: return@repeat
            val sent = runCatching {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                        CONNECT_TIMEOUT,
                    )
                    socket.getOutputStream().apply {
                        write("$SHOW\n".toByteArray())
                        flush()
                    }
                }
            }.isSuccess
            if (sent) return true
        }
        return false
    }

    private companion object {
        const val SHOW = "show"
        const val BACKLOG = 4
        const val CONNECT_TIMEOUT = 500
        const val HANDOVER_ATTEMPTS = 10
        const val HANDOVER_PAUSE = 200L
    }
}
