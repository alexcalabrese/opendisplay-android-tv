package io.github.mohithdas.opendisplay.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDisconnectPolicyTest {
    @Test
    fun decoderIsResetOnlyOnDisconnectTransition() {
        assertFalse(VideoDisconnectPolicy.shouldResetDecoder(false, false))
        assertFalse(VideoDisconnectPolicy.shouldResetDecoder(false, true))
        assertFalse(VideoDisconnectPolicy.shouldResetDecoder(true, true))
        assertTrue(VideoDisconnectPolicy.shouldResetDecoder(true, false))
    }

    @Test
    fun queuedFramesAreIgnoredAfterDisconnect() {
        assertTrue(VideoDisconnectPolicy.shouldSubmitFrame(connected = true))
        assertFalse(VideoDisconnectPolicy.shouldSubmitFrame(connected = false))
    }
}
