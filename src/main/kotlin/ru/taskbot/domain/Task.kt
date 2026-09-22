package ru.taskbot.domain

data class Task(
    val id: Long,
    val text: String,
    val completed: Boolean = false,
)
