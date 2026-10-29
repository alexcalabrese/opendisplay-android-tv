package io.github.mohithdas.opendisplay.tv.video

internal enum class DecoderRecoveryAction { NONE, REQUEST_KEYFRAME, RESTART }

internal class DecoderProgress {
    var pendingFrames = 0
        private set
    private var pendingSinceMs = 0L
    private var lastOutputMs = 0L
    private var lastInputMs = 0L
    private var keyframeRequestedAtMs: Long? = null

    fun queued(nowMs: Long) {
        lastInputMs = nowMs
        if (pendingFrames++ == 0) pendingSinceMs = nowMs
    }

    fun rendered(nowMs: Long) {
        keyframeRequestedAtMs = null
        pendingFrames = (pendingFrames - 1).coerceAtLeast(0)
        lastOutputMs = nowMs
    }

    fun isStalled(nowMs: Long): Boolean =
        pendingFrames > 0 && nowMs - lastInputMs < 1000 &&
            nowMs - maxOf(pendingSinceMs, lastOutputMs) > 1500

    fun recoveryAction(nowMs: Long): DecoderRecoveryAction {
        if (!isStalled(nowMs)) return DecoderRecoveryAction.NONE
        val requestedAt = keyframeRequestedAtMs
        if (requestedAt == null) {
            keyframeRequestedAtMs = nowMs
            return DecoderRecoveryAction.REQUEST_KEYFRAME
        }
        return if (pendingFrames >= 8 && nowMs - requestedAt > 1500) {
            DecoderRecoveryAction.RESTART
        } else DecoderRecoveryAction.NONE
    }

    fun reset() {
        keyframeRequestedAtMs = null
        pendingFrames = 0
        pendingSinceMs = 0
        lastOutputMs = 0
        lastInputMs = 0
    }
}
