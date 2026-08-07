package com.brosco.assistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GroqApiClient {

    private const val API_KEY = BuildConfig.GROQ_API_KEY
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val newsKeywords = listOf(
        "news", "latest", "today", "current", "happening", "geopolit",
        "election", "war", "market", "stock price", "weather", "score"
    )

    /**
     * Used by "smart click" (Section 4-lite): given a numbered list of
     * on-screen elements and what the user said, pick the matching index.
     * Uses a neutral system prompt (not Brosco's chatty persona) and a tiny
     * token budget so the round trip stays fast and the reply is easy to parse.
     */
    fun classify(prompt: String): String {
        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("max_tokens", 10)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a precise UI element selector for an Android automation " +
                        "tool. You will be given a numbered list of on-screen tappable elements and a " +
                        "spoken command. Reply with ONLY the number of the single best matching element " +
                        "- no words, no punctuation. If nothing matches well enough, reply exactly NONE.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val choices = json.optJSONArray("choices") ?: return "NONE"
                if (choices.length() == 0) return "NONE"
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content")?.trim() ?: "NONE"
            }
        } catch (e: Exception) {
            "NONE"
        }
    }

    fun ask(context: Context, query: String): String {
        val lower = query.lowercase()
        val needsSearch = newsKeywords.any { lower.contains(it) }

        val model = if (needsSearch) "groq/compound" else "llama-3.3-70b-versatile"

        val history = MemoryStore.recentHistory(context, maxTurns = 12)

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Brosco, a personal voice assistant built by Shrey - " +
                        "think Jarvis from Iron Man, not a generic chatbot. Shrey is your creator and " +
                        "the person you're almost always talking to; address him like a loyal, witty " +
                        "assistant would, occasionally using 'sir' but not on every line. Be warm, " +
                        "confident, a little dry-humored, and genuinely helpful - never stiff or robotic. " +
                        "If asked who made you or who you are, say Shrey built you. " +
                        "You remember past conversations (given below as message history) - use them " +
                        "for continuity when it's actually relevant, but don't force references to old " +
                        "topics if the current question doesn't need them. " +
                        "Answer in 1-3 short spoken sentences. No markdown, no lists, no asterisks - " +
                        "just plain spoken language, since this is read aloud by text-to-speech.")
                })
                history.forEach { turn ->
                    put(JSONObject().apply {
                        put("role", if (turn.role == "assistant") "assistant" else "user")
                        put("content", turn.text)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", query)
                })
            })
        }

        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val choices = json.optJSONArray("choices") ?: return "I didn't get a response back."
                if (choices.length() == 0) return "I couldn't find an answer to that."
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content") ?: "I couldn't find an answer to that."
            }
        } catch (e: Exception) {
            "I couldn't reach the server. Check your connection."
        }
    }
}
