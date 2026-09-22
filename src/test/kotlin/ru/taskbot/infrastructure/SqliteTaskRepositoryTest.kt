package ru.taskbot.infrastructure

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.taskbot.domain.TaskLimitExceededException

class SqliteTaskRepositoryTest {
    @Test
    fun `stores tasks between repository instances and isolates scopes`() = runBlocking {
        val directory = Files.createTempDirectory("task-bot-test")
        val database = directory.resolve("tasks.db")
        try {
            val first = SqliteTaskRepository(database)
            assertEquals(1, first.add("telegram:one", "Первая", 50).id)
            assertEquals(2, first.add("telegram:one", "Вторая", 50).id)
            assertFailsWith<TaskLimitExceededException> {
                first.add("telegram:one", "Лишняя", 2)
            }
            assertEquals(2, first.list("telegram:one").size)
            assertTrue(first.complete("telegram:one", 1))
            assertFalse(first.complete("telegram:two", 1))

            val reopened = SqliteTaskRepository(database)
            assertEquals(listOf(true, false), reopened.list("telegram:one").map { it.completed })
            assertTrue(reopened.list("telegram:two").isEmpty())
            assertTrue(reopened.delete("telegram:one", 2))
            assertEquals(listOf(1L), reopened.list("telegram:one").map { it.id })
            assertEquals(1, reopened.deleteCompleted("telegram:one"))
            assertTrue(reopened.list("telegram:one").isEmpty())
        } finally {
            directory.resolve("tasks.db-wal").deleteIfExists()
            directory.resolve("tasks.db-shm").deleteIfExists()
            database.deleteIfExists()
            directory.deleteIfExists()
        }
    }
}
