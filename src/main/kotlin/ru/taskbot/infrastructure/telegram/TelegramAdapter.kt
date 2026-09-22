package ru.taskbot.infrastructure.telegram

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import ru.taskbot.application.IncomingMessage
import ru.taskbot.application.MessageHandler
import ru.taskbot.application.MessengerAdapter

class TelegramAdapter(
    token: String,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MessengerAdapter {
    private val apiUrl = "https://api.telegram.org/bot$token"
    private val logger = Logger.getLogger(TelegramAdapter::class.java.name)

    override suspend fun run(handler: MessageHandler) {
        validateToken()
        logger.info("Telegram bot authenticated; long polling started")
        var offset = 0L
        while (true) {
            try {
                val updates = getUpdates(offset)
                for (update in updates) {
                    offset = maxOf(offset, update.updateId + 1)
                    val message = update.message ?: continue
                    val text = message.text ?: continue
                    val reply = handler.handle(
                        IncomingMessage("telegram", message.chat.id.toString(), text),
                    ) ?: continue
                    sendMessage(message.chat.id, reply)
                }
            } catch (error: Exception) {
                logger.log(Level.WARNING, "Temporary Telegram error; retrying in 2 seconds", error)
                delay(RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun getUpdates(offset: Long): List<TelegramUpdate> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiUrl/getUpdates?timeout=30&offset=$offset&allowed_updates=%5B%22message%22%5D"))
            .timeout(POLL_REQUEST_TIMEOUT)
            .GET()
            .build()
        val response = execute(request)
        val body = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(response.body())
        checkResponse(response, body)
        return body.result.orEmpty()
    }

    private suspend fun sendMessage(chatId: Long, text: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiUrl/sendMessage"))
            .header("Content-Type", "application/json")
            .timeout(SEND_REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(TelegramSendMessage(chatId, text))))
            .build()
        val response = execute(request)
        val body = json.decodeFromString<TelegramResponse<JsonElement>>(response.body())
        checkResponse(response, body)
    }

    private suspend fun validateToken() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiUrl/getMe"))
            .timeout(SEND_REQUEST_TIMEOUT)
            .GET()
            .build()
        val response = execute(request)
        val body = json.decodeFromString<TelegramResponse<JsonElement>>(response.body())
        checkResponse(response, body)
    }

    private fun <T> checkResponse(response: HttpResponse<String>, body: TelegramResponse<T>) {
        if (response.statusCode() !in 200..299 || !body.ok) {
            throw TelegramApiException(
                body.errorCode ?: response.statusCode(),
                body.description ?: "Telegram API request failed",
            )
        }
    }

    private suspend fun execute(request: HttpRequest): HttpResponse<String> = withContext(Dispatchers.IO) {
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val POLL_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(40)
        val SEND_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val RETRY_DELAY_MILLIS = 2_000L
    }
}

private class TelegramApiException(errorCode: Int, description: String) :
    RuntimeException("Telegram API error $errorCode: $description")
