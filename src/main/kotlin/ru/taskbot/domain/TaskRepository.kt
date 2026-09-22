package ru.taskbot.domain

interface TaskRepository {
    suspend fun list(scope: String): List<Task>
    suspend fun add(scope: String, text: String, limit: Int): Task
    suspend fun complete(scope: String, id: Long): Boolean
    suspend fun delete(scope: String, id: Long): Boolean
    suspend fun deleteCompleted(scope: String): Int
}
