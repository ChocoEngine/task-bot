package ru.taskbot.infrastructure.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val parameters: JsonObject? = null,
)

@Serializable
data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
)

@Serializable
data class TelegramMessage(
    val chat: TelegramChat,
    val text: String? = null,
)

@Serializable
data class TelegramChat(val id: Long)

@Serializable
data class TelegramSendMessage(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
)
