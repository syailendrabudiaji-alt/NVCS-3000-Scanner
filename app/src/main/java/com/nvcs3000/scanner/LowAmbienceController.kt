package com.nvcs3000.scanner

import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.view.animation.DecelerateInterpolator

class LowAmbienceController(
    private val getPlayers: () -> List<MediaPlayer?>,
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

    private val animators = mutableListOf<ValueAnimator>()

    fun toggle(): Boolean {
        setEnabled(!enabled)
        return enabled
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return

        enabled = value
        fadeAllTo(if (value) LOW_VOLUME else NORMAL_VOLUME)
        onModeChanged?.invoke(value)
    }

    fun syncPlayers() {
        val target = if (enabled) LOW_VOLUME else NORMAL_VOLUME

        getPlayers().filterNotNull().forEach { player ->
            try {
                player.setVolume(target, target)
            } catch (_: Exception) {
            }
        }
    }

    private fun fadeAllTo(target: Float) {
        animators.forEach { it.cancel() }
        animators.clear()

        getPlayers().filterNotNull().forEach { player ->
            try {
                val start = if (enabled) NORMAL_VOLUME else LOW_VOLUME

                val animator = ValueAnimator.ofFloat(start, target).apply {
                    duration = FADE_DURATION_MS
                    interpolator = DecelerateInterpolator()

                    addUpdateListener { animation ->
                        val volume = animation.animatedValue as Float

                        try {
                            player.setVolume(volume, volume)
                        } catch (_: Exception) {
                            cancel()
                        }
                    }
                }

                animators.add(animator)
                animator.start()
            } catch (_: Exception) {
            }
        }
    }

    fun stopAndReset() {
        animators.forEach { it.cancel() }
        animators.clear()
        enabled = false

        getPlayers().filterNotNull().forEach { player ->
            try {
                player.setVolume(NORMAL_VOLUME, NORMAL_VOLUME)
            } catch (_: Exception) {
            }
        }

        onModeChanged?.invoke(false)
    }
}
