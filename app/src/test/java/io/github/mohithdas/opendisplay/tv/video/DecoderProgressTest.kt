package io.github.mohithdas.opendisplay.tv.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderProgressTest {
    @Test fun idleDesktopDoesNotTriggerRecovery() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.rendered(200)
        assertFalse(progress.isStalled(60000))
    }

    @Test fun continuingInputCannotHideMissingOutput() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.queued(1500)
        assertTrue(progress.isStalled(1601))
    }

    @Test fun outputProgressExtendsDeadline() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.queued(200)
        progress.rendered(1400)
        assertFalse(progress.isStalled(2000))
        progress.queued(2800)
        assertTrue(progress.isStalled(2901))
    }

    @Test fun freshInputAfterIdleGetsFullDeadline() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.rendered(200)
        progress.queued(60000)
        assertFalse(progress.isStalled(60001))
    }

    @Test fun bufferedTailOnStaticDesktopDoesNotTriggerRecovery() {
        val progress = DecoderProgress()
        progress.queued(100)
        assertFalse(progress.isStalled(60000))
    }

    @Test fun resetDiscardsPendingWorkFromOldStream() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.reset()
        assertFalse(progress.isStalled(60000))
    }
}
