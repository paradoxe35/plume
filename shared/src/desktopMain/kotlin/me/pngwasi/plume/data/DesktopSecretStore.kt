package me.pngwasi.plume.data

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Where a desktop API key actually lives.
 *
 * Each platform has a real credential store and it is the right place for a key; the encrypted
 * file exists because none of them is guaranteed to be reachable — a Linux box with no keyring
 * daemon, a locked login keychain, a headless session.
 */
internal interface KeyringBackend {
    /** False when this backend cannot work here, so the caller can fall back. */
    fun isAvailable(): Boolean
    fun get(entry: String): String?
    fun set(entry: String, value: String): Boolean
    fun remove(entry: String)
}

/**
 * Writes a value, reads it back, and removes it.
 *
 * "The tool is installed" is not the same as "the tool works": a locked keychain, a keyring daemon
 * that is running but refusing, a DPAPI call that is subtly wrong. All of those fail on write, and
 * because saving a key is fire-and-forget from the UI's point of view, the user would find out when
 * their provider stopped being configured. Proving the round trip once at startup costs a few
 * milliseconds and turns a silent loss into a fallback.
 */
internal fun KeyringBackend.roundTrips(): Boolean = runCatching {
    val probe = "plume_probe"
    val value = "plume-probe-value"
    if (!set(probe, value)) return@runCatching false
    val readBack = get(probe)
    remove(probe)
    readBack == value
}.getOrDefault(false)

class DesktopSecretStore private constructor(
    private val directory: String,
    private val chooseBackend: (String) -> KeyringBackend,
) : SecretStore {

    constructor(directory: String = plumeConfigDirectory()) :
        this(directory, ::preferredBackend)

    private val backend: KeyringBackend by lazy { chooseBackend(directory) }

    override fun getKey(providerId: String): String =
        runCatching { backend.get(secretEntryName(providerId)) }.getOrNull().orEmpty()

    override fun setKey(providerId: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            removeKey(providerId)
            return
        }
        runCatching { backend.set(secretEntryName(providerId), trimmed) }
    }

    override fun removeKey(providerId: String) {
        runCatching { backend.remove(secretEntryName(providerId)) }
    }

    override fun hasKey(providerId: String): Boolean = getKey(providerId).isNotEmpty()

    internal companion object {
        /** Test seam: keeps a test run out of the developer's real keyring. */
        internal fun withBackend(directory: String, backend: KeyringBackend) =
            DesktopSecretStore(directory) { backend }
    }
}

/** The platform's own store when it works, the encrypted file when it does not. */
private fun preferredBackend(directory: String): KeyringBackend {
    val preferred = when (DesktopOs.current) {
        DesktopOs.Linux -> SecretToolBackend()
        DesktopOs.MacOs -> MacKeychainBackend()
        DesktopOs.Windows -> DpapiFileBackend(directory)
    }
    return if (preferred.isAvailable() && preferred.roundTrips()) {
        preferred
    } else {
        EncryptedFileBackend(directory)
    }
}

/**
 * `secret-tool` talks to whatever Secret Service is running (GNOME Keyring, KWallet's bridge).
 * Using the CLI rather than binding libsecret avoids a native dependency for a handful of calls.
 */
internal class SecretToolBackend : KeyringBackend {

    override fun isAvailable(): Boolean =
        runCatching { run(listOf("secret-tool", "--version")).first == 0 }.getOrDefault(false)

    override fun get(entry: String): String? {
        val (code, out) = run(listOf("secret-tool", "lookup", "service", SERVICE, "account", entry))
        return if (code == 0 && out.isNotEmpty()) out else null
    }

    override fun set(entry: String, value: String): Boolean {
        val (code, _) = run(
            listOf("secret-tool", "store", "--label=Plume ($entry)", "service", SERVICE, "account", entry),
            stdin = value,
        )
        return code == 0
    }

    override fun remove(entry: String) {
        run(listOf("secret-tool", "clear", "service", SERVICE, "account", entry))
    }

    private companion object {
        const val SERVICE = "me.pngwasi.plume"
    }
}

internal class MacKeychainBackend : KeyringBackend {

    override fun isAvailable(): Boolean =
        runCatching { File("/usr/bin/security").canExecute() }.getOrDefault(false)

    override fun get(entry: String): String? {
        val (code, out) = run(
            listOf("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", entry, "-w"),
        )
        return if (code == 0 && out.isNotEmpty()) out else null
    }

    /**
     * -U updates in place; without it a second save fails with "already exists".
     *
     * Over stdin first, since `ps` shows every argument to every process on the machine. Not every
     * macOS reads the prompt that way, so the write is read back rather than assumed.
     */
    override fun set(entry: String, value: String): Boolean {
        val base = listOf(
            "/usr/bin/security", "add-generic-password", "-s", SERVICE, "-a", entry, "-U",
        )
        if (run(base + "-w", stdin = value).first == 0 && get(entry) == value) return true
        return run(base + listOf("-w", value)).first == 0
    }

    override fun remove(entry: String) {
        run(listOf("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", entry))
    }

    private companion object {
        const val SERVICE = "me.pngwasi.plume"
    }
}

/**
 * DPAPI encrypts for the current user account, so the file is unreadable by anyone else on the
 * machine even though it sits in an ordinary directory.
 */
internal class DpapiFileBackend(private val directory: String) : KeyringBackend {

    override fun isAvailable(): Boolean =
        DesktopOs.current == DesktopOs.Windows && runCatching { Dpapi.load() }.getOrDefault(false)

    private fun file(entry: String) = File(directory, "secrets/$entry.dpapi")

    override fun get(entry: String): String? {
        val f = file(entry)
        if (!f.exists()) return null
        return runCatching { Dpapi.unprotect(f.readBytes()).decodeToString() }.getOrNull()
    }

    override fun set(entry: String, value: String): Boolean = runCatching {
        val f = file(entry)
        f.parentFile?.mkdirs()
        f.writeBytes(Dpapi.protect(value.encodeToByteArray()))
        true
    }.getOrDefault(false)

    override fun remove(entry: String) {
        file(entry).delete()
    }
}

/**
 * The honest fallback. This protects a key from casual reading and from anything that scrapes
 * config files; it does not protect it from a process already running as this user, and nothing
 * file-based can. It is reached only when the platform's own store is unavailable.
 */
internal class EncryptedFileBackend(directory: String) : KeyringBackend {

    private val root = File(directory, "secrets").apply { mkdirs() }
    private val keyFile = File(root, "master.key")

    override fun isAvailable(): Boolean = true

    private fun masterKey(): SecretKeySpec {
        if (!keyFile.exists()) {
            val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            keyFile.writeBytes(generated.encoded)
            restrictToOwner(keyFile)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private fun file(entry: String) = File(root, "$entry.enc")

    override fun get(entry: String): String? {
        val f = file(entry)
        if (!f.exists()) return null
        return runCatching {
            val raw = Base64.getDecoder().decode(f.readText())
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(entry.encodeToByteArray())
            cipher.doFinal(body).decodeToString()
        }.getOrNull()
    }

    override fun set(entry: String, value: String): Boolean = runCatching {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(entry.encodeToByteArray())
        val body = cipher.doFinal(value.encodeToByteArray())
        val f = file(entry)
        f.writeText(Base64.getEncoder().encodeToString(iv + body))
        restrictToOwner(f)
        true
    }.getOrDefault(false)

    override fun remove(entry: String) {
        file(entry).delete()
    }

    private fun restrictToOwner(f: File) {
        runCatching {
            f.setReadable(false, false)
            f.setWritable(false, false)
            f.setReadable(true, true)
            f.setWritable(true, true)
        }
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Runs a command, returning its exit code and trimmed stdout.
 *
 * Stdout is drained on its own thread: a keyring prompt holds the pipe open, so reading to EOF here
 * would outlast the timeout below rather than be bounded by it.
 */
private fun run(command: List<String>, stdin: String? = null): Pair<Int, String> {
    val process = ProcessBuilder(command)
        // Never read, and an undrained pipe filling up would wedge the child.
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()

    val out = AtomicReference("")
    val reader = thread(isDaemon = true, name = "plume-secret-read") {
        out.set(runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault(""))
    }

    runCatching {
        stdin?.let { process.outputStream.use { pipe -> pipe.write(it.encodeToByteArray()) } }
            ?: process.outputStream.close()
    }

    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return -1 to ""
    }
    reader.join(READ_GRACE_MILLIS)
    return process.exitValue() to out.get().trim()
}

private const val TIMEOUT_SECONDS = 20L
private const val READ_GRACE_MILLIS = 500L
