package ru.taskbot

import kotlinx.coroutines.runBlocking
import ru.taskbot.application.ScopeResolver
import ru.taskbot.application.TaskCommandHandler
import ru.taskbot.domain.TaskService
import ru.taskbot.infrastructure.SqliteTaskRepository
import ru.taskbot.infrastructure.telegram.TelegramAdapter

fun main() = runBlocking {
    val config = Config.load()
    val tasks = TaskService(SqliteTaskRepository(config.tasksDatabase))
    val handler = TaskCommandHandler(tasks, ScopeResolver(config.scopeOverrides))
    TelegramAdapter(config.telegramToken).run(handler)
}
