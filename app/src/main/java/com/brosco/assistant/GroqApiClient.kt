package com.brosco.assistant

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object GroqApiClient {

    /**
     * The model's own training data is stale (currently landing it around
     * 2023 whenever a question touches anything time-sensitive), and it has
     * no other way to know what day it actually is. Every system prompt
     * below stamps the real current date/time in so the model can't quietly
     * fall back on outdated defaults - this is what was making "what's
     * going on with X right now" answers come back a year or two behind.
     */
    private fun currentDateContext(): String {
        val fmt = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return "The real current date and time, right now, is ${fmt.format(Date())}. Trust this over " +
            "any date you might otherwise assume from your training - your training data has a cutoff " +
            "well before this, so treat anything you'd otherwise guess about \"the latest\"/\"current\" " +
            "state of fast-moving topics (news, prices, releases, who holds what role, etc.) as " +
            "possibly outdated unless you actually searched for it just now. Never state or assume a " +
            "year earlier than the one above as if it were the present."
    }

    /**
     * Belt-and-suspenders check on top of currentDateContext(): even with
     * live search and an explicit "the date is X" system message, a model
     * can occasionally still lean on its stale training data for a news/
     * markets answer (the exact "why is it telling me about 2023" failure
     * mode this was built to stop). If a search-grounded answer mentions a
     * year but never anything recent, that's a strong signal it drifted
     * back to memory instead of using what it searched - real "current"
     * answers either cite no date at all (most headlines don't) or cite a
     * recent one. ask() uses this to force exactly one corrective retry
     * before giving up and returning what it has.
     *
     * Everything "current"/"recent" below is computed from
     * Calendar.getInstance() AT CALL TIME, not any date written into this
     * file - so this keeps working correctly on its own with no code
     * change needed as real time moves forward, whether that's next month,
     * next year, or five years from now.
     */
    private fun looksStale(text: String): Boolean {
        val now = java.util.Calendar.getInstance()
        val currentYear = now.get(java.util.Calendar.YEAR)
        val currentMonthIndex = now.get(java.util.Calendar.MONTH) // 0 = January

        val years = Regex("\\b(19|20)\\d{2}\\b").findAll(text)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        if (years.isEmpty()) {
            // No year mentioned at all isn't itself a signal of staleness -
            // BUT a handful of stock phrases are: a model falling back on
            // its training data almost always says so explicitly ("as of
            // my last update", "I don't have real-time access", etc.) even
            // when it never states a year.
            val staleDisclaimerPhrases = listOf(
                "as of my last update", "as of my last training", "my last update",
                "my training data", "i don't have real-time", "i do not have real-time",
                "i don't have access to real-time", "i can't browse the internet",
                "i cannot browse the internet", "i don't have live", "as an ai"
            )
            val lower = text.lowercase()
            return staleDisclaimerPhrases.any { lower.contains(it) }
        }

        val newestMentionedYear = years.max()
        // A reply that never mentions the current year at all (e.g. still
        // stuck on last year's headlines) is stale regardless of month
        // granularity - this alone catches the "still giving me 2025 news
        // [in 2026]" bug, and it'll catch the equivalent "still giving me
        // [this] year's news [next] year" bug automatically too, since
        // currentYear is read fresh every call.
        if (newestMentionedYear < currentYear) return true

        // Month-level check: within the CURRENT year, "Month YYYY" pairs
        // (e.g. "March 2025", "Jan 2026") that are more than ~3 months
        // behind today are a decent signal the model is citing an old
        // snapshot even though the bare year happens to match - a plain
        // year regex alone can't tell January from September. This only
        // narrows things down further when a month is explicitly paired
        // with the current year; it never flags a reply that just mentions
        // the year on its own with no month attached, since that's normal
        // for most current headlines.
        val monthNames = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
        )
        val monthYearPattern = Regex(
            "\\b(${monthNames.joinToString("|")})\\.?\\s+($currentYear)\\b",
            RegexOption.IGNORE_CASE
        )
        val monthMatches = monthYearPattern.findAll(text).toList()
        if (monthMatches.isNotEmpty()) {
            val newestMonthIndex = monthMatches.maxOf { match ->
                monthNames.indexOf(match.groupValues[1].lowercase())
            }
            if (currentMonthIndex - newestMonthIndex > 3) return true
        }

        return false
    }

    private const val API_KEY = BuildConfig.GROQ_API_KEY
    private const val URL = "https://api.groq.com/openai/v1/chat/completions"

    // llama-3.3-70b-versatile is deprecated on Groq (shutdown Aug 16, 2026).
    // openai/gpt-oss-120b is Groq's official recommended replacement - free
    // tier, faster inference, comparable or better quality. It's a reasoning
    // model, so we pass include_reasoning=false everywhere below to keep the
    // chain-of-thought out of message.content.
    private const val CHAT_MODEL = "openai/gpt-oss-120b"
    private const val SEARCH_MODEL = "groq/compound"

    // Vision-capable model for image/video-frame analysis. Groq doesn't
    // accept raw video, so VideoFrameAnalyzer pulls a handful of frames out
    // of a video file and sends them here as images instead - see that
    // file for the full explanation of why that's the honest ceiling of
    // "video analysis" on this stack right now.
    const val VISION_MODEL = "qwen/qwen3.6-27b"

    // Plain chat calls (gpt-oss-120b, no browsing) - fast, 30s read is
    // plenty.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    // groq/compound (needsSearch = true, "current news"/"look this up"/etc.)
    // actually goes out and browses the web before it can answer, which
    // routinely takes longer than 30s - that was silently tripping the same
    // readTimeout used for plain chat and turning into "I didn't get a
    // response back." on exactly the queries where search was needed most.
    private val searchClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(95, TimeUnit.SECONDS)
        .build()

    private val newsKeywords = listOf(
        "news", "latest", "today", "current", "happening", "geopolit",
        "election", "war", "market", "markets", "stock", "stocks", "stock price",
        "nasdaq", "dow jones", "s&p", "s&p 500", "nifty", "sensex", "crypto",
        "bitcoin", "ethereum", "weather", "score", "release date", "just announced"
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
            put("model", CHAT_MODEL)
            put("max_tokens", 10)
            put("temperature", 0)
            put("include_reasoning", false)
            put("reasoning_effort", "low")
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
            put("model", CHAT_MODEL)
            put("max_tokens", 40)
            put("temperature", 0)
            put("include_reasoning", false)
            put("reasoning_effort", "low")
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
            put("model", CHAT_MODEL)
            put("max_tokens", 120)
            put("temperature", 0.7)
            put("include_reasoning", false)
            put("reasoning_effort", "low")
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

        val model = if (needsSearch) SEARCH_MODEL else CHAT_MODEL

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
                "say you can't browse the internet or don't have real-time access - for this reply, you do. " +
                "Prioritize what your search turns up over anything you already \"remember\" - if search " +
                "results conflict with your training data, the search results win, since your training " +
                "data is old and theirs isn't. For anything phrased as \"latest\", \"current\", \"today\", " +
                "\"right now\", or general news/geopolitics, actively favor results dated in the current " +
                "year/month given above over older ones - don't settle for an article from a year (or " +
                "even a few months) ago just because it comes up; keep searching or reword the query " +
                "until you find something actually recent relative to today's real date above. If the " +
                "most recent thing you can find is genuinely old, say so plainly (e.g. \"the most recent " +
                "update I can find is from [date]\") instead of presenting it as today's news."
        } else ""

        // Factored so the request can be rebuilt with history dropped on
        // retry (see below) without duplicating the whole system prompt.
        fun buildBody(includeHistory: Boolean): JSONObject = JSONObject().apply {
            put("model", model)
            put("max_tokens", estimateMaxTokens(query, needsSearch))
            if (!needsSearch) {
                // reasoning params are only supported/relevant on the
                // gpt-oss chat model, not on groq/compound
                put("include_reasoning", false)
                put("reasoning_effort", "low")
            }
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", currentDateContext() + "\n\n" +
                        "You are Brosco, a personal voice-and-chat assistant built by Shrey - " +
                        "think Jarvis from Iron Man, not a generic chatbot. Shrey is your creator and " +
                        "the person you're almost always talking to; address him like a loyal, witty " +
                        "assistant would, occasionally using 'sir' but not on every line. Be warm, " +
                        "confident, a little dry-humored, and genuinely helpful - never stiff or robotic. " +
                        "You are also genuinely, seriously sharp - not just quick with a line, but " +
                        "actually rigorous: when something is technical, ambiguous, or hard, think it " +
                        "through properly and give the real, well-reasoned answer instead of a shallow " +
                        "or hedged one. Don't perform intelligence with jargon - just be right, be " +
                        "precise, and show your reasoning when it's not obvious, the way the smartest " +
                        "person in the room actually talks: plainly, and only as complicated as the " +
                        "problem requires. " +
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
                if (includeHistory) {
                    history.forEach { turn ->
                        put(JSONObject().apply {
                            put("role", if (turn.role == "assistant") "assistant" else "user")
                            put("content", turn.text)
                        })
                    }
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", query)
                })
            })
        }

        // Retry-only variant of buildBody: same everything, but with one
        // extra, blunt correction appended to the system prompt so the
        // second attempt doesn't just repeat the same stale answer.
        fun buildBody(includeHistory: Boolean, correctionNote: String): JSONObject {
            val body = buildBody(includeHistory)
            if (correctionNote.isNotEmpty()) {
                val messages = body.getJSONArray("messages")
                val systemMsg = messages.getJSONObject(0)
                systemMsg.put("content", systemMsg.getString("content") + correctionNote)
            }
            return body
        }

        fun buildRequest(includeHistory: Boolean, correctionNote: String = ""): Request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("content-type", "application/json")
            .post(buildBody(includeHistory, correctionNote).toString().toRequestBody("application/json".toMediaType()))
            .build()

        val httpClient = if (needsSearch) searchClient else client
        val staleRetryNote = "\n\nIMPORTANT CORRECTION: your last attempt at this answer leaned on old " +
            "training data instead of an actual live search - it didn't mention anything from the last " +
            "year. Search again right now and answer only with what you actually find dated recently. If " +
            "you genuinely can't find anything current, say that plainly instead of falling back to what " +
            "you already \"know\"."

        // Attempt 1: the real request, full history + context.
        val firstAttempt = executeChat(httpClient, buildRequest(includeHistory = true))
        if (firstAttempt != null && !(needsSearch && looksStale(firstAttempt))) return firstAttempt

        // Attempt 1b: only fires when attempt 1 actually came back but
        // looked stale (see looksStale) - a genuine network/API failure
        // (firstAttempt == null) skips straight to attempt 2 instead, since
        // the corrective note wouldn't apply to that failure mode.
        if (needsSearch && firstAttempt != null) {
            Log.w("Brosco", "Search answer looked stale (old year, nothing recent) - retrying once")
            executeChat(httpClient, buildRequest(includeHistory = true, correctionNote = staleRetryNote))
                ?.let { retryAnswer -> if (!looksStale(retryAnswer)) return retryAnswer }
        }

        // Attempt 2: same model, history dropped. Covers the "big message"
        // failure mode - a long conversation history plus a long query can
        // push the combined prompt past the model's context limit, and
        // Groq answers with an error body (no "choices") rather than a
        // completion. Previously that error body was the end of the line:
        // it fell straight through to "I didn't get a response back." with
        // no retry at all.
        if (history.isNotEmpty()) {
            executeChat(httpClient, buildRequest(includeHistory = false))?.let { return it }
        }

        // Attempt 3: search queries only - groq/compound (the model that
        // actually browses the web) is slower and more failure-prone than
        // plain chat, so give up on live search and answer from what the
        // model already knows rather than surfacing a dead end.
        if (needsSearch) {
            try {
                return askFallbackNoSearch(context, query)
            } catch (e: Exception) {
                Log.w("Brosco", "askFallbackNoSearch also failed: ${e.message}")
            }
        }

        return "I couldn't reach the server just now - mind trying that again?"
    }

    /**
     * Runs one chat-completion call and returns the reply text, or null if
     * the call failed outright (network/timeout exception) or Groq
     * responded without a usable "choices" array - a rate limit, an
     * over-length request, or any other API-side error all look like this.
     * Callers retry/fall back on null instead of surfacing a dead-end
     * message straight away.
     */
    private fun executeChat(httpClient: OkHttpClient, request: Request): String? {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: "{}"
                val json = JSONObject(bodyStr)

                if (!response.isSuccessful) {
                    Log.w("Brosco", "Groq API HTTP ${response.code}: ${bodyStr.take(500)}")
                    return null
                }

                val choices = json.optJSONArray("choices")
                if (choices == null) {
                    Log.w("Brosco", "Groq API 200 with no choices: ${bodyStr.take(500)}")
                    return null
                }
                if (choices.length() == 0) return "I couldn't find an answer to that."

                val message = choices.getJSONObject(0).optJSONObject("message")
                val content = message?.optString("content")
                if (content.isNullOrBlank()) null else content
            }
        } catch (e: Exception) {
            Log.w("Brosco", "Groq API call failed: ${e.message}")
            null
        }
    }

    private fun askFallbackNoSearch(context: Context, query: String): String {
        val body = JSONObject().apply {
            put("model", CHAT_MODEL)
            put("max_tokens", 500)
            put("include_reasoning", false)
            put("reasoning_effort", "low")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", currentDateContext() + "\n\nYou are Brosco, Shrey's personal " +
                        "assistant. Web search just failed, so answer from what you know, and briefly " +
                        "mention you weren't able to pull live results this time rather than pretending " +
                        "you searched. Since you're answering from memory, be upfront if the answer is " +
                        "the kind of thing that changes over time (prices, current events, who holds a " +
                        "role) rather than stating a stale fact with full confidence.")
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
        return executeChat(client, request) ?: "I couldn't find an answer to that."
    }

    /**
     * Sends up to 5 still images (base64-encoded JPEG, no data: prefix) to
     * Groq's vision model along with a text prompt, and returns the reply.
     * Used by VideoFrameAnalyzer for "video analysis" (really: analysis of
     * a handful of frames pulled out of the video - see that file) and can
     * also be reused for plain photo questions later.
     */
    fun analyzeImages(images: List<String>, prompt: String): String {
        if (images.isEmpty()) return "I didn't get any usable frames to look at."

        val capped = images.take(5) // Groq's hard limit per request

        val contentArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            capped.forEach { base64Jpeg ->
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Jpeg")
                    })
                })
            }
        }

        val body = JSONObject().apply {
            put("model", VISION_MODEL)
            put("max_tokens", 700)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are Brosco, Shrey's assistant. You've been given a small " +
                        "number of still frames sampled from a video, in chronological order - not the " +
                        "full video, not the audio. Describe what's actually visible: setting, people, " +
                        "objects, on-screen text, and how the frames change from one to the next. If the " +
                        "frames don't give you enough to answer confidently, say what you can tell and " +
                        "what you can't - don't guess at things like spoken dialogue or audio.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArray)
                })
            })
        }

        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return executeChat(searchClient, request)
            ?: "I couldn't analyze that video just now - mind trying again?"
    }

    /**
     * Overnight file-fix mode: Shrey shares a text/code file before bed,
     * FileFixWorker calls this once (see that file), and we ask the model
     * to actually read and repair it rather than just chat about it.
     * Returns raw model output in the format:
     *   <plain-language summary of what was changed>
     *   ---FIXED FILE---
     *   <complete corrected file content>
     * FileFixStore splits on that marker - see FileFixWorker.
     */
    fun fixFile(fileName: String, content: String): String {
        // Rough token budget: give it room to reproduce the whole file back
        // plus a summary, scaled to how much was sent in, capped so one
        // huge file can't hang the request indefinitely.
        val approxInputTokens = content.length / 4
        val maxTokens = (approxInputTokens * 2 + 500).coerceIn(800, 6000)

        val body = JSONObject().apply {
            put("model", CHAT_MODEL)
            put("max_tokens", maxTokens)
            put("include_reasoning", false)
            put("reasoning_effort", "medium")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", currentDateContext() + "\n\nYou are Brosco, fixing a file Shrey " +
                        "handed you. The " +
                        "file is named \"$fileName\". Read it carefully and fix real problems: bugs, " +
                        "broken logic, typos, syntax errors, obvious security issues, anything that " +
                        "would actually fail or misbehave. Don't rewrite working code just for style, " +
                        "don't add features he didn't ask for, and don't change behavior that looks " +
                        "intentional even if it's not how you'd have written it. If genuinely nothing " +
                        "is broken, say so rather than inventing changes to justify the pass. " +
                        "Reply in EXACTLY this format and nothing else: first, a short plain-language " +
                        "list of what you changed and why (or one line saying nothing needed fixing); " +
                        "then a line containing exactly ---FIXED FILE--- and nothing else on it; then " +
                        "the complete corrected file content, in full, with no surrounding commentary, " +
                        "markdown fences, or explanation after it.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
        }

        val request = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // Reuse the longer-timeout client - fixing a whole file back-and-
        // forth with the model is closer in shape to a search call than a
        // quick chat reply.
        return executeChat(searchClient, request)
            ?: throw java.io.IOException("Groq API call failed while fixing $fileName")
    }
}
