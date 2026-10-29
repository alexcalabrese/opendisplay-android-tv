package io.github.mohithdas.opendisplay.tv.net

import java.net.InetSocketAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerReplacementPolicyTest {
    @Test
    fun reconnectFromSameHostWithNewPortDoesNotWarn() {
        val old = InetSocketAddress("192.168.0.12", 56039)
        val new = InetSocketAddress("192.168.0.12", 56041)
        assertFalse(PeerReplacementPolicy.shouldWarn(old.address, new.address))
    }

    @Test
    fun replacingConnectedHostWarns() {
        assertTrue(PeerReplacementPolicy.shouldWarn(
            InetSocketAddress("192.168.0.12", 56039).address,
            InetSocketAddress("192.168.0.14", 56041).address,
        ))
    }

    @Test
    fun firstConnectionDoesNotWarn() {
        assertFalse(PeerReplacementPolicy.shouldWarn(null, InetSocketAddress("192.168.0.12", 56039).address))
    }
}
