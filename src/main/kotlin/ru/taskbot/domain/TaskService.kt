package ru.taskbot.domain

class TaskService(private val repository: TaskRepository) {
    suspend fun list(scope: String): List<Task> = repository.list(scope)

    suspend fun add(scope: String, text: String): Task {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Текст задачи не может быть пустым." }
        require(normalized.length <= 500) { "Текст задачи не должен быть длиннее 500 символов." }
        return repository.add(scope, normalized, TASK_LIMIT)
    }

    suspend fun complete(scope: String, id: Long): Boolean = repository.complete(scope, id)
    suspend fun delete(scope: String, id: Long): Boolean = repository.delete(scope, id)
    suspend fun deleteCompleted(scope: String): Int = repository.deleteCompleted(scope)

    companion object {
        const val TASK_LIMIT = 50
    }
}

class TaskLimitExceededException(val limit: Int) :
    RuntimeException("Достигнут лимит в $limit задач.")
