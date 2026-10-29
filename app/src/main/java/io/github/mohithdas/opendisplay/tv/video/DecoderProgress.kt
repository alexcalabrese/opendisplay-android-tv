package io.github.mohithdas.opendisplay.tv.video

internal class DecoderProgress {
    var pendingFrames = 0
        private set
    private var pendingSinceMs = 0L
    private var lastOutputMs = 0L
    private var lastInputMs = 0L

    fun queued(nowMs: Long) {
        lastInputMs = nowMs
        if (pendingFrames++ == 0) pendingSinceMs = nowMs
    }

    fun rendered(nowMs: Long) {
        pendingFrames = (pendingFrames - 1).coerceAtLeast(0)
        lastOutputMs = nowMs
    }

    fun isStalled(nowMs: Long): Boolean =
        pendingFrames > 0 && nowMs - lastInputMs < 1000 &&
            nowMs - maxOf(pendingSinceMs, lastOutputMs) > 1500

    fun reset() {
        pendingFrames = 0
        pendingSinceMs = 0
        lastOutputMs = 0
        lastInputMs = 0
    }
}
