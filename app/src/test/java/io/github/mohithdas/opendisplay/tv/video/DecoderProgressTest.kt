package io.github.mohithdas.opendisplay.tv.video

import org.junit.Assert.assertEquals
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
    @Test fun sparseBufferedFramesRequestKeyframeWithoutRestart() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.queued(1000)
        progress.queued(1500)
        assertEquals(DecoderRecoveryAction.REQUEST_KEYFRAME, progress.recoveryAction(1601))
        progress.queued(4000)
        assertEquals(DecoderRecoveryAction.NONE, progress.recoveryAction(4001))
    }

    @Test fun growingBacklogRestartsOnlyAfterSoftRecoveryTimesOut() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.queued(1500)
        assertEquals(DecoderRecoveryAction.REQUEST_KEYFRAME, progress.recoveryAction(1601))
        repeat(6) { progress.queued(2000) }
        assertEquals(DecoderRecoveryAction.NONE, progress.recoveryAction(2100))
        progress.queued(3200)
        assertEquals(DecoderRecoveryAction.RESTART, progress.recoveryAction(3201))
    }

    @Test fun resumedOutputCancelsRestartEscalation() {
        val progress = DecoderProgress()
        progress.queued(100)
        progress.queued(1500)
        assertEquals(DecoderRecoveryAction.REQUEST_KEYFRAME, progress.recoveryAction(1601))
        progress.rendered(1700)
        repeat(8) { progress.queued(3400) }
        assertEquals(DecoderRecoveryAction.REQUEST_KEYFRAME, progress.recoveryAction(3401))
    }

}
