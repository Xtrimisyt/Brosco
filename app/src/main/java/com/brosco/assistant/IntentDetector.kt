package com.brosco.assistant

enum class IntentType {
    OPEN_APP,
    CALL,
    MESSAGE,
    WHATSAPP,
    SEARCH,
    ORDER_FOOD,
    PLAY_MUSIC,

    GO_BACK,
    GO_HOME,
    SCROLL_DOWN,
    SCROLL_UP,
    CLICK,
    CLICK_ID,
    TYPE_TEXT,
    LONG_PRESS,
    SWIPE,

    // App-automation flows (Section 3)
    ZOMATO_ORDER,
    DOMINOS_ORDER,
    ADD_ITEM,
    YOUTUBE_SEARCH,
    YOUTUBE_PAUSE,
    YOUTUBE_NEXT,
    SPOTIFY_SEARCH,
    SPOTIFY_PAUSE,
    SPOTIFY_NEXT,
    JIOSAAVN_SEARCH,
    JIOSAAVN_PAUSE,
    JIOSAAVN_NEXT,
    JIOSAAVN_MY_PLAYLIST,
    INSTAGRAM_SCROLL_FEED,
    INSTAGRAM_OPEN_REELS,
    INSTAGRAM_LIKE,
    INSTAGRAM_FOLLOW,

    // Section 5: chained commands, e.g. "open zomato then search burger then add to cart"
    MULTI_STEP,

    // Section 4-lite: AI-resolved clicks for ordinals/pronouns ("the first
    // video", "the add button next to that")
    SMART_CLICK,

    // Section 6: WhatsApp smart replies - read the latest incoming message,
    // offer 2-3 AI-generated reply options, and let a follow-up command pick
    // one ("reply with the second one") to actually send.
    WHATSAPP_SMART_REPLY,
    SELECT_SMART_REPLY,

    // Section 7: screen-aware Q&A - "what does this error say", "summarize
    // this article" - answered from a live read of the current screen.
    SCREEN_QA,

    // Memory
    CLEAR_MEMORY,

    // "work brosco goodnight" mode - gather markets/news overnight via
    // WorkManager and read it back as a briefing later.
    START_OVERNIGHT_WORK,
    STOP_OVERNIGHT_WORK,
    OVERNIGHT_BRIEFING,

    // Frame-sampled video analysis (see VideoFrameAnalyzer) - reached via
    // Android's share sheet, not spoken text, so no phrase detection here;
    // kept in the enum for consistency with how CommandProcessor dispatches.
    ANALYZE_VIDEO,

    UNKNOWN
}

data class DetectedIntent(
    val type: IntentType,
    val target: String = "",
    val extra: String = ""
)

object IntentDetector {

    // Words that split one spoken sentence into several commands to run in order.
    private val chainSeparators = listOf(
        " then ", " and then ", " -> ", " after that ", " followed by "
    )

    // Section 6: "reply with the second one" / "send the first option" /
    // "use option 2" - selects one of the options SmartReplyStore is
    // currently holding. Captures the ordinal word/digit in group 1.
    private val smartReplySelectRegex = Regex(
        "(?:reply with|send|use)\\s+(?:the\\s+)?(?:option\\s+)?(first|second|third|1st|2nd|3rd|one|two|three|1|2|3)(?:\\s+(?:one|option|reply))?"
    )

    private fun parseOrdinalWord(word: String): Int? = when (word.trim().lowercase()) {
        "first", "1st", "one", "1" -> 1
        "second", "2nd", "two", "2" -> 2
        "third", "3rd", "three", "3" -> 3
        else -> null
    }

    // Phrases that ask Brosco to read the latest WhatsApp message and offer
    // reply suggestions, rather than select one that's already been offered.
    private val smartReplyTriggers = listOf(
        "read my whatsapp", "read my messages", "read my message", "check whatsapp",
        "check my whatsapp", "any new messages", "new whatsapp message", "smart reply",
        "smart replies", "suggest a reply", "suggest replies", "what should i reply",
        "how should i reply", "help me reply", "read this message", "read the message",
        "what did they say", "what did they text"
    )

    // Phrases that ask Brosco to read/explain/summarize whatever's on screen
    // right now, without a manual copy-paste.
    private val screenQaTriggers = listOf(
        "what does this say", "what does this error say", "what does that say",
        "summarize this", "summarise this", "read this to me", "read that to me",
        "translate this", "what is this error", "explain this screen",
        "what's on my screen", "whats on my screen", "read my screen",
        "what does this mean", "explain this error"
    )

    fun detect(text: String): DetectedIntent {

        val input = text.lowercase().trim()

        // ---- Section 5: multi-step chains take priority over everything else ----
        for (sep in chainSeparators) {
            if (input.contains(sep)) {
                return DetectedIntent(IntentType.MULTI_STEP, input)
            }
        }

        // MEMORY WIPE
        if (input.contains("forget everything") || input.contains("forget our conversation") ||
            input.contains("clear your memory") || input.contains("clear memory") ||
            input.contains("wipe your memory")
        ) {
            return DetectedIntent(IntentType.CLEAR_MEMORY)
        }

        // ---- Overnight work mode ("work brosco goodnight") ----
        // Checked with the full input (not word-boundary-sensitive) since
        // this is meant to catch loose phrasing - "work brosco goodnight",
        // "goodnight, keep working", "gather news while I sleep", etc.
        val overnightStopPhrases = listOf(
            "stop working overnight", "cancel overnight", "stop the overnight",
            "cancel the overnight", "stop overnight mode"
        )
        if (overnightStopPhrases.any { input.contains(it) }) {
            return DetectedIntent(IntentType.STOP_OVERNIGHT_WORK)
        }

        val overnightBriefingPhrases = listOf(
            "my briefing", "overnight briefing", "morning briefing",
            "what did you find while i was asleep", "what did you find overnight",
            "what happened while i was asleep", "what happened overnight",
            "read me the digest", "give me the digest", "overnight digest",
            "what did you gather"
        )
        if (overnightBriefingPhrases.any { input.contains(it) }) {
            return DetectedIntent(IntentType.OVERNIGHT_BRIEFING)
        }

        val mentionsWork = input.contains("work")
        val mentionsGoodnight = input.contains("goodnight") || input.contains("good night")
        val overnightStartPhrases = listOf(
            "work overnight", "work through the night", "work while i sleep",
            "work while i'm asleep", "work while im asleep", "gather news while i sleep",
            "analyze stocks at night", "analyse stocks at night", "start overnight mode",
            "overnight mode"
        )
        if ((mentionsWork && mentionsGoodnight) || overnightStartPhrases.any { input.contains(it) }) {
            return DetectedIntent(IntentType.START_OVERNIGHT_WORK)
        }

        // ---- Section 6: WhatsApp smart replies ----
        // Selecting an already-offered option is checked first since its
        // phrasing ("reply with...", "send...") could otherwise get pulled
        // into the generic message/send handling further down.
        smartReplySelectRegex.find(input)?.let { match ->
            val ordinal = parseOrdinalWord(match.groupValues[1])
            if (ordinal != null) return DetectedIntent(IntentType.SELECT_SMART_REPLY, ordinal.toString())
        }
        if (smartReplyTriggers.any { input.contains(it) }) {
            return DetectedIntent(IntentType.WHATSAPP_SMART_REPLY)
        }

        // ---- Section 7: screen-aware Q&A ----
        if (screenQaTriggers.any { input.contains(it) }) {
            return DetectedIntent(IntentType.SCREEN_QA, input)
        }

        // ---- Section 3: named app-automation flows ----
        detectAppFlow(input)?.let { return it }

        // SMART CLICK ("click the first video", "add the one next to this",
        // "tap that") - plain text matching can't resolve ordinals/pronouns,
        // so this hands the screen + phrase to the AI resolver instead.
        val referenceWords = listOf(
            " this", " that", " it", " the one", "first ", "second ", "third ",
            "last ", "next one", "top ", "bottom "
        )
        val actionVerbs = listOf("click ", "tap ", "press ", "add ", "select ", "choose ", "open ")
        val matchedVerb = actionVerbs.firstOrNull { input.startsWith(it) }
        if (matchedVerb != null && referenceWords.any { input.contains(it) }) {
            return DetectedIntent(IntentType.SMART_CLICK, input.removePrefix(matchedVerb).trim())
        }

        // ADD ITEM ("add margherita pizza", "add 2 medium fries") - clicks the
        // nearest "Add" button next to a matching label on screen.
        if (input.startsWith("add ")) {
            return DetectedIntent(IntentType.ADD_ITEM, input.removePrefix("add ").trim())
        }

        // OPEN APP
        val openWords = listOf("open", "launch", "start")
        openWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(IntentType.OPEN_APP, input.removePrefix("$word ").trim())
            }
        }

        // CALL
        val callWords = listOf("call", "phone", "ring")
        callWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(IntentType.CALL, input.removePrefix("$word ").trim())
            }
        }

        // TYPE TEXT ("type hello world", "type into search bar pizza")
        val typeWords = listOf("type ", "enter text ")
        typeWords.forEach { word ->
            if (input.startsWith(word)) {
                return DetectedIntent(IntentType.TYPE_TEXT, input.removePrefix(word).trim())
            }
        }

        // LONG PRESS
        if (input.startsWith("long press ") || input.startsWith("hold ")) {
            val target = input.removePrefix("long press ").removePrefix("hold ").trim()
            return DetectedIntent(IntentType.LONG_PRESS, target)
        }

        // SWIPE
        if (input.startsWith("swipe ")) {
            val direction = input.removePrefix("swipe ").trim()
            return DetectedIntent(IntentType.SWIPE, direction)
        }

        // SEARCH / WEB / DEEP RESEARCH - phrased loosely ("do a deep research
        // on X", "search the web for X", "look that up", "google it") this
        // used to fall through to the generic AI chat with no signal that a
        // real lookup was wanted, which is why it used to just say it
        // couldn't browse the internet. Now it's always tagged explicitly so
        // CommandProcessor can force the search-capable model on.
        val researchPhrases = listOf(
            "deep research", "do research", "do a research", "research on",
            "research about", "research into", "search the web", "browse the web",
            "look this up", "look that up", "google that", "google it"
        )
        if (researchPhrases.any { input.contains(it) } || input.startsWith("research ")) {
            val cleaned = input
                .replace(Regex("deep research(ing)?( on| about| into| for)?"), "")
                .replace(Regex("^(do( a)? )?research( on| about| into| for)?"), "")
                .replace(Regex("search the web( for)?"), "")
                .replace(Regex("browse the web( for)?"), "")
                .replace(Regex("look (this|that) up( for)?"), "")
                .replace(Regex("google (that|it)"), "")
                .trim()
            return DetectedIntent(IntentType.SEARCH, cleaned.ifBlank { input })
        }

        // SEARCH (generic, kept for fallback/plain web search behaviour)
        val searchWords = listOf("search", "find", "look up")
        searchWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(IntentType.SEARCH, input.removePrefix("$word ").trim())
            }
        }

        // FOOD (generic Zomato text search, kept for anything the flow parser doesn't catch)
        if (input.contains("zomato") || input.contains("dominos") || input.contains("domino's") ||
            input.startsWith("order ")
        ) {
            return DetectedIntent(IntentType.ORDER_FOOD, input)
        }

        // MUSIC
        if (input.startsWith("play ")) {
            return DetectedIntent(IntentType.PLAY_MUSIC, input.removePrefix("play ").trim())
        }

        // BACK
        if (input == "go back" || input == "back" || input.contains("go back") || input.contains("back please")) {
            return DetectedIntent(IntentType.GO_BACK)
        }

        // HOME
        if (input == "go home" || input == "home" || input.contains("go home")) {
            return DetectedIntent(IntentType.GO_HOME)
        }

        // SCROLL
        if (input.contains("scroll down")) return DetectedIntent(IntentType.SCROLL_DOWN)
        if (input.contains("scroll up")) return DetectedIntent(IntentType.SCROLL_UP)

        // CLICK BY VIEW ID (e.g. "click id com.whatsapp:id/send")
        if (input.startsWith("click id ") || input.startsWith("tap id ")) {
            val id = input.removePrefix("click id ").removePrefix("tap id ").trim()
            return DetectedIntent(IntentType.CLICK_ID, id)
        }

        // CLICK BY TEXT
        val clickWords = listOf("click", "tap", "press")
        clickWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(IntentType.CLICK, input.removePrefix("$word ").trim())
            }
        }

        return DetectedIntent(IntentType.UNKNOWN)
    }

    /**
     * Section 3: recognise natural phrasing for the five supported app flows
     * so CommandProcessor can build a step-by-step AutomationStep task for them.
     */
    private fun detectAppFlow(input: String): DetectedIntent? {

        // --- Zomato ---
        if (Regex("(order|get|search)\\s+(.+?)\\s+(on|from|via)\\s+zomato").containsMatchIn(input)) {
            val match = Regex("(order|get|search)\\s+(.+?)\\s+(on|from|via)\\s+zomato").find(input)!!
            return DetectedIntent(IntentType.ZOMATO_ORDER, match.groupValues[2].trim())
        }
        if (input.contains("zomato")) {
            val food = input.replace("zomato", "").replace(Regex("\\b(order|search|get|on|from|via|open)\\b"), "").trim()
            return DetectedIntent(IntentType.ZOMATO_ORDER, food)
        }

        // --- Domino's ---
        if (input.contains("domino")) {
            val pizza = input
                .replace(Regex("domino'?s"), "")
                .replace(Regex("\\b(order|search|get|on|from|via|open|pizza)\\b"), "")
                .trim()
                .ifBlank { "pizza" }
            return DetectedIntent(IntentType.DOMINOS_ORDER, pizza)
        }

        // --- YouTube ---
        if (Regex("(play|search|find)\\s+(.+?)\\s+on\\s+youtube").containsMatchIn(input)) {
            val match = Regex("(play|search|find)\\s+(.+?)\\s+on\\s+youtube").find(input)!!
            return DetectedIntent(IntentType.YOUTUBE_SEARCH, match.groupValues[2].trim())
        }
        if (input.contains("youtube")) {
            if (input.contains("pause")) return DetectedIntent(IntentType.YOUTUBE_PAUSE)
            if (input.contains("next")) return DetectedIntent(IntentType.YOUTUBE_NEXT)
            val query = input.replace("youtube", "").replace(Regex("\\b(play|search|find|video|videos|on|open)\\b"), "").trim()
            if (query.isNotBlank()) return DetectedIntent(IntentType.YOUTUBE_SEARCH, query)
        }

        // --- Spotify ---
        if (Regex("(play|search|find)\\s+(.+?)\\s+on\\s+spotify").containsMatchIn(input)) {
            val match = Regex("(play|search|find)\\s+(.+?)\\s+on\\s+spotify").find(input)!!
            return DetectedIntent(IntentType.SPOTIFY_SEARCH, match.groupValues[2].trim())
        }
        if (input.contains("spotify")) {
            if (input.contains("pause")) return DetectedIntent(IntentType.SPOTIFY_PAUSE)
            if (input.contains("next") || input.contains("skip")) return DetectedIntent(IntentType.SPOTIFY_NEXT)
            val query = input.replace("spotify", "").replace(Regex("\\b(play|search|find|song|on|open)\\b"), "").trim()
            if (query.isNotBlank()) return DetectedIntent(IntentType.SPOTIFY_SEARCH, query)
        }

        // --- JioSaavn: "my playlist" - the playlist Shrey has marked as his
        // own (e.g. liked/favourited into a playlist called "My Playlist").
        // Checked before the generic "play X on jiosaavn" regex below, since
        // otherwise "play my playlist on jiosaavn" would get parsed as a
        // literal search for the song title "my playlist" instead of
        // opening the actual playlist. Also matches with no app name at all
        // ("play my playlist") since JioSaavn is the only app this maps to.
        if (input.contains("my playlist")) {
            return DetectedIntent(IntentType.JIOSAAVN_MY_PLAYLIST)
        }

        // --- JioSaavn ---
        if (Regex("(play|search|find)\\s+(.+?)\\s+on\\s+(jiosaavn|saavn)").containsMatchIn(input)) {
            val match = Regex("(play|search|find)\\s+(.+?)\\s+on\\s+(jiosaavn|saavn)").find(input)!!
            return DetectedIntent(IntentType.JIOSAAVN_SEARCH, match.groupValues[2].trim())
        }
        if (input.contains("jiosaavn") || input.contains("saavn")) {
            if (input.contains("pause")) return DetectedIntent(IntentType.JIOSAAVN_PAUSE)
            if (input.contains("next") || input.contains("skip")) return DetectedIntent(IntentType.JIOSAAVN_NEXT)
            val query = input.replace(Regex("jiosaavn|saavn"), "")
                .replace(Regex("\\b(play|search|find|song|on|open)\\b"), "").trim()
            if (query.isNotBlank()) return DetectedIntent(IntentType.JIOSAAVN_SEARCH, query)
        }

        // --- Instagram ---
        if (input.contains("instagram") || input.contains("reels")) {
            if (input.contains("follow")) return DetectedIntent(IntentType.INSTAGRAM_FOLLOW)
            if (input.contains("like")) return DetectedIntent(IntentType.INSTAGRAM_LIKE)
            if (input.contains("reel")) return DetectedIntent(IntentType.INSTAGRAM_OPEN_REELS)
            if (input.contains("scroll") || input.contains("feed")) return DetectedIntent(IntentType.INSTAGRAM_SCROLL_FEED)
        }

        return null
    }
}
