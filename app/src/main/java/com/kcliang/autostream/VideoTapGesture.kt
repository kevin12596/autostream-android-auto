package com.kcliang.autostream

/** Pairs nearby taps on the same side of a video without forwarding the first tap early. */
internal class VideoTapGesture(private val timeoutMs: Long) {
    data class Tap(val x: Float, val y: Float, val timeMs: Long, val side: Int)
    data class Decision(val single: Tap? = null, val double: Pair<Tap, Tap>? = null)

    /** Video bounds in viewport fractions, independent of car resolution and display density. */
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun sideAt(x: Float, y: Float): Int {
            if (!listOf(left, top, right, bottom, x, y).all { it.isFinite() }) return 0
            if (right <= left || bottom <= top) return 0
            val rx = (x - left) / (right - left)
            val ry = (y - top) / (bottom - top)
            // Leave the center and the top/bottom controls to the native page.
            if (ry < 0.18f || ry > 0.75f) return 0
            return when (rx) {
                in 0.05f..0.4f -> -1
                in 0.6f..0.95f -> 1
                else -> 0
            }
        }
    }

    var pending: Tap? = null
        private set

    fun register(tap: Tap, slopPx: Float): Decision {
        require(tap.side == -1 || tap.side == 1)
        val first = pending
        if (first != null) {
            val elapsed = tap.timeMs - first.timeMs
            val dx = tap.x - first.x
            val dy = tap.y - first.y
            if (elapsed in 0..timeoutMs && tap.side == first.side &&
                dx * dx + dy * dy <= slopPx * slopPx
            ) {
                pending = null
                return Decision(double = first to tap)
            }
        }
        pending = tap
        return Decision(single = first)
    }

    fun takeSingle(): Tap? = pending.also { pending = null }

    fun cancel() {
        pending = null
    }
}
