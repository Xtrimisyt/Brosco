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
    INSTAGRAM_SCROLL_FEED,
    INSTAGRAM_OPEN_REELS,
    INSTAGRAM_LIKE,
    INSTAGRAM_FOLLOW,

    // Section 5: chained commands, e.g. "open zomato then search burger then add to cart"
    MULTI_STEP,

    // Section 4-lite: AI-resolved clicks for ordinals/pronouns ("the first
    // video", "the add button next to that")
    SMART_CLICK,

    // Memory
    CLEAR_MEMORY,

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
            val food = input.replace("zomato", "").replace(Regex("order|search|get|on|from|via|open"), "").trim()
            return DetectedIntent(IntentType.ZOMATO_ORDER, food)
        }

        // --- Domino's ---
        if (input.contains("domino")) {
            val pizza = input
                .replace(Regex("domino'?s"), "")
                .replace(Regex("order|search|get|on|from|via|open|pizza"), "")
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
            val query = input.replace("youtube", "").replace(Regex("play|search|find|video|videos|on"), "").trim()
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
            val query = input.replace("spotify", "").replace(Regex("play|search|find|song|on"), "").trim()
            if (query.isNotBlank()) return DetectedIntent(IntentType.SPOTIFY_SEARCH, query)
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
