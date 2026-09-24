package me.longtai.core.ui.files

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and writes CSV files picked through the Storage Access Framework. */
@Singleton
class CsvFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Writes UTF-8 with a BOM so Excel opens Chinese text correctly. */
    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("無法寫入檔案")
        stream.use {
            it.write(UTF8_BOM)
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Reads a text file. UTF-8 (with or without BOM) is preferred; files that are not
     * valid UTF-8 are decoded as Big5, the encoding Traditional Chinese Excel uses.
     */
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val data = input.readBytes()
            if (data.size > MAX_BYTES) throw IOException("檔案過大（上限 10MB）")
            data
        } ?: throw IOException("無法讀取檔案")
        decode(bytes)
    }

    private fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            val big5 = runCatching { Charset.forName("Big5") }.getOrNull() ?: Charsets.ISO_8859_1
            String(body, big5)
        }
    }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        const val MAX_BYTES = 10 * 1024 * 1024
    }
}
