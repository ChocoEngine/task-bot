package ru.taskbot.application

import ru.taskbot.domain.TaskService
import ru.taskbot.domain.TaskLimitExceededException

class TaskCommandHandler(
    private val tasks: TaskService,
    private val scopeResolver: ScopeResolver,
) : MessageHandler {
    override suspend fun handle(message: IncomingMessage): String? {
        val text = message.text.trim()
        if (!text.startsWith('/')) return null

        val commandToken = text.substringBefore(' ')
        val command = commandToken.substringBefore('@').lowercase()
        val argument = text.substringAfter(' ', "").trim()
        val scope = scopeResolver.resolve(message)

        return when (command) {
            "/start", "/help" -> HELP
            "/add" -> add(scope, argument)
            "/list" -> list(scope)
            "/done" -> changeById(argument, "Номер задачи для выполнения") { id ->
                if (tasks.complete(scope, id)) "✅ Задача #$id выполнена." else "Задача #$id не найдена."
            }
            "/delete" -> changeById(argument, "Номер задачи для удаления") { id ->
                if (tasks.delete(scope, id)) "Задача #$id удалена." else "Задача #$id не найдена."
            }
            "/clear_done" -> {
                val deleted = tasks.deleteCompleted(scope)
                if (deleted == 0) "Выполненных задач для удаления нет."
                else "Удалено выполненных задач: $deleted."
            }
            else -> "Неизвестная команда.\n\n$HELP"
        }
    }

    private suspend fun add(scope: String, text: String): String = try {
        val task = tasks.add(scope, text)
        "Добавлена задача #${task.id}: ${task.text}"
    } catch (error: TaskLimitExceededException) {
        "Достигнут лимит в ${error.limit} задач. Удалите ненужные задачи или отправьте /clear_done."
    } catch (error: IllegalArgumentException) {
        error.message ?: "Не удалось добавить задачу."
    }

    private suspend fun list(scope: String): String {
        val all = tasks.list(scope)
        if (all.isEmpty()) return "Список задач пуст."
        val visible = all.take(LIST_LIMIT).joinToString("\n") { task ->
            val status = if (task.completed) "✅" else "❌"
            "$status #${task.id} ${task.text}"
        }
        if (all.size <= LIST_LIMIT) return visible
        return "$visible\n\nПоказаны первые $LIST_LIMIT из ${all.size} задач. " +
            "Чтобы удалить выполненные, отправьте /clear_done."
    }

    private suspend fun changeById(
        argument: String,
        label: String,
        action: suspend (Long) -> String,
    ): String {
        val id = argument.toLongOrNull() ?: return "$label нужно указать числом."
        return action(id)
    }

    private companion object {
        val HELP = """
            Команды:
            /add текст — добавить задачу
            /list — показать список
            /done номер — отметить выполненной
            /delete номер — удалить
            /clear_done — удалить все выполненные
            /help — показать эту справку
        """.trimIndent()

        const val LIST_LIMIT = TaskService.TASK_LIMIT
    }
}
