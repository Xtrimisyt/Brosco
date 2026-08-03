package com.brosco.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        var pendingMessage: String? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != "com.whatsapp") return
        if (pendingMessage == null) return

        // Give WhatsApp a moment to fully render the chat screen and pre-filled text
        Handler(Looper.getMainLooper()).postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val sendButton = findSendButton(root)
            if (sendButton != null) {
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pendingMessage = null
            }
        }, 900)
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (byId.isNotEmpty()) return byId[0]

        // Fallback: search by content description, in case WhatsApp changes the resource id
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.contentDescription?.toString()?.equals("Send", ignoreCase = true) == true) {
                return child
            }
            val found = findSendButton(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}
}
