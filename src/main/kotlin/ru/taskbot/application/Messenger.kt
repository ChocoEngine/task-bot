package ru.taskbot.application

data class IncomingMessage(
    val platform: String,
    val chatId: String,
    val text: String,
)

fun interface MessageHandler {
    suspend fun handle(message: IncomingMessage): String?
}

interface MessengerAdapter {
    suspend fun run(handler: MessageHandler)
}

