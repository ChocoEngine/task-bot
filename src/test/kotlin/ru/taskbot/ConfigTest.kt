package ru.taskbot

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    @Test
    fun `can enable yandex without a telegram token`() {
        val config = Config.load(
            environment = mapOf("BOT_TRANSPORTS" to "yandex", "YANDEX_MESSENGER_TOKEN" to "test-token"),
            envFile = Path.of("nonexistent-test-env-file"),
        )
        assertEquals(setOf("yandex"), config.transports)
        assertEquals(null, config.telegramToken)
    }

    @Test
    fun `requires a token for each enabled transport`() {
        assertFailsWith<IllegalArgumentException> {
            Config.load(environment = mapOf("BOT_TRANSPORTS" to "yandex"),
                envFile = Path.of("nonexistent-test-env-file"))
        }
    }
}
