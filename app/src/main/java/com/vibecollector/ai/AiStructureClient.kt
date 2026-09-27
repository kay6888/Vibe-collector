package com.vibecollector.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Optional: ask an assistant for a project structure from a one-line description.
 *
 * Uses the OpenAI-compatible `/chat/completions` shape, which DeepSeek,
 * OpenAI, Together and most local servers speak. The key is the user's own and
 * never leaves the device except in the request body.
 *
 * The parser is entirely local — this is a convenience, not a dependency. If no
 * key is set, the scaffold screen still works from pasted text.
 */
class AiStructureClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun generateStructure(
        apiBase: String,
        apiKey: String,
        model: String,
        description: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("No API key saved"))
        if (description.isBlank()) return@withContext Result.failure(IllegalStateException("Describe the project first"))

        val base = apiBase.trimEnd('/')
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            return@withContext Result.failure(IllegalStateException("API base must start with http:// or https://"))
        }

        runCatching {
            val connection = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val payload = json.encodeToString(
                    ChatRequest(
                        model = model,
                        messages = listOf(
                            ChatMessage("system", SYSTEM_PROMPT),
                            ChatMessage("user", description),
                        ),
                        temperature = 0.2,
                    )
                )
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    val err = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    throw IllegalStateException("HTTP $code: ${err.take(300).ifBlank { connection.responseMessage }}")
                }

                val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val parsed = json.decodeFromString<ChatResponse>(body)
                val content = parsed.choices.firstOrNull()?.message?.content
                    ?: throw IllegalStateException("Empty response from $model")
                // The model may wrap the tree in a fence; the parser copes with that,
                // but stripping it makes the preview cleaner.
                content.trim().removeSurrounding("```").trim().removePrefix("text").trim()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You output only a project directory tree. No prose, no explanation, no
            file contents, no markdown fences.

            Rules:
            - One path per line, indented by four spaces per level.
            - Directories end with a forward slash.
            - Use realistic paths and file names for the project described.
            - Include the common config files the project would need.
            - No numbering, no bullets, no commentary, no trailing sentences.
        """.trimIndent()
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val stream: Boolean = false,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(val choices: List<Choice> = emptyList())

@Serializable
private data class Choice(val message: ChatMessage)

@Suppress("unused")
@Serializable
private data class Usage(@SerialName("total_tokens") val totalTokens: Int = 0)
