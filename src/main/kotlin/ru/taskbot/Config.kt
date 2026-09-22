package ru.taskbot

import java.nio.file.Files
import java.nio.file.Path

data class Config(
    val telegramToken: String,
    val tasksDatabase: Path,
    val scopeOverrides: String?,
) {
    companion object {
        fun load(environment: Map<String, String> = System.getenv(), envFile: Path = Path.of(".env")): Config {
            val fileValues = if (Files.exists(envFile)) {
                Files.readAllLines(envFile)
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith('#') && '=' in it }
                    .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
            } else emptyMap()
            val values = fileValues + environment
            val token = values["TELEGRAM_BOT_TOKEN"]
                ?.takeUnless { it.isBlank() || it == "put-real-token-here" }
                ?: error("Укажите TELEGRAM_BOT_TOKEN в .env или переменной окружения.")
            return Config(
                telegramToken = token,
                tasksDatabase = Path.of(values["TASKS_DATABASE"] ?: "data/tasks.db"),
                scopeOverrides = values["TASK_SCOPE_OVERRIDES"],
            )
        }
    }
}
