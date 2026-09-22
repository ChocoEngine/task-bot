package ru.taskbot.infrastructure.yandex

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YandexMessengerAdapterTest {
    @Test
    fun `maps private and group updates to separate scopes and reply destinations`() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val messages = mutableListOf<String>()
        val server = server { path, body, authorization ->
            assertEquals("OAuth test-token", authorization)
            requests += path to body
            when {
                path.contains("offset=0") -> """{"ok":true,"updates":[{"update_id":10,"chat":{"type":"private"},"from":{"id":"user-id","login":"ivan"},"text":"/list"}]}"""
                path.contains("offset=11") -> """{"ok":true,"updates":[{"update_id":11,"chat":{"type":"group","id":"0/0/group"},"from":{"id":"user-id","login":"ivan"},"text":"/help"}]}"""
                path.endsWith("/sendText/") -> """{"ok":true,"message_id":123}"""
                else -> error("Unexpected request: $path")
            }
        }
        try {
            val adapter = YandexMessengerAdapter("test-token", apiUrl = "http://127.0.0.1:${server.address.port}/bot/v1")
            val handler = ru.taskbot.application.MessageHandler { message ->
                messages += "${message.platform}:${message.chatId}:${message.text}"
                "reply"
            }
            assertEquals(11, adapter.pollOnce(handler, 0))
            assertEquals(12, adapter.pollOnce(handler, 11))
            assertEquals(listOf("yandex:private:user-id:/list", "yandex:0/0/group:/help"), messages)
            val sent = requests.filter { it.first.endsWith("/sendText/") }.map { Json.parseToJsonElement(it.second).jsonObject }
            assertEquals("ivan", sent[0]["login"]?.jsonPrimitive?.content)
            assertEquals("reply", sent[0]["text"]?.jsonPrimitive?.content)
            assertEquals("0/0/group", sent[1]["chat_id"]?.jsonPrimitive?.content)
            assertEquals("taskbot-10-0", sent[0]["payload_id"]?.jsonPrimitive?.content)
            assertEquals("taskbot-11-0", sent[1]["payload_id"]?.jsonPrimitive?.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `retries a failed reply without running the command twice`() = runBlocking {
        var sends = 0
        var commands = 0
        val server = server { path, _, _ ->
            when {
                path.contains("getUpdates") -> """{"ok":true,"updates":[{"update_id":20,"chat":{"type":"private"},"from":{"id":"user-id","login":"ivan"},"text":"/add tea"}]}"""
                path.endsWith("/sendText/") -> {
                    sends++
                    if (sends == 1) """{"ok":false,"description":"temporary error"}"""
                    else """{"ok":true,"message_id":123}"""
                }
                else -> error("Unexpected request: $path")
            }
        }
        try {
            val adapter = YandexMessengerAdapter("test-token", apiUrl = "http://127.0.0.1:${server.address.port}/bot/v1")
            val handler = ru.taskbot.application.MessageHandler { commands++; "added" }
            assertFailsWith<IllegalStateException> { adapter.pollOnce(handler, 0) }
            assertEquals(21, adapter.pollOnce(handler, 0))
            assertEquals(1, commands)
            assertEquals(2, sends)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `splits a long task list into messages within the API limit`() = runBlocking {
        val sent = mutableListOf<String>()
        val server = server { path, body, _ ->
            when {
                path.contains("getUpdates") -> """{"ok":true,"updates":[{"update_id":30,"chat":{"type":"group","id":"group"},"from":{"login":"ivan"},"text":"/list"}]}"""
                path.endsWith("/sendText/") -> {
                    sent += Json.parseToJsonElement(body).jsonObject["text"]!!.jsonPrimitive.content
                    """{"ok":true,"message_id":123}"""
                }
                else -> error("Unexpected request: $path")
            }
        }
        try {
            val adapter = YandexMessengerAdapter("test-token", apiUrl = "http://127.0.0.1:${server.address.port}/bot/v1")
            val list = List(20) { "❌ #$it ${"x".repeat(490)}" }.joinToString("\n")
            assertEquals(31, adapter.pollOnce(ru.taskbot.application.MessageHandler { list }, 0))
            assertEquals(list, sent.joinToString("\n"))
            assertEquals(true, sent.size > 1)
            assertEquals(true, sent.all { it.length <= 6000 })
        } finally {
            server.stop(0)
        }
    }

    private fun server(respond: (String, String, String?) -> String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bot/v1/") { exchange ->
            val path = exchange.requestURI.toString()
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val reply = respond(path, body, exchange.requestHeaders.getFirst("Authorization"))
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
