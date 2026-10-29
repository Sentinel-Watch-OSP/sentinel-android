package com.samourai.sentinel.ui.views

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.core.ConnectionIndicator

/**
 * Renders the toolbar network dot as a traffic light.
 *
 * - GREEN   solid  : Tor connected AND all collections synced
 * - YELLOW  flashing: connecting / bootstrapping / syncing
 * - RED     solid  : connection or sync failed
 * - NEUTRAL hidden : nothing attempted yet
 */
class ConnectionIndicatorController(private val dot: View) {

    private var pulse: ObjectAnimator? = null
    private var current: ConnectionIndicator? = null

    fun render(state: ConnectionIndicator) {
        if (current == state) return
        current = state

        stopPulse()

        val colorRes = when (state) {
            ConnectionIndicator.GREEN -> R.color.success_green
            ConnectionIndicator.YELLOW -> R.color.warning_yellow
            ConnectionIndicator.RED -> R.color.mpm_red
            ConnectionIndicator.NEUTRAL -> null
        }

        if (colorRes == null) {
            dot.visibility = View.INVISIBLE
            return
        }

        val shape = ContextCompat.getDrawable(dot.context, R.drawable.circle_shape)?.mutate()
        shape?.setTint(ContextCompat.getColor(dot.context, colorRes))
        dot.background = shape
        dot.visibility = View.VISIBLE
        dot.alpha = 1f

        if (state == ConnectionIndicator.YELLOW) {
            startPulse()
        }
    }

    private fun startPulse() {
        pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.15f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        dot.alpha = 1f
    }

    /** Must be called when the host view is destroyed to avoid leaking the animator. */
    fun dispose() {
        stopPulse()
        current = null
    }
}
