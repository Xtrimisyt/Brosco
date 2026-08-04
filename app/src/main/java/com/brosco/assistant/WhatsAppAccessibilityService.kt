package com.brosco.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        var pendingMessage: String? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (event.packageName != "com.whatsapp") return
            if (pendingMessage == null) return

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val root = rootInActiveWindow ?: return@postDelayed
                    val sendButton = findSendButton(root, depth = 0)
                    if (sendButton != null) {
                        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        pendingMessage = null
                    }
                } catch (e: Exception) {
                    Log.e("Brosco", "Send-tap failed: ${e.message}")
                }
            }, 900)
        } catch (e: Exception) {
            Log.e("Brosco", "Accessibility event failed: ${e.message}")
        }
    }

    private fun findSendButton(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        return try {
            val byId = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (byId.isNotEmpty()) return byId[0]

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.contentDescription?.toString()?.equals("Send", ignoreCase = true) == true) {
                    return child
                }
                val found = findSendButton(child, depth + 1)
                if (found != null) return found
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun onInterrupt() {}
}
