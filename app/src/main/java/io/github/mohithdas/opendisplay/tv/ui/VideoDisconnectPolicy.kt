package io.github.mohithdas.opendisplay.tv.ui

internal object VideoDisconnectPolicy {
    fun shouldResetDecoder(previouslyConnected: Boolean, connected: Boolean): Boolean =
        previouslyConnected && !connected

    fun shouldSubmitFrame(connected: Boolean): Boolean = connected
}
