package com.brosco.assistant

enum class IntentType {
    OPEN_APP,
    CALL,
    MESSAGE,
    WHATSAPP,
    SEARCH,
    ORDER_FOOD,
    PLAY_MUSIC,
    UNKNOWN
}

data class DetectedIntent(
    val type: IntentType,
    val target: String = "",
    val extra: String = ""
)

object IntentDetector {

    fun detect(text: String): DetectedIntent {

        val input = text.lowercase().trim()

        // OPEN APP
        val openWords = listOf(
            "open",
            "launch",
            "start"
        )

        openWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(
                    IntentType.OPEN_APP,
                    input.removePrefix("$word ").trim()
                )
            }
        }

        // CALL
        val callWords = listOf(
            "call",
            "phone",
            "ring"
        )

        callWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(
                    IntentType.CALL,
                    input.removePrefix("$word ").trim()
                )
            }
        }

        // SEARCH
        val searchWords = listOf(
            "search",
            "find",
            "look up"
        )

        searchWords.forEach { word ->
            if (input.startsWith("$word ")) {
                return DetectedIntent(
                    IntentType.SEARCH,
                    input.removePrefix("$word ").trim()
                )
            }
        }

        // FOOD

        if (input.contains("zomato") ||
            input.contains("dominos") ||
            input.contains("domino's") ||
            input.startsWith("order ")
        ) {

            return DetectedIntent(
                IntentType.ORDER_FOOD,
                input
            )
        }

        // MUSIC

        if (input.startsWith("play ")) {

            return DetectedIntent(
                IntentType.PLAY_MUSIC,
                input.removePrefix("play ").trim()
            )
        }

        return DetectedIntent(IntentType.UNKNOWN)
    }
}
