package com.brosco.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        // Existing WhatsApp support
        var pendingMessage: String? = null

        // Universal actions
        var pendingClickText: String? = null
        var pendingClickId: String? = null
        var pendingTypeText: String? = null

        var pendingScrollForward = false
        var pendingScrollBackward = false

        var pendingBack = false
        var pendingHome = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            // Step 3: Existing WhatsApp automation handling
            if (pendingMessage != null) {
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
            }

            // Step 4: Universal action dispatcher
            val root = rootInActiveWindow ?: return

            when {
                pendingClickText != null -> {
                    clickByText(root, pendingClickText!!)
                    pendingClickText = null
                }

                pendingClickId != null -> {
                    clickById(root, pendingClickId!!)
                    pendingClickId = null
                }

                pendingScrollForward -> {
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    pendingScrollForward = false
                }

                pendingScrollBackward -> {
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    pendingScrollBackward = false
                }

                pendingBack -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    pendingBack = false
                }

                pendingHome -> {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    pendingHome = false
                }
            }
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

    // Step 5: Helper functions
    private fun clickByText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false

        if (node.text?.toString()?.equals(text, true) == true ||
            node.contentDescription?.toString()?.equals(text, true) == true) {

            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }

            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        for (i in 0 until node.childCount) {
            if (clickByText(node.getChild(i), text))
                return true
        }

        return false
    }

    private fun clickById(node: AccessibilityNodeInfo?, id: String): Boolean {
        if (node == null) return false

        return try {
            val list = node.findAccessibilityNodeInfosByViewId(id)

            if (list.isNotEmpty()) {
                list[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onInterrupt() {}
}
