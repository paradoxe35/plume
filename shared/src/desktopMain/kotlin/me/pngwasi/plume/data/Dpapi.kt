package me.pngwasi.plume.data

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/**
 * Windows DPAPI, reached through JNA rather than a helper process.
 *
 * `CryptProtectData` encrypts against the logged-in Windows account, so the ciphertext is useless
 * to any other user on the machine and needs no key of our own to store.
 */
internal object Dpapi {

    @Suppress("FunctionName")
    private interface Crypt32 : StdCallLibrary {
        // The description is LPCWSTR and there is no ANSI variant of this entry point, so a JNA
        // String — which maps to char* — would be read as wide characters and produce garbage.
        // Null is allowed and the description only ever appears in a prompt Plume never triggers.
        fun CryptProtectData(
            input: Blob, description: Pointer?, entropy: Blob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, output: Blob,
        ): Boolean

        fun CryptUnprotectData(
            input: Blob, description: Pointer?, entropy: Blob?, reserved: Pointer?,
            prompt: Pointer?, flags: Int, output: Blob,
        ): Boolean
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun LocalFree(handle: Pointer?): Pointer?
    }

    @Structure.FieldOrder("cbData", "pbData")
    internal class Blob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null

        fun bytes(): ByteArray = pbData?.getByteArray(0, cbData) ?: ByteArray(0)
    }

    private val library: Crypt32 by lazy {
        Native.load("Crypt32", Crypt32::class.java)
    }

    fun load(): Boolean = runCatching { library; true }.getOrDefault(false)

    private fun blobOf(data: ByteArray): Blob {
        val memory = com.sun.jna.Memory(data.size.toLong().coerceAtLeast(1))
        memory.write(0, data, 0, data.size)
        return Blob().apply {
            cbData = data.size
            pbData = memory
        }
    }

    fun protect(data: ByteArray): ByteArray {
        val output = Blob()
        val ok = library.CryptProtectData(blobOf(data), null, null, null, null, 0, output)
        check(ok) { "CryptProtectData failed" }
        return output.take()
    }

    fun unprotect(data: ByteArray): ByteArray {
        val output = Blob()
        val ok = library.CryptUnprotectData(blobOf(data), null, null, null, null, 0, output)
        check(ok) { "CryptUnprotectData failed" }
        return output.take()
    }

    /** DPAPI allocates the output with LocalAlloc, so the caller owns it. */
    private fun Blob.take(): ByteArray {
        val copy = bytes()
        runCatching { kernel32.LocalFree(pbData) }
        return copy
    }

    private val kernel32: Kernel32 by lazy {
        Native.load("Kernel32", Kernel32::class.java)
    }
}
