package io.github.mohithdas.opendisplay.tv.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal object ListenerPortBinder {
    const val FIRST_PORT = 9010
    const val LAST_PORT = 9029

    @Throws(IOException::class)
    fun bindFirstAvailable(
        bindAddress: InetAddress,
        firstPort: Int = FIRST_PORT,
        lastPort: Int = LAST_PORT,
    ): ServerSocket {
        require(firstPort in 1..65535 && lastPort in firstPort..65535)
        var lastError: IOException? = null
        for (candidate in firstPort..lastPort) {
            val server = ServerSocket()
            server.reuseAddress = true
            try {
                server.bind(InetSocketAddress(bindAddress, candidate))
                return server
            } catch (error: IOException) {
                lastError = error
                try {
                    server.close()
                } catch (_: IOException) {
                }
            }
        }
        throw ListenerPortsUnavailableException(lastError)
    }
}

internal class ListenerPortsUnavailableException(cause: IOException?) :
    IOException("No listener port is available", cause)

internal object RetryBackoff {
    fun delayMillis(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 5)
        return (1_000L shl exponent).coerceAtMost(30_000L)
    }
}

internal object NsdRegistrationPolicy {
    fun canRegister(running: Boolean, activeListenerPort: Int?, requestedPort: Int): Boolean =
        running && activeListenerPort == requestedPort
}

internal object PeerReplacementPolicy {
    fun shouldWarn(previousHost: InetAddress?, newHost: InetAddress): Boolean =
        previousHost != null && previousHost != newHost
}
