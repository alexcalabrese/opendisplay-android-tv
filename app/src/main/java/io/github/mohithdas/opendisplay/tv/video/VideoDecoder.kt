package io.github.mohithdas.opendisplay.tv.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import io.github.mohithdas.opendisplay.tv.net.VideoFrame
import io.github.mohithdas.opendisplay.tv.util.Log
import java.nio.ByteBuffer

/**
 * Hardware H.264 decode straight to a [Surface] — no CPU YUV conversion, no
 * custom shader path. Unlike the iOS receiver (which optionally routes
 * through a Metal shader because `AVSampleBufferDisplayLayer` is suspected of
 * an extra buffered frame), `MediaCodec` configured with an output `Surface`
 * already gets a dedicated compositor path, so there is no equivalent
 * trade-off to make here.
 *
 * Deliberate simplification: rather than hand-parsing SPS Exp-Golomb bits for
 * the real coded width/height, [expectedWidth]/[expectedHeight] (this
 * device's own panel size — the Mac captures a virtual display sized to
 * exactly that) seed `MediaFormat`, and the real size arrives moments later
 * via `INFO_OUTPUT_FORMAT_CHANGED` (see [onSizeChanged]). MediaCodec expects
 * Annex-B access units in its input buffers on Android (unlike VideoToolbox's
 * AVCC), so wire NALUs are fed through unchanged, just prefixed with start
 * codes.
 *
 * Not thread-safe — feed it from a single thread/coroutine (the same one
 * draining [io.github.mohithdas.opendisplay.tv.net.PhoneReceiver.videoFrames]).
 */
class VideoDecoder(
    private val surface: Surface,
    private var expectedWidth: Int,
    private var expectedHeight: Int,
    private val onSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    /** Called (at most once a second) when the codec errors out, so the
     * caller can ask the Mac for a fresh keyframe — mirrors the iOS
     * receiver's `requestKeyframeIfNeeded`. Without this, a decoder error
     * would otherwise leave the picture frozen until the Mac's own periodic
     * keyframe, up to 60s away (see `Mac/MacSender.swift`). */
    private val onError: () -> Unit = {},
) {
    private var codec: MediaCodec? = null
    private var currentSps: ByteArray? = null
    private var currentPps: ByteArray? = null
    private var lastErrorSignalAt = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    private var queuedFrames = 0L
    private var renderedFrames = 0L
    private var droppedFrames = 0L
    private var lastStatsAt = 0L
    private var waitingForKeyframe = true
    private val progress = DecoderProgress()

    private fun requestKeyframe() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastErrorSignalAt >= 1000) {
            lastErrorSignalAt = now
            onError()
        }
    }

    fun drain() {
        val activeCodec = codec ?: run {
            requestKeyframe()
            return
        }
        try {
            drainOutput(activeCodec)
            val now = android.os.SystemClock.elapsedRealtime()
            when (progress.recoveryAction(now)) {
                DecoderRecoveryAction.REQUEST_KEYFRAME -> {
                    Log.warn("decoder output delayed with ${progress.pendingFrames} pending frames; requesting keyframe without restart")
                    requestKeyframe()
                }
                DecoderRecoveryAction.RESTART -> {
                    Log.warn("decoder did not recover after keyframe request; rebuilding with ${progress.pendingFrames} pending frames")
                    signalError()
                    return
                }
                DecoderRecoveryAction.NONE -> Unit
            }
            if (now - lastStatsAt >= 1000) {
                Log.info("decoder totals: queued=$queuedFrames rendered=$renderedFrames dropped=$droppedFrames")
                lastStatsAt = now
            }
        } catch (error: Exception) {
            Log.error("decoder output failed", error)
            signalError()
        }
    }

    /** Update the seed size (e.g. after a rotation) before the next SPS/PPS
     * change triggers a reconfigure. Does not itself force a reconfigure —
     * the wire protocol always follows a rotation with new SPS/PPS anyway. */
    fun updateExpectedSize(width: Int, height: Int) {
        expectedWidth = width
        expectedHeight = height
    }

    fun submit(frame: VideoFrame) {
        var headersChanged = false
        frame.sps?.let {
            if (currentSps == null || !it.contentEquals(currentSps)) {
                currentSps = it
                headersChanged = true
            }
        }
        frame.pps?.let {
            if (currentPps == null || !it.contentEquals(currentPps)) {
                currentPps = it
                headersChanged = true
            }
        }
        if (headersChanged) {
            release()
            waitingForKeyframe = true
        }
        if (frame.vclNalus.isEmpty()) return
        val isKeyframe = frame.vclNalus.any { (it[0].toInt() and 0x1f) == 5 }
        if (waitingForKeyframe && !isKeyframe) {
            requestKeyframe()
            return
        }
        if (codec == null) reconfigure()
        val mediaCodec = codec ?: run {
            requestKeyframe()
            return
        }
        waitingForKeyframe = false
        queueAccessUnit(mediaCodec, frame.vclNalus)
    }

    private fun reconfigure() {
        val sps = currentSps ?: return
        val pps = currentPps ?: return
        release()
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, expectedWidth, expectedHeight)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + pps))
            val mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = mediaCodec // Own it before configure/start so failure cleanup releases it.
            val supportsLowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                mediaCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            Log.info("decoder ${mediaCodec.name}, low-latency supported=$supportsLowLatency")
            if (supportsLowLatency) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime

            mediaCodec.configure(format, surface, null, 0)
            mediaCodec.start()
            Log.info("MediaCodec ${mediaCodec.name} configured, seed ${expectedWidth}x$expectedHeight")
        } catch (e: Exception) {
            Log.error("MediaCodec configure failed", e)
            signalError()
        }
    }

    private fun queueAccessUnit(mediaCodec: MediaCodec, nalus: List<ByteArray>) {
        try {
            drainOutput(mediaCodec)
            var index = mediaCodec.dequeueInputBuffer(0)
            val deadline = android.os.SystemClock.elapsedRealtime() + 100
            while (index < 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                drainOutput(mediaCodec)
                index = mediaCodec.dequeueInputBuffer(5000)
            }
            if (index < 0) {
                droppedFrames++
                Log.warn("decoder input stalled; requesting a fresh keyframe")
                signalError()
                return
            }
            val inputBuffer = checkNotNull(mediaCodec.getInputBuffer(index)) { "Codec returned no input buffer" }
            inputBuffer.clear()
            var size = 0
            for (nalu in nalus) {
                inputBuffer.put(START_CODE)
                inputBuffer.put(nalu)
                size += START_CODE.size + nalu.size
            }
            mediaCodec.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, 0)
            queuedFrames++
            progress.queued(android.os.SystemClock.elapsedRealtime())
            drainOutput(mediaCodec)
        } catch (e: Exception) {
            Log.error("decode failed — rebuilding on the next keyframe", e)
            signalError()
        }
    }

    /** Tears the codec down and forgets the last-seen SPS/PPS, so even a
     * keyframe with byte-identical headers to before triggers a fresh
     * [reconfigure] (headersChanged in [submit] only fires on a *change*).
     * Debounced to at most once a second — a stuck codec would otherwise
     * fail every single frame and spam keyframe requests. */
    private fun signalError() {
        release()
        currentSps = null
        currentPps = null
        waitingForKeyframe = true
        requestKeyframe()
    }

    private fun drainOutput(mediaCodec: MediaCodec) {
        while (true) {
            val outIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex >= 0 -> {
                    mediaCodec.releaseOutputBuffer(outIndex, true)
                    renderedFrames++
                    progress.rendered(android.os.SystemClock.elapsedRealtime())
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = mediaCodec.outputFormat
                    val width = if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                        format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                    } else format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                        format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                    } else format.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.info("decoder output format changed: ${width}x$height")
                    onSizeChanged(width, height)
                }
                else -> return // INFO_TRY_AGAIN_LATER or the deprecated buffers-changed code
            }
        }
    }

    fun release() {
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        progress.reset()
    }

    /** Releases decoder resources and forgets stream headers after a peer disconnects. */
    fun reset() {
        waitingForKeyframe = true
        release()
        currentSps = null
        currentPps = null
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
