package com.nvcs3000.scanner

import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.view.animation.DecelerateInterpolator

/**
 * Small integration helper for the Python reference's Low Ambience Mode.
 *
 * Pass the two ambience MediaPlayers already created by MainActivity.
 * This helper does not create players or own/release them.
 */
class LowAmbienceController(
    private val ambienceA: MediaPlayer?,
    private val ambienceB: MediaPlayer?,
    private val onModeChanged: ((enabled: Boolean) -> Unit)? = null
) {
    companion object {
        const val NORMAL_VOLUME = 1.0f
        const val LOW_VOLUME = 0.15f
        const val DRAIN_MULTIPLIER = 5.0
        const val FADE_DURATION_MS = 450L
    }

    var enabled: Boolean = false
        private set

    private var animator: ValueAnimator? = null

    fun toggle(): Boolean {
        setEnabled(!enabled)
        return enabled
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        fadeTo(if (enabled) LOW_VOLUME else NORMAL_VOLUME)
        onModeChanged?.invoke(enabled)
    }

    private fun fadeTo(target: Float) {
        animator?.cancel()
        val players = listOfNotNull(ambienceA, ambienceB)
        if (players.isEmpty()) return

        // The helper tracks only the two target states, not MediaPlayer's internal volume.
        val start = if (target == LOW_VOLUME) NORMAL_VOLUME else LOW_VOLUME

        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val volume = animation.animatedValue as Float
                players.forEach { player ->
                    try {
                        player.setVolume(volume, volume)
                    } catch (_: IllegalStateException) {
                        // Player may have been released during Activity shutdown.
                    }
                }
            }
            start()
        }
    }

    fun stopAndReset() {
        animator?.cancel()
        animator = null
        enabled = false
        listOfNotNull(ambienceA, ambienceB).forEach { player ->
            try { player.setVolume(NORMAL_VOLUME, NORMAL_VOLUME) } catch (_: Exception) {}
        }
    }

}
