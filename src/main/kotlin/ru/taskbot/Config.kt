package ru.taskbot

import java.nio.file.Files
import java.nio.file.Path

data class Config(
    val telegramToken: String?,
    val yandexMessengerToken: String?,
    val transports: Set<String>,
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
            val transports = (values["BOT_TRANSPORTS"] ?: "telegram")
                .split(',')
                .map(String::trim)
                .toSet()
            require(transports.isNotEmpty() && transports.all { it in setOf("telegram", "yandex") }) {
                "BOT_TRANSPORTS должен содержать telegram, yandex или оба значения через запятую."
            }
            val telegramToken = values["TELEGRAM_BOT_TOKEN"]
                ?.takeUnless { it.isBlank() || it == "put-real-token-here" }
            val yandexMessengerToken = values["YANDEX_MESSENGER_TOKEN"]
                ?.takeUnless { it.isBlank() || it == "put-real-token-here" }
            require("telegram" !in transports || telegramToken != null) {
                "Укажите TELEGRAM_BOT_TOKEN в .env или переменной окружения."
            }
            require("yandex" !in transports || yandexMessengerToken != null) {
                "Укажите YANDEX_MESSENGER_TOKEN в .env или переменной окружения."
            }
            return Config(
                telegramToken = telegramToken,
                yandexMessengerToken = yandexMessengerToken,
                transports = transports,
                tasksDatabase = Path.of(values["TASKS_DATABASE"] ?: "data/tasks.db"),
                scopeOverrides = values["TASK_SCOPE_OVERRIDES"],
            )
        }
    }
}
