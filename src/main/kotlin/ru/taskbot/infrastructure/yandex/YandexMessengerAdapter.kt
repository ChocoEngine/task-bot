package ru.taskbot.infrastructure.yandex

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.taskbot.application.IncomingMessage
import ru.taskbot.application.MessageHandler
import ru.taskbot.application.MessengerAdapter

class YandexMessengerAdapter(
    private val token: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val apiUrl: String = "https://botapi.messenger.yandex.net/bot/v1",
) : MessengerAdapter {
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = Logger.getLogger(YandexMessengerAdapter::class.java.name)
    private var pending: PendingReply? = null

    override suspend fun run(handler: MessageHandler) {
        val self = get("$apiUrl/self/get")
        val bot = parseResponse(self)
        check(bot.webhookUrl.isNullOrBlank()) {
            "У бота Яндекс Мессенджера настроен webhook; отключите его для polling."
        }
        logger.info("Yandex Messenger bot authenticated; polling started")

        var offset = 0L
        while (true) {
            try {
                val nextOffset = pollOnce(handler, offset)
                if (nextOffset == offset) delay(EMPTY_POLL_DELAY_MILLIS)
                offset = nextOffset
            } catch (error: Exception) {
                logger.log(Level.WARNING, "Temporary Yandex Messenger error; retrying in 2 seconds", error)
                delay(RETRY_DELAY_MILLIS)
            }
        }
    }

    internal suspend fun pollOnce(handler: MessageHandler, offset: Long): Long {
        val response = get("$apiUrl/messages/getUpdates/?limit=1&offset=$offset")
        val updates = parseResponse(response).updates.orEmpty().sortedBy { it.updateId }
        var nextOffset = offset
        for (update in updates) {
            if (update.updateId < nextOffset) continue
            val prepared = pending?.takeIf { it.updateId == update.updateId }
                ?: prepareReply(update, handler).also { pending = it }
            if (prepared != null && prepared.text != null) {
                sendReply(prepared)
            }
            pending = null
            nextOffset = update.updateId + 1
        }
        return nextOffset
    }

    private suspend fun prepareReply(update: YandexUpdate, handler: MessageHandler): PendingReply? {
        val text = update.text ?: return null
        if (update.sender.robot == true) return null
        val destination = when (update.chat.type) {
            "private" -> update.sender.login?.let { Destination.Private(it) }
            "group" -> update.chat.id?.let { Destination.Group(it) }
            else -> null
        } ?: return null
        val scopeId = when (destination) {
            is Destination.Private -> "private:${update.sender.id ?: destination.login}"
            is Destination.Group -> destination.chatId
        }
        val reply = handler.handle(IncomingMessage("yandex", scopeId, text))
        return PendingReply(update.updateId, destination, reply)
    }

    private suspend fun sendReply(reply: PendingReply) {
        splitText(reply.text!!).forEachIndexed { index, part ->
            val body = when (val destination = reply.destination) {
                is Destination.Private -> SendText(login = destination.login, text = part,
                    payloadId = "taskbot-${reply.updateId}-$index")
                is Destination.Group -> SendText(chatId = destination.chatId, text = part,
                    payloadId = "taskbot-${reply.updateId}-$index")
            }
            val request = HttpRequest.newBuilder(URI.create("$apiUrl/messages/sendText/"))
                .header("Authorization", "OAuth $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
                .build()
            parseResponse(execute(request))
        }
    }

    private fun splitText(text: String): List<String> {
        if (text.length <= MAX_MESSAGE_LENGTH) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.split('\n')) {
            require(line.length <= MAX_MESSAGE_LENGTH) { "Строка ответа длиннее лимита Яндекс Мессенджера." }
            if (current.isNotEmpty() && current.length + 1 + line.length > MAX_MESSAGE_LENGTH) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private suspend fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "OAuth $token")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        return execute(request)
    }

    private fun parseResponse(response: HttpResponse<String>): YandexResponse {
        val body = json.decodeFromString<YandexResponse>(response.body())
        check(response.statusCode() in 200..299 && body.ok) {
            "Yandex Messenger API error ${response.statusCode()}: ${body.description ?: "request failed"}"
        }
        return body
    }

    private suspend fun execute(request: HttpRequest): HttpResponse<String> = withContext(Dispatchers.IO) {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 6000
        const val EMPTY_POLL_DELAY_MILLIS = 1_000L
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}

private sealed interface Destination {
    data class Private(val login: String) : Destination
    data class Group(val chatId: String) : Destination
}

private data class PendingReply(val updateId: Long, val destination: Destination, val text: String?)

@Serializable
private data class YandexResponse(
    val ok: Boolean,
    val description: String? = null,
    val updates: List<YandexUpdate>? = null,
    @SerialName("webhook_url") val webhookUrl: String? = null,
)

@Serializable
private data class YandexUpdate(
    @SerialName("update_id") val updateId: Long,
    val chat: YandexChat,
    @SerialName("from") val sender: YandexSender,
    val text: String? = null,
)

@Serializable
private data class YandexChat(val type: String, val id: String? = null)

@Serializable
private data class YandexSender(val id: String? = null, val login: String? = null, val robot: Boolean? = null)

@Serializable
private data class SendText(
    @SerialName("chat_id") val chatId: String? = null,
    val login: String? = null,
    val text: String,
    @SerialName("payload_id") val payloadId: String,
)
