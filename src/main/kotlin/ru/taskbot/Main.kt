package ru.taskbot

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import ru.taskbot.application.ScopeResolver
import ru.taskbot.application.TaskCommandHandler
import ru.taskbot.domain.TaskService
import ru.taskbot.infrastructure.SqliteTaskRepository
import ru.taskbot.infrastructure.telegram.TelegramAdapter
import ru.taskbot.infrastructure.yandex.YandexMessengerAdapter

fun main() = runBlocking {
    val config = Config.load()
    val tasks = TaskService(SqliteTaskRepository(config.tasksDatabase))
    val handler = TaskCommandHandler(tasks, ScopeResolver(config.scopeOverrides))
    supervisorScope {
        if ("telegram" in config.transports) {
            launch { TelegramAdapter(requireNotNull(config.telegramToken)).run(handler) }
        }
        if ("yandex" in config.transports) {
            launch { YandexMessengerAdapter(requireNotNull(config.yandexMessengerToken)).run(handler) }
        }
    }
}
