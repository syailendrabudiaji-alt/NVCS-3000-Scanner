NVCS 3000 â€” MainActivity.kt PATCH
=================================

IMPORTANT
---------
This is a PATCH GUIDE for your existing MainActivity.kt.
It is NOT the complete MainActivity.kt file. Do not replace the whole file
with this document.

FILE:
app/src/main/java/com/nvcs3000/scanner/MainActivity.kt

GOAL
----
Fix the LowAmbienceController constructor mismatch and add a smooth 450 ms
static fade-out. Keep your existing camera, detector, and UI code.

CHANGE 1 â€” ADD TWO IMPORTS
--------------------------
Add these with the other imports at the top:

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

Do not add duplicates if either import already exists.

CHANGE 2 â€” ADD THE STATIC ANIMATOR FIELD
----------------------------------------
Find:

private var ambience: MediaPlayer? = null
private var ambienceFailed = false
private var lowAmbienceButton: Button? = null

Change to:

private var ambience: MediaPlayer? = null
private var ambienceFailed = false
private var lowAmbienceButton: Button? = null
private var staticAnimator: ValueAnimator? = null

CHANGE 3 â€” FIX THE CONTROLLER CONSTRUCTOR
-----------------------------------------
Inside LowAmbienceController(...), find:

getPlayer = { ambience },

Replace ONLY that line with:

getPlayers = { listOf(ambience) },

The LowAmbienceController.kt file expects getPlayers and a list.

CHANGE 4 â€” REPLACE hideStatic
-----------------------------
Find:

private val hideStatic = Runnable {
    if (::staticView.isInitialized) staticView.visibility = View.GONE
}

Replace with:

private val hideStatic = Runnable {
    if (::staticView.isInitialized) {
        staticView.alpha = 1f
        staticView.visibility = View.GONE
    }
    staticAnimator = null
}

CHANGE 5 â€” UPDATE startAmbience()
---------------------------------
Inside startAmbience(), find this whole block:

ambience?.setVolume(
    if (lowAmbienceController.enabled) LowAmbienceController.LOW_VOLUME
    else LowAmbienceController.NORMAL_VOLUME,
    if (lowAmbienceController.enabled) LowAmbienceController.LOW_VOLUME
    else LowAmbienceController.NORMAL_VOLUME
)

Replace it with:

lowAmbienceController.syncPlayers()

Keep the next line that starts the player:

ambience?.let { if (!it.isPlaying) it.start() }

CHANGE 6 â€” REPLACE THE STATIC DISPLAY BLOCK IN flashStatic()
------------------------------------------------------------
Find:

if (staticBitmap != null) {
    staticView.visibility = View.VISIBLE
    mainHandler.removeCallbacks(hideStatic)
    mainHandler.postDelayed(hideStatic, 220L)
}

Replace with:

if (staticBitmap != null) {
    mainHandler.removeCallbacks(hideStatic)
    staticAnimator?.cancel()
    staticAnimator = null

    staticView.alpha = 1f
    staticView.visibility = View.VISIBLE

    staticAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 450L
        interpolator = DecelerateInterpolator()

        addUpdateListener { animation ->
            if (::staticView.isInitialized) {
                staticView.alpha = animation.animatedValue as Float
            }
        }

        start()
    }

    mainHandler.postDelayed(hideStatic, 460L)
}

CHANGE 7 â€” CLEAN UP THE ANIMATOR IN onDestroy()
------------------------------------------------
In onDestroy(), find:

mainHandler.removeCallbacksAndMessages(null)

Immediately AFTER it, add:

staticAnimator?.cancel()
staticAnimator = null

AFTER EDITING
-------------
1. Save MainActivity.kt.
2. Commit the change to the main branch.
3. Run the GitHub Actions Android build.
4. If the build fails, copy the FIRST error message from the build log.

IMPORTANT NOTE
--------------
These changes have NOT been saved to GitHub by ChatGPT. Apply them manually
to your existing MainActivity.kt.

This patch only addresses the Low Ambience controller integration and static
fade behavior. It does not add all 44 planned modules or battery mechanics.