
package com.nvcs3000.scanner

import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.view.animation.DecelerateInterpolator

class LowAmbienceController(
    private val getPlayer: () -> MediaPlayer?,
    private val onModeChanged: ((Boolean) -> Unit)? = null
) {
    companion object {
        const val NORMAL_VOLUME = 0.4f
        const val LOW_VOLUME = 0.06f
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
        val player = getPlayer() ?: return

        val start = if (enabled) NORMAL_VOLUME else LOW_VOLUME

        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val volume = animation.animatedValue as Float
                try {
                    player.setVolume(volume, volume)
                } catch (_: IllegalStateException) {
                    // The player may have been released.
                }
            }
            start()
        }
    }

    fun stopAndReset() {
        animator?.cancel()
        animator = null
        enabled = false
        try {
            getPlayer()?.setVolume(NORMAL_VOLUME, NORMAL_VOLUME)
        } catch (_: Exception) {
        }
    }
}
