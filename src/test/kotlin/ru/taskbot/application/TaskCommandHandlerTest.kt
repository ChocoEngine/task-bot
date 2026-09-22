package ru.taskbot.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import ru.taskbot.domain.Task
import ru.taskbot.domain.TaskRepository
import ru.taskbot.domain.TaskService

class TaskCommandHandlerTest {
    private val repository = MemoryRepository()
    private val handler = TaskCommandHandler(TaskService(repository), ScopeResolver(null))

    @Test
    fun `full task lifecycle`() = runBlocking {
        val chat = IncomingMessage("telegram", "42", "/add Купить молоко")
        assertEquals("Добавлена задача #1: Купить молоко", handler.handle(chat))
        assertEquals("❌ #1 Купить молоко", handler.handle(chat.copy(text = "/list")))
        assertEquals("✅ Задача #1 выполнена.", handler.handle(chat.copy(text = "/done 1")))
        assertEquals("✅ #1 Купить молоко", handler.handle(chat.copy(text = "/list")))
        assertEquals("Задача #1 удалена.", handler.handle(chat.copy(text = "/delete 1")))
        assertEquals("Список задач пуст.", handler.handle(chat.copy(text = "/list")))
    }

    @Test
    fun `group command may contain bot username`() = runBlocking {
        val message = IncomingMessage("telegram", "group", "/add@my_task_bot Общая задача")
        assertEquals("Добавлена задача #1: Общая задача", handler.handle(message))
    }

    @Test
    fun `different chats have different lists`() = runBlocking {
        handler.handle(IncomingMessage("telegram", "one", "/add Первая"))
        assertEquals("Список задач пуст.", handler.handle(IncomingMessage("telegram", "two", "/list")))
    }

    @Test
    fun `list is limited and completed tasks can be cleared`() = runBlocking {
        val message = IncomingMessage("telegram", "large", "")
        repeat(50) { index ->
            handler.handle(message.copy(text = "/add Задача ${index + 1}"))
        }
        assertEquals(
            "Достигнут лимит в 50 задач. Удалите ненужные задачи или отправьте /clear_done.",
            handler.handle(message.copy(text = "/add Лишняя задача")),
        )
        handler.handle(message.copy(text = "/done 1"))

        val list = handler.handle(message.copy(text = "/list"))!!
        assertEquals(50, list.lineSequence().count { it.startsWith("✅") || it.startsWith("❌") })
        assertEquals("Удалено выполненных задач: 1.", handler.handle(message.copy(text = "/clear_done")))
        assertEquals(false, handler.handle(message.copy(text = "/list"))!!.contains("#1 "))
        assertEquals("Добавлена задача #51: Новая задача", handler.handle(message.copy(text = "/add Новая задача")))
    }
}

private class MemoryRepository : TaskRepository {
    private val tasks = mutableMapOf<String, MutableList<Task>>()

    override suspend fun list(scope: String) = tasks[scope].orEmpty().toList()
    override suspend fun add(scope: String, text: String, limit: Int): Task {
        val list = tasks.getOrPut(scope) { mutableListOf() }
        if (list.size >= limit) throw ru.taskbot.domain.TaskLimitExceededException(limit)
        return Task((list.maxOfOrNull { it.id } ?: 0) + 1, text).also(list::add)
    }
    override suspend fun complete(scope: String, id: Long): Boolean {
        val list = tasks[scope] ?: return false
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        list[index] = list[index].copy(completed = true)
        return true
    }
    override suspend fun delete(scope: String, id: Long): Boolean =
        tasks[scope]?.removeIf { it.id == id } ?: false

    override suspend fun deleteCompleted(scope: String): Int {
        val list = tasks[scope] ?: return 0
        val before = list.size
        list.removeIf { it.completed }
        return before - list.size
    }
}
