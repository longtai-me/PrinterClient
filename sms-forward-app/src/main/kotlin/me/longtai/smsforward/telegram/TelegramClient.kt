package me.longtai.smsforward.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Telegram Bot API client used to forward messages to the owner's own chat.
 * Uses HttpURLConnection so it needs no extra dependency. All calls run on the IO dispatcher
 * with short timeouts so they stay within the SMS receiver's background window.
 */
@Singleton
class TelegramClient @Inject constructor() {

    /** Sends [text] to [chatId] through the bot. */
    suspend fun sendMessage(token: String, chatId: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildString {
                append("chat_id=").append(encode(chatId))
                append("&disable_web_page_preview=true")
                append("&text=").append(encode(text.take(MAX_TELEGRAM_CHARS)))
            }
            val json = post(token, "sendMessage", body)
            if (!json.optBoolean("ok", false)) {
                throw IOException(json.optString("description", "Telegram 回應失敗"))
            }
        }
    }

    /**
     * Finds the chat id of the most recent message sent to the bot, so the owner can tap
     * "detect" after messaging their bot instead of looking the id up by hand.
     */
    suspend fun detectChatId(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get(token, "getUpdates?limit=10&timeout=0")
            if (!json.optBoolean("ok", false)) throw IOException(json.optString("description", "無法取得更新"))
            val updates = json.optJSONArray("result") ?: throw IOException("沒有更新")
            for (i in updates.length() - 1 downTo 0) {
                val message = updates.getJSONObject(i).let { it.optJSONObject("message") ?: it.optJSONObject("channel_post") }
                val chat = message?.optJSONObject("chat")
                val id = chat?.opt("id")?.toString()
                if (!id.isNullOrEmpty()) return@runCatching id
            }
            throw IOException("找不到對話，請先在 Telegram 對這個 Bot 傳一則訊息")
        }
    }

    /** Verifies a token and returns the bot's @username. */
    suspend fun getMe(token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = get(token, "getMe")
            if (!json.optBoolean("ok", false)) throw IOException(json.optString("description", "Token 無效"))
            json.getJSONObject("result").optString("username", "bot")
        }
    }

    private fun post(token: String, method: String, body: String): JSONObject {
        val connection = open(token, method)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return read(connection)
    }

    private fun get(token: String, methodWithQuery: String): JSONObject {
        val connection = open(token, methodWithQuery)
        connection.requestMethod = "GET"
        return read(connection)
    }

    @Suppress("DEPRECATION")
    private fun open(token: String, path: String): HttpURLConnection {
        require(token.contains(':')) { "Token 格式錯誤" }
        val url = URL("https://api.telegram.org/bot$token/$path")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
    }

    private fun read(connection: HttpURLConnection): JSONObject {
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            JSONObject(text)
        } catch (e: Exception) {
            Timber.w(e, "telegram read failed")
            throw IOException("連線失敗：${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_TELEGRAM_CHARS = 4_000
    }
}
