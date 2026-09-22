package ru.taskbot.application

class ScopeResolver(overrides: String?) {
    private val aliases = overrides
        .orEmpty()
        .split(',')
        .mapNotNull { item ->
            val parts = item.trim().split('=', limit = 2)
            if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
        }
        .toMap()

    fun resolve(message: IncomingMessage): String {
        val nativeScope = "${message.platform}:${message.chatId}"
        return aliases[nativeScope] ?: nativeScope
    }
}

