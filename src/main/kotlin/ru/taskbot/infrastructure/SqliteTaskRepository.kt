package ru.taskbot.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.taskbot.domain.Task
import ru.taskbot.domain.TaskLimitExceededException
import ru.taskbot.domain.TaskRepository

class SqliteTaskRepository(database: Path) : TaskRepository {
    private val mutex = Mutex()
    private val jdbcUrl: String

    init {
        database.toAbsolutePath().parent?.let(Files::createDirectories)
        jdbcUrl = "jdbc:sqlite:${database.toAbsolutePath()}"
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tasks (
                        scope TEXT NOT NULL,
                        id INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
                        PRIMARY KEY (scope, id)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override suspend fun list(scope: String): List<Task> = locked {
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT id, text, completed FROM tasks WHERE scope = ? ORDER BY id",
            ).use { statement ->
                statement.setString(1, scope)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(Task(rows.getLong("id"), rows.getString("text"), rows.getBoolean("completed")))
                        }
                    }
                }
            }
        }
    }

    override suspend fun add(scope: String, text: String, limit: Int): Task = locked {
        connection().use { connection ->
            connection.autoCommit = false
            try {
                val currentCount = connection.prepareStatement(
                    "SELECT COUNT(*) FROM tasks WHERE scope = ?",
                ).use { statement ->
                    statement.setString(1, scope)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
                }
                if (currentCount >= limit) throw TaskLimitExceededException(limit)

                val nextId = connection.prepareStatement(
                    "SELECT COALESCE(MAX(id), 0) + 1 FROM tasks WHERE scope = ?",
                ).use { statement ->
                    statement.setString(1, scope)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO tasks(scope, id, text, completed) VALUES (?, ?, ?, 0)",
                ).use { statement ->
                    statement.setString(1, scope)
                    statement.setLong(2, nextId)
                    statement.setString(3, text)
                    statement.executeUpdate()
                }
                connection.commit()
                Task(nextId, text)
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    override suspend fun complete(scope: String, id: Long): Boolean = locked {
        connection().use { connection ->
            connection.prepareStatement(
                "UPDATE tasks SET completed = 1 WHERE scope = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, scope)
                statement.setLong(2, id)
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun delete(scope: String, id: Long): Boolean = locked {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM tasks WHERE scope = ? AND id = ?").use { statement ->
                statement.setString(1, scope)
                statement.setLong(2, id)
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun deleteCompleted(scope: String): Int = locked {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM tasks WHERE scope = ? AND completed = 1").use { statement ->
                statement.setString(1, scope)
                statement.executeUpdate()
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl)

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }
}
