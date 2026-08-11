package com.brosco.assistant

import android.app.Application
import android.os.Looper
import android.util.Log

/**
 * Last line of defense against "This service is malfunctioning."
 *
 * CommandProcessor, the background listener, and the accessibility ticker
 * all already wrap their own bodies in try/catch (see the comments in
 * those files) - but those only cover the paths someone thought to guard.
 * Anything outside them (a bad frame in GalaxyBackgroundView's onDraw, a
 * stray click listener, a future feature that forgets to wrap itself)
 * still throws straight to Android's default uncaught-exception handler,
 * which kills the ENTIRE app process - on any thread, not just the main
 * one. Since WhatsAppAccessibilityService lives in that same process,
 * Android sees the crash, force-disables the accessibility service, and
 * shows "This service is malfunctioning" until it's manually toggled off
 * and back on.
 *
 * Two different threads need two different fixes:
 *  - Main thread: an uncaught exception unwinds Looper.loop() itself, so
 *    the thread's run() method returns and the main thread quietly dies -
 *    even if we "swallow" the exception, this alone would leave the app
 *    a lifeless zombie process (nothing left to process messages, so the
 *    ticker and every Handler.post/postDelayed callback stop firing too).
 *    The fix is to call Looper.loop() again from inside the handler: since
 *    the Looper itself is untouched, this resumes the exact same message
 *    queue right where it left off, skipping only the one message whose
 *    handler threw.
 *  - Any other thread (coroutines, the ticker's background work, etc.):
 *    simply not re-throwing is enough - that one thread ends, but nothing
 *    else depends on it staying alive, and critically the process itself
 *    is never torn down.
 */
class BrocoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val mainThread = Looper.getMainLooper().thread

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("Brosco", "UNCAUGHT on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // logging itself failed - fall through, still don't let this kill the process
            }

            if (thread === mainThread) {
                // Keep resuming the main Looper forever - each restart only
                // ever loses the single message that was mid-handling when
                // something threw, not the thread itself.
                while (true) {
                    try {
                        Looper.loop()
                    } catch (inner: Throwable) {
                        Log.e("Brosco", "UNCAUGHT on main (resumed loop): ${inner.message}", inner)
                        continue
                    }
                    break
                }
            }
            // Non-main threads: just don't rethrow / don't call the
            // platform default handler. That thread ends; the process,
            // the accessibility service, and the main thread do not.
        }
    }
}
