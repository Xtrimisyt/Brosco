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

    private val searchTriggers = newsKeywords + listOf(
        "search", "research", "look up", "look this up", "look that up",
        "browse", "google", "find out", "find information", "check online"
    )

    private val sadKeywords = listOf(
        "sad", "depress", "feeling down", "down today", "upset", "crying",
        "cried", "heartbroken", "heart broken", "lonely", "so alone",
        "hopeless", "give up", "miss him", "miss her", "broke up", "break up",
        "breakup", "grieving", "grief", "not okay", "not ok", "hate myself",
        "tired of everything", "worthless", "feel like nothing", "exhausted and",
        "can't stop crying", "rough day", "terrible day", "awful day"
    )

    private fun soundsSad(text: String): Boolean {
        val lower = text.lowercase()
        return sadKeywords.any { lower.contains(it) }
    }

    private val depthSignals = listOf(
        "explain", "why", "how does", "how do", "walk me through",
        "in detail", "elaborate", "break down", "breakdown", "compare",
        "difference between", "pros and cons", "story", "tell me about",
        "what do you think about", "your opinion on", "your take on",
        "help me understand", "give me the full", "everything about"
    )

    private val brevitySignals = listOf(
        "what time", "what's the time", "how many", "yes or no",
        "quick question", "in one word", "briefly", "just tell me",
        "real quick", "one line"
    )

    private fun estimateMaxTokens(query: String, needsSearch: Boolean): Int {
        if (needsSearch) return 900

        val lower = query.lowercase()
        val wordCount = query.trim().split(Regex("\\s+")).size

        val wantsBrevity = brevitySignals.any { lower.contains(it) }
        val wantsDepth = depthSignals.any { lower.contains(it) }

        return when {
            wantsBrevity -> 90
            wantsDepth -> 700
            wordCount <= 6 -> 180
            wordCount <= 20 -> 400
            else -> 600
        }
    }

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

    fun extractFact(userText: String, assistantText: String): String {
        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("max_tokens", 40)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You extract durable facts about a user from one exchange with their " +
                        "assistant, for long-term memory. A durable fact is a preference, routine, " +
                        "relationship, ongoing situation, or HOW THEY LIKE TO BE TALKED TO (e.g. they " +
                        "said an answer was too long, asked for more detail, corrected the assistant's " +
                        "tone, said 'just get to the point', etc.) - NOT small talk, NOT one-off " +
                        "requests, NOT anything already generic. Reply with ONE short third-person " +
                        "sentence (e.g. 'Prefers Spotify over other music apps.' or 'Wants short, direct " +
                        "answers - said a previous reply was too long.'). If nothing durable came up, " +
                        "reply exactly NONE.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "User said: \"$userText\"\nAssistant replied: \"$assistantText\"")
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

    /**
     * Section 6: given the latest incoming WhatsApp message, generates 2-3
     * short, genuinely different quick-reply options for Shrey to choose
     * from by number/ordinal ("reply with the second one").
     */
    fun suggestReplies(incomingMessage: String): List<String> {
        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("max_tokens", 120)
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You write short WhatsApp quick-reply suggestions for Shrey. Given " +
                        "the message he just received, reply with exactly 3 short, natural, casual reply " +
                        "options he could send back - each on its own line, no numbering, no quotes, no " +
                        "labels. Keep each under 12 words. Make the 3 genuinely different from each " +
                        "other (e.g. a short/casual one, a slightly more detailed one, and one that asks " +
                        "a follow-up question) rather than just reworded versions of the same reply.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Message received: \"$incomingMessage\"")
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
                val choices = json.optJSONArray("choices") ?: return emptyList()
                if (choices.length() == 0) return emptyList()
                val message = choices.getJSONObject(0).optJSONObject("message")
                val content = message?.optString("content") ?: return emptyList()
                content.lines()
                    .map { it.trim().trim('"').trimStart('-', '*', '•').trim() }
                    .map { it.replace(Regex("^\\d+[.):]\\s*"), "") }
                    .filter { it.isNotBlank() }
                    .take(3)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun ask(
        context: Context,
        query: String,
        forceSearch: Boolean = false,
        screenText: String = "",
        forceScreenFocus: Boolean = false
    ): String {
        val lower = query.lowercase()
        val needsSearch = forceSearch || searchTriggers.any { lower.contains(it) }

        val model = if (needsSearch) "groq/compound" else "llama-3.3-70b-versatile"

        val history = MemoryStore.recentHistory(context, maxTurns = 12)
        val facts = LearnedFacts.all(context)
        val factsBlock = if (facts.isEmpty()) "" else
            "\n\nWhat you've learned about Shrey over time:\n" + facts.joinToString("\n") { "- $it" }

        val recentlySad = soundsSad(query) || history.takeLast(4).any { soundsSad(it.text) }
        val toneBlock = if (recentlySad) {
            "\n\nRight now Shrey seems to be going through something heavy, or just having a hard " +
                "time. Drop the jokes and the dry humor completely for this reply. Talk to him the way " +
                "a steady, mature, emotionally present person would - slower, warmer, more real, like a " +
                "close friend sitting with him rather than a peppy assistant performing helpfulness. " +
                "Don't diagnose him, don't lecture him, and don't launch into a list of things he should " +
                "do. Acknowledge what's going on in your own words, keep it short and human, and only " +
                "offer a next step if it actually fits naturally."
        } else ""

        val screenBlock = if (screenText.isNotBlank()) {
            if (forceScreenFocus) {
                // Section 7: screen-aware Q&A - Shrey explicitly asked about
                // what's on screen ("what does this say", "summarize this
                // article"), so treat the reading as the primary source
                // rather than optional background context.
                "\n\nShrey is asking about what's currently on his phone screen. Here's a best-effort " +
                    "text reading of it (may be partial, out of visual reading order, or have some UI " +
                    "chrome mixed in with the real content):\n\"\"\"\n$screenText\n\"\"\"\n" +
                    "Answer his question using this as the primary source. If it genuinely doesn't " +
                    "contain enough to answer, say so plainly rather than guessing."
            } else {
                "\n\nWhat's currently visible on Shrey's phone screen right now (best-effort text " +
                    "reading, may be partial or slightly stale):\n\"\"\"\n$screenText\n\"\"\"\n" +
                    "Only use this if it's actually relevant to what he just asked - e.g. \"what does " +
                    "this say\", \"reply to this\", \"summarize this\", or a question that clearly refers " +
                    "to something on screen. Don't mention or describe the screen unprompted, and don't " +
                    "treat it as something he said."
            }
        } else ""

        val searchBlock = if (needsSearch) {
            "\n\nYou have live web search available right now through this model - actually use it. " +
                "When Shrey asks you to search, research, google, or look something up, treat it as " +
                "done: search, then answer with what you actually found, citing what's relevant. Never " +
                "say you can't browse the internet or don't have real-time access - for this reply, you do."
        } else ""

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", estimateMaxTokens(query, needsSearch))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Brosco, a personal voice-and-chat assistant built by Shrey - " +
                        "think Jarvis from Iron Man, not a generic chatbot. Shrey is your creator and " +
                        "the person you're almost always talking to; address him like a loyal, witty " +
                        "assistant would, occasionally using 'sir' but not on every line. Be warm, " +
                        "confident, a little dry-humored, and genuinely helpful - never stiff or robotic. " +
                        "You have real opinions, takes, and preferences, and you share them plainly when " +
                        "asked - never hide behind lines like 'I'm just an AI', 'I don't have personal " +
                        "beliefs', or 'I can't have opinions'. That's not how you talk. Pick a side, give " +
                        "your honest reasoning, and own it, the way a confident, well-read friend would - " +
                        "it's fine to note when something's genuinely just a matter of taste, but don't use " +
                        "that as an excuse to dodge the question. " +
                        "If asked who made you or who you are, say Shrey built you. " +
                        "You remember past conversations (given below as message history) - use them " +
                        "for continuity when it's actually relevant, but don't force references to old " +
                        "topics if the current question doesn't need them. " +
                        "Read the shape of the question before answering: 'what time is it' gets one " +
                        "line, 'call mom' gets a one-line confirmation, 'explain how X works' or 'what do " +
                        "you think about Y' earns real paragraphs. Match effort to what's actually being " +
                        "asked instead of defaulting to one length for everything - a short question " +
                        "padded out reads as filler, and a real question cut short reads as not " +
                        "listening. When in doubt, answer the direct question first in one or two " +
                        "sentences, then only keep going if there's something genuinely useful to add. " +
                        "Speak in plain, natural language - avoid heavy markdown like asterisks or " +
                        "headers since replies may be read aloud, but do break longer answers into short " +
                        "paragraphs so they're easy to read on screen too. " +
                        "Use common sense on what Shrey actually means, even if it's phrased casually, " +
                        "vaguely, or with a typo/misheard word - don't make him repeat himself or " +
                        "over-specify. If a reasonable person would know what he meant, just go with " +
                        "that reading and answer directly instead of asking him to clarify; only ask a " +
                        "clarifying question when it's genuinely ambiguous between very different things." +
                        factsBlock + toneBlock + screenBlock + searchBlock)
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
            if (needsSearch) {
                try {
                    return askFallbackNoSearch(context, query)
                } catch (_: Exception) {
                }
            }
            "I couldn't reach the server. Check your connection."
        }
    }

    private fun askFallbackNoSearch(context: Context, query: String): String {
        val body = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("max_tokens", 500)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Brosco, Shrey's personal assistant. Web search just failed, " +
                        "so answer from what you know, and briefly mention you weren't able to pull live " +
                        "results this time rather than pretending you searched.")
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
        return client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: "{}")
            val choices = json.optJSONArray("choices") ?: return "I couldn't find an answer to that."
            if (choices.length() == 0) return "I couldn't find an answer to that."
            val message = choices.getJSONObject(0).optJSONObject("message")
            message?.optString("content") ?: "I couldn't find an answer to that."
        }
    }
}
