package app.halo.launcher.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/** One callable tool for Gemini (function declaration). */
data class Tool(
    val name: String,
    val description: String,
    /** param name → (description, enum values or null). */
    val params: Map<String, Pair<String, List<String>?>> = emptyMap(),
    val required: List<String> = params.keys.toList(),
)

/**
 * Minimal Gemini REST client with function calling (no SDK needed).
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 */
class GeminiClient(private val key: () -> String) {
    private val apiKey: String get() = key()
    companion object {
        /**
         * Keeps at most ~[max] entries but only cuts where a new user *text* turn starts,
         * so a functionCall is never separated from its functionResponse.
         */
        fun trimHistory(history: List<JsonObject>, max: Int): List<JsonObject> {
            if (history.size <= max) return history
            for (i in (history.size - max) until history.size) {
                val e = history[i]
                val isUser = e["role"]?.jsonPrimitive?.contentOrNull == "user"
                val parts = e["parts"]?.jsonArray.orEmpty().map { it.jsonObject }
                if (isUser && parts.any { "text" in it } && parts.none { "functionResponse" in it }) {
                    return history.subList(i, history.size)
                }
            }
            return emptyList()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Model aliases that always point at the current Flash generation; first that works wins. */
    private val models = listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash")
    private var modelIndex = 0

    val configured: Boolean get() = apiKey.isNotBlank()

    private fun toolsJson(tools: List<Tool>): JsonArray = buildJsonArray {
        add(buildJsonObject {
            putJsonArray("functionDeclarations") {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        if (t.params.isNotEmpty()) {
                            putJsonObject("parameters") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    t.params.forEach { (p, spec) ->
                                        putJsonObject(p) {
                                            put("type", "string")
                                            put("description", spec.first)
                                            spec.second?.let { values -> putJsonArray("enum") { values.forEach { add(it) } } }
                                        }
                                    }
                                }
                                putJsonArray("required") { t.required.forEach { add(it) } }
                            }
                        }
                    })
                }
            }
        })
    }

    private fun post(model: String, body: JsonObject): Pair<Int, String> {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /**
     * Runs one user request to completion: the model may call tools (several rounds);
     * [execute] performs each call and returns a short JSON result.
     * Returns the model's final spoken reply.
     */
    suspend fun ask(
        system: String,
        history: List<JsonObject>,
        userText: String,
        tools: List<Tool>,
        execute: suspend (name: String, args: Map<String, String>) -> String,
    ): Pair<String, List<JsonObject>> = withContext(Dispatchers.IO) {
        val contents = history.toMutableList()
        contents += buildJsonObject {
            put("role", "user")
            putJsonArray("parts") { add(buildJsonObject { put("text", userText) }) }
        }
        val toolJson = toolsJson(tools)
        repeat(4) {
            val body = buildJsonObject {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
                }
                put("contents", JsonArray(contents))
                put("tools", toolJson)
                putJsonObject("generationConfig") {
                    // Includes "thinking" tokens on models that think by default.
                    put("maxOutputTokens", 2048)
                }
            }
            var result: Pair<Int, String>
            while (true) {
                result = post(models[modelIndex], body)
                // Unknown model alias → try the next one.
                if (result.first == 404 && modelIndex < models.lastIndex) modelIndex++ else break
            }
            val (code, text) = result
            if (code !in 200..299) {
                val msg = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                }.getOrDefault("HTTP $code")
                error(msg)
            }
            val content = json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject
                ?: return@withContext "Sorry, I have no answer to that." to contents
            val parts = content["parts"]?.jsonArray.orEmpty().map { it.jsonObject }
            if (parts.isEmpty()) return@withContext "Sorry, I couldn’t finish that answer." to contents.dropLast(1)
            contents += content // keep verbatim (incl. thought signatures)
            val calls = parts.mapNotNull { it["functionCall"]?.jsonObject }
            if (calls.isEmpty()) {
                val reply = parts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString(" ").trim()
                return@withContext reply to contents
            }
            val responses = calls.map { call ->
                val name = call["name"]?.jsonPrimitive?.content.orEmpty()
                val args = call["args"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() }
                val out = runCatching { execute(name, args) }.getOrElse { "error: ${it.message}" }
                buildJsonObject {
                    putJsonObject("functionResponse") {
                        call["id"]?.let { put("id", it) }
                        put("name", name)
                        putJsonObject("response") { put("result", out) }
                    }
                }
            }
            contents += buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(responses))
            }
        }
        "Done." to contents
    }
}
