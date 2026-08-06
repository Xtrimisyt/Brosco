package com.brosco.assistant

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

    fun ask(query: String): String {
        val lower = query.lowercase()
        val needsSearch = newsKeywords.any { lower.contains(it) }

        val model = if (needsSearch) "groq/compound" else "llama-3.3-70b-versatile"

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
                        "Answer in 1-3 short spoken sentences. No markdown, no lists, no asterisks - " +
                        "just plain spoken language, since this is read aloud by text-to-speech.")
                })
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
