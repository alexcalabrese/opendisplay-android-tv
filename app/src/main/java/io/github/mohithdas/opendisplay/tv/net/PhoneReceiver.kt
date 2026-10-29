// Derived from iOS/PhoneReceiver.swift in peetzweg/opendisplay.
// Copyright (c) 2026 Philip Poloczek. Licensed under GPL-3.0.

package io.github.mohithdas.opendisplay.tv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import io.github.mohithdas.opendisplay.tv.protocol.WireMessage
import io.github.mohithdas.opendisplay.tv.protocol.WireProtocol
import io.github.mohithdas.opendisplay.tv.settings.DisplayProfile
import io.github.mohithdas.opendisplay.tv.settings.DisplayProfileProvider
import io.github.mohithdas.opendisplay.tv.settings.DisplaySelection
import io.github.mohithdas.opendisplay.tv.settings.DisplaySelector
import io.github.mohithdas.opendisplay.tv.settings.ReceiverSettings
import io.github.mohithdas.opendisplay.tv.settings.ReceiverSettingsRepository
import io.github.mohithdas.opendisplay.tv.settings.ResolutionChoice
import io.github.mohithdas.opendisplay.tv.settings.SharedPreferencesStorage
import io.github.mohithdas.opendisplay.tv.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONObject

data class CursorPosition(val x: Double, val y: Double, val visible: Boolean)

data class CursorImage(
    val png: ByteArray,
    val normalizedWidth: Double,
    val normalizedHeight: Double,
    val anchorX: Double,
    val anchorY: Double,
)

data class VideoFrame(
    val sps: ByteArray?,
    val pps: ByteArray?,
    val vclNalus: List<ByteArray>,
    val captureMs: Long?,
    val sendMs: Long?,
)

sealed class PeerSignal {
    data object UpdateMac : PeerSignal()
    data class UpdateAndroid(val message: String?, val storeUrl: String?) : PeerSignal()
    data class PeerReplaced(val previousAddress: String, val newAddress: String) : PeerSignal()
}

data class PerfStats(
    val fps: Int = 0,
    val megabitsPerSecond: Double = 0.0,
    val e2eP50Ms: Double = 0.0,
    val e2eP95Ms: Double = 0.0,
    val rttMs: Double = 0.0,
    val approximateMemoryMb: Long = 0,
)

enum class ListenerPhase { STARTING, WAITING_FOR_NETWORK, LISTENING, RETRYING, PORTS_UNAVAILABLE, STOPPED }

data class ListenerState(
    val phase: ListenerPhase,
    val address: String? = null,
    val port: Int? = null,
    val retrySeconds: Int = 0,
) {
    val addressAndPort: String?
        get() = if (address != null && port != null) "$address:$port" else null
}

enum class NsdPhase { IDLE, REGISTERING, REGISTERED, RETRYING, UNAVAILABLE }

data class NsdState(
    val phase: NsdPhase,
    val registeredName: String? = null,
    val retrySeconds: Int = 0,
    val errorCode: Int? = null,
)

/**
 * TCP receiver and Bonjour advertiser for the OpenDisplay protocol. The TCP listener and NSD
 * registration deliberately expose separate state: a bound socket is usable by manual IP even
 * when a vendor NSD implementation is failing.
 */
class PhoneReceiver(
    context: Context,
    startupDisplayProfile: DisplayProfile? = null,
) {

    companion object {
        const val DEFAULT_PORT = ListenerPortBinder.FIRST_PORT
        const val SERVICE_TYPE = "_opensidecar._tcp."
        private const val PREFS_NAME = "opendisplay_tv"
        private const val KEY_INSTALL_ID = "installID"
        private const val KEY_SERVICE_NAME = "serviceName"
        private const val WATCHDOG_TIMEOUT_MS = 5_000L
        private const val PING_INTERVAL_MS = 2_000L
        private const val READ_BUFFER_SIZE = 64 * 1024
        private val ALLOWED_STORE_HOSTS = setOf("github.com", "play.google.com")

        internal fun sanitizedStoreUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val uri = try {
                java.net.URI(raw)
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != "https" || uri.host !in ALLOWED_STORE_HOSTS) return null
            return raw
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val sendLock = Any()
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var displayProfile = startupDisplayProfile
        ?: DisplayProfileProvider.detectServiceSafe(appContext)
    private val settingsRepository = ReceiverSettingsRepository(
        SharedPreferencesStorage(preferences),
        displayProfile.isTelevision,
    )
    val settings: StateFlow<ReceiverSettings> = settingsRepository.settings

    private val _displaySelection = MutableStateFlow(resolveDisplaySelection())
    val displaySelection: StateFlow<DisplaySelection> = _displaySelection.asStateFlow()

    private val _supportedResolutions = MutableStateFlow(DisplaySelector.supportedChoices(displayProfile))
    val supportedResolutions: StateFlow<List<ResolutionChoice>> = _supportedResolutions.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null

    private var listenerJob: Job? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkRestartJob: Job? = null
    private var nsdRetryJob: Job? = null
    private var nsdRetryAttempt = 0

    @Volatile private var lastDataReceivedAt = System.currentTimeMillis()

    private val _listenerState = MutableStateFlow(ListenerState(ListenerPhase.STARTING))
    val listenerState: StateFlow<ListenerState> = _listenerState.asStateFlow()

    private val _nsdState = MutableStateFlow(NsdState(NsdPhase.IDLE))
    val nsdState: StateFlow<NsdState> = _nsdState.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _videoFrames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 2,
    )
    val videoFrames: SharedFlow<VideoFrame> = _videoFrames.asSharedFlow()

    private val _cursorPosition = MutableStateFlow<CursorPosition?>(null)
    val cursorPosition: StateFlow<CursorPosition?> = _cursorPosition.asStateFlow()

    private val _cursorImage = MutableSharedFlow<CursorImage>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val cursorImage: SharedFlow<CursorImage> = _cursorImage.asSharedFlow()

    private val _peerSignal = MutableStateFlow<PeerSignal?>(null)
    val peerSignal: StateFlow<PeerSignal?> = _peerSignal.asStateFlow()

    private val _perf = MutableStateFlow(PerfStats())
    val perf: StateFlow<PerfStats> = _perf.asStateFlow()
    private var framesThisWindow = 0
    private var bytesThisWindow = 0L
    private var perfWindowStartMs = System.currentTimeMillis()
    private val e2eWindow = ArrayList<Double>(120)
    @Volatile private var lastRttMs = 0.0

    private val _decoderResolution = MutableStateFlow<io.github.mohithdas.opendisplay.tv.settings.PixelSize?>(null)
    val decoderResolution: StateFlow<io.github.mohithdas.opendisplay.tv.settings.PixelSize?> =
        _decoderResolution.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdGeneration = 0

    private val _serviceName = MutableStateFlow(loadServiceName())
    val serviceName: StateFlow<String> = _serviceName.asStateFlow()

    private data class OffsetSample(val rtt: Double, val offset: Double)
    private val offsetSamples = ArrayDeque<OffsetSample>()
    @Volatile private var clockOffsetMs: Double? = null

    val installId: String by lazy { loadOrCreateInstallId() }

    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRestart()
        override fun onLost(network: Network) = scheduleNetworkRestart()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            scheduleNetworkRestart()
    }

    fun start(port: Int = DEFAULT_PORT) {
        if (!running.compareAndSet(false, true)) return
        registerNetworkCallback()
        _listenerState.value = ListenerState(ListenerPhase.STARTING)
        listenerJob = scope.launch { listenLoop(port) }
        pingJob = scope.launch { pingLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        unregisterNetworkCallback()
        networkRestartJob?.cancel()
        listenerJob?.cancel()
        readJob?.cancel()
        pingJob?.cancel()
        watchdogJob?.cancel()
        closeConnection()
        closeServerSocket()
        unadvertise()
        _connected.value = false
        _listenerState.value = ListenerState(ListenerPhase.STOPPED)
        _decoderResolution.value = null
    }

    fun enterSleep() {
        scope.launch {
            if (connected.value) sendControlBlocking(JSONObject().put("type", WireMessage.SLEEPING))
            stop()
        }
    }

    fun wake(port: Int = DEFAULT_PORT) = start(port)

    fun shutDown() {
        scope.launch {
            if (connected.value) sendControlBlocking(JSONObject().put("type", WireMessage.CLOSING))
            stop()
            scope.cancel()
        }
    }

    fun updateDisplayProfile(profile: DisplayProfile) {
        val oldProfile = displayProfile
        displayProfile = profile
        _supportedResolutions.value = DisplaySelector.supportedChoices(profile)
        val old = _displaySelection.value
        val updated = resolveDisplaySelection()
        _displaySelection.value = updated
        if (connected.value && (updated != old || profile != oldProfile)) {
            Log.info("visual display profile updated; sending an updated hello")
            sendHello()
        }
    }

    fun updateSettings(updated: ReceiverSettings) {
        val oldSelection = _displaySelection.value
        settingsRepository.update(updated)
        val newSelection = resolveDisplaySelection()
        _displaySelection.value = newSelection
        if (newSelection != oldSelection && connected.value) {
            Log.info("display settings changed; sending an updated hello")
            sendHello()
        }
    }

    fun setDecoderResolution(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            _decoderResolution.value = io.github.mohithdas.opendisplay.tv.settings.PixelSize(width, height)
        }
    }

    fun setServiceName(name: String) {
        val resolved = name.trim().ifEmpty { appContext.getString(io.github.mohithdas.opendisplay.tv.R.string.app_name) }.take(63)
        if (resolved == _serviceName.value) return
        _serviceName.value = resolved
        preferences.edit { putString(KEY_SERVICE_NAME, resolved) }
        val state = _listenerState.value
        if (state.phase == ListenerPhase.LISTENING && state.port != null) {
            unadvertise()
            advertise(state.port)
        }
    }

    fun localAddressHint(): String? = _listenerState.value.addressAndPort

    fun sendTouch(phase: String, x: Double, y: Double) {
        val message = JSONObject()
            .put("type", WireMessage.TOUCH)
            .put("phase", phase)
            .put("x", x)
            .put("y", y)
        clockOffsetMs?.let { message.put("t", nowMs() + it) }
        sendControl(message)
    }

    fun sendScroll(dx: Double, dy: Double) {
        sendControl(JSONObject().put("type", WireMessage.SCROLL).put("dx", dx).put("dy", dy))
    }

    fun requestKeyframe() = sendControl(JSONObject().put("type", WireMessage.KEYFRAME_REQUEST))

    private fun resolveDisplaySelection(): DisplaySelection = DisplaySelector.select(
        displayProfile,
        settings.value.resolution,
        settings.value.uiScale,
    )

    private suspend fun listenLoop(firstPort: Int) {
        var failureAttempt = 0
        while (running.get()) {
            val bindAddress = NetworkInfo.localIPv4InetAddress(appContext)
            if (bindAddress == null) {
                _listenerState.value = ListenerState(ListenerPhase.WAITING_FOR_NETWORK)
                unadvertise()
                delay(RetryBackoff.delayMillis(failureAttempt++))
                continue
            }
            try {
                val server = ListenerPortBinder.bindFirstAvailable(
                    bindAddress,
                    firstPort,
                    ListenerPortBinder.LAST_PORT,
                )
                serverSocket = server
                failureAttempt = 0
                val state = ListenerState(
                    ListenerPhase.LISTENING,
                    address = bindAddress.hostAddress,
                    port = server.localPort,
                )
                _listenerState.value = state
                Log.info("listening on ${state.addressAndPort}")
                advertise(server.localPort)
                while (running.get() && serverSocket === server) {
                    val client = server.accept()
                    Log.info("new connection from ${client.remoteSocketAddress}")
                    acceptConnection(client)
                }
            } catch (error: IOException) {
                if (!running.get()) return
                val delayMs = RetryBackoff.delayMillis(failureAttempt++)
                val allPortsFailed = error is ListenerPortsUnavailableException
                _listenerState.value = ListenerState(
                    phase = if (allPortsFailed) ListenerPhase.PORTS_UNAVAILABLE else ListenerPhase.RETRYING,
                    address = bindAddress.hostAddress,
                    retrySeconds = (delayMs / 1000).toInt(),
                )
                Log.warn("listener failed; retrying in ${delayMs}ms", error)
                delay(delayMs)
            } finally {
                closeServerSocket()
                unadvertise()
            }
        }
    }

    private fun acceptConnection(client: Socket) {
        val previousHost = socket?.inetAddress
        val previousAddress = socket?.remoteSocketAddress?.toString()
        val newAddress = client.remoteSocketAddress?.toString()
        closeConnection()
        _peerSignal.value = null
        if (PeerReplacementPolicy.shouldWarn(previousHost, client.inetAddress) &&
            previousAddress != null && newAddress != null) {
            _peerSignal.value = PeerSignal.PeerReplaced(previousAddress, newAddress)
        }
        client.tcpNoDelay = true
        socket = client
        outputStream = client.getOutputStream()
        lastDataReceivedAt = System.currentTimeMillis()
        _connected.value = true
        sendHello()
        readJob = scope.launch { readLoop(client) }
    }

    private suspend fun readLoop(client: Socket) {
        val frameDecoder = Framing.FrameDecoder()
        val input = client.getInputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (running.get() && socket === client) {
                val count = input.read(buffer)
                if (count < 0) break
                lastDataReceivedAt = System.currentTimeMillis()
                bytesThisWindow += count
                val frames = try {
                    frameDecoder.feed(buffer, count)
                } catch (error: IllegalArgumentException) {
                    Log.error("invalid frame from peer; dropping connection", error)
                    break
                }
                for (frame in frames) dispatchFrame(frame)
            }
        } catch (error: IOException) {
            if (running.get()) Log.info("receive error: ${error.message}")
        } finally {
            if (socket === client) {
                socket = null
                outputStream = null
                _connected.value = false
                _decoderResolution.value = null
            }
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun closeConnection() {
        val client = socket ?: return
        socket = null
        outputStream = null
        _connected.value = false
        try {
            client.close()
        } catch (_: IOException) {
        }
    }

    private fun closeServerSocket() {
        val server = serverSocket
        serverSocket = null
        try {
            server?.close()
        } catch (_: IOException) {
        }
    }

    private suspend fun dispatchFrame(frame: ByteArray) {
        if (AnnexB.isControlJson(frame)) {
            handleControlJson(frame)
            return
        }
        val parsed = AnnexB.parse(frame)
        if (parsed.vclNalus.isEmpty() && parsed.sps == null && parsed.pps == null) return
        val telemetry = AnnexB.parseTelemetry(parsed.telemetryPrefix)
        _videoFrames.emit(
            VideoFrame(parsed.sps, parsed.pps, parsed.vclNalus, telemetry.captureMs, telemetry.sendMs),
        )
        recordPerfSample(telemetry.captureMs)
    }

    private fun recordPerfSample(captureMs: Long?) {
        framesThisWindow++
        val offset = clockOffsetMs
        if (captureMs != null && offset != null) {
            val e2e = (nowMs() + offset) - captureMs
            if (e2e > -50 && e2e < 5_000 && e2eWindow.size < 240) e2eWindow.add(e2e)
        }
        val now = System.currentTimeMillis()
        val elapsedMs = now - perfWindowStartMs
        if (elapsedMs < 1_000) return
        val sorted = e2eWindow.sorted()
        val runtime = Runtime.getRuntime()
        _perf.value = PerfStats(
            fps = (framesThisWindow * 1_000 / elapsedMs).toInt(),
            megabitsPerSecond = bytesThisWindow * 8.0 / elapsedMs / 1_000.0,
            e2eP50Ms = percentile(sorted, 0.5),
            e2eP95Ms = percentile(sorted, 0.95),
            rttMs = lastRttMs,
            approximateMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
        )
        framesThisWindow = 0
        bytesThisWindow = 0
        e2eWindow.clear()
        perfWindowStartMs = now
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        return sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.lastIndex)]
    }

    private fun sendHello() {
        val selection = _displaySelection.value
        val message = JSONObject()
            .put("type", WireMessage.HELLO)
            .put("pixelsWide", selection.pixels.width)
            .put("pixelsHigh", selection.pixels.height)
            .put("scale", selection.scale)
            .put("device", if (displayProfile.isTelevision) "Android TV" else "Android")
            .put("id", installId)
            .put("pv", WireProtocol.VERSION)
        sendControl(message)
        Log.info("hello sent (${selection.pixels.width}x${selection.pixels.height} @${selection.scale}x)")
    }

    private fun handleControlJson(payload: ByteArray) {
        val value = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (error: Exception) {
            Log.warn("unparseable control message (${payload.size} bytes)", error)
            return
        }
        when (val type = value.optString("type")) {
            WireMessage.PING -> Unit
            WireMessage.WELCOME -> {
                if (value.optInt("pv", WireProtocol.ASSUMED_WHEN_ABSENT) < WireProtocol.MIN_SUPPORTED_PEER) {
                    _peerSignal.value = PeerSignal.UpdateMac
                }
            }
            WireMessage.UPDATE_REQUIRED -> {
                val message = value.optString("message").takeIf { it.isNotBlank() }
                val store = sanitizedStoreUrl(value.optString("store").takeIf { it.isNotBlank() })
                _peerSignal.value = PeerSignal.UpdateAndroid(message, store)
            }
            WireMessage.PONG -> handlePong(value)
            WireMessage.CURSOR -> {
                _cursorPosition.value = CursorPosition(
                    value.optDouble("x", 0.0),
                    value.optDouble("y", 0.0),
                    value.optInt("v", 0) == 1,
                )
            }
            WireMessage.CURSOR_IMAGE -> handleCursorImage(value)
            WireMessage.STATS -> Log.info("MAC-STATS $value")
            else -> Log.info("unknown control message type: $type")
        }
    }

    private fun handlePong(value: JSONObject) {
        if (!value.has("t") || !value.has("mt")) return
        val t1 = value.optDouble("t", Double.NaN)
        val macTime = value.optDouble("mt", Double.NaN)
        if (t1.isNaN() || macTime.isNaN()) return
        val t2 = nowMs()
        val rtt = t2 - t1
        if (rtt !in 0.0..<2_000.0) return
        lastRttMs = rtt
        offsetSamples.addLast(OffsetSample(rtt, macTime - (t1 + t2) / 2))
        if (offsetSamples.size > 15) offsetSamples.removeFirst()
        clockOffsetMs = offsetSamples.minByOrNull { it.rtt }?.offset
    }

    private fun handleCursorImage(value: JSONObject) {
        val encoded = value.optString("png").takeIf { it.isNotEmpty() } ?: return
        val png = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            Log.warn("bad cursor image base64", error)
            return
        }
        _cursorImage.tryEmit(
            CursorImage(
                png,
                value.optDouble("nw", 0.0),
                value.optDouble("nh", 0.0),
                value.optDouble("ax", 0.0),
                value.optDouble("ay", 0.0),
            ),
        )
    }

    private fun sendControl(value: JSONObject) {
        scope.launch { sendControlBlocking(value) }
    }

    private fun sendControlBlocking(value: JSONObject) {
        val stream = outputStream ?: return
        val framed = Framing.encode(value.toString().toByteArray(Charsets.UTF_8))
        synchronized(sendLock) {
            try {
                stream.write(framed)
                stream.flush()
            } catch (error: IOException) {
                Log.info("control send error: ${error.message}")
            }
        }
    }

    private suspend fun pingLoop() {
        while (running.get()) {
            delay(PING_INTERVAL_MS)
            if (_connected.value) sendControl(JSONObject().put("type", WireMessage.PING).put("t", nowMs()))
        }
    }

    private suspend fun watchdogLoop() {
        while (running.get()) {
            delay(2_000)
            if (_connected.value && System.currentTimeMillis() - lastDataReceivedAt > WATCHDOG_TIMEOUT_MS) {
                Log.info("watchdog timed out; dropping connection")
                closeConnection()
            }
        }
    }

    /** The multicast lock must be held before registerService on affected Onn/Google TV builds. */
    @Synchronized
    private fun advertise(boundPort: Int, isRetry: Boolean = false) {
        if (!NsdRegistrationPolicy.canRegister(running.get(), _listenerState.value.port, boundPort)) return
        unadvertise(resetRetryAttempt = !isRetry)
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            _nsdState.value = NsdState(NsdPhase.UNAVAILABLE)
            return
        }
        nsdManager = manager
        acquireMulticastLock()
        val generation = ++nsdGeneration
        val info = NsdServiceInfo().apply {
            serviceName = _serviceName.value
            serviceType = SERVICE_TYPE
            port = boundPort
            setAttribute("id", installId)
            setAttribute("pv", WireProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (generation != nsdGeneration) return
                nsdRetryAttempt = 0
                _nsdState.value = NsdState(NsdPhase.REGISTERED, serviceInfo.serviceName)
                Log.info("NSD registered as ${serviceInfo.serviceName} on $boundPort")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (generation != nsdGeneration) return
                Log.warn("NSD registration failed: $errorCode")
                scheduleNsdRetry(boundPort, errorCode, generation)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.info("NSD unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.warn("NSD unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        _nsdState.value = NsdState(NsdPhase.REGISTERING)
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            Log.warn("NSD registerService threw", error)
            scheduleNsdRetry(boundPort, null, generation)
        }
    }

    @Synchronized
    private fun scheduleNsdRetry(boundPort: Int, errorCode: Int?, generation: Int) {
        if (generation != nsdGeneration) return
        val delayMs = RetryBackoff.delayMillis(nsdRetryAttempt++)
        _nsdState.value = NsdState(
            NsdPhase.RETRYING,
            retrySeconds = (delayMs / 1_000).toInt(),
            errorCode = errorCode,
        )
        registrationListener = null
        nsdRetryJob?.cancel()
        nsdRetryJob = scope.launch {
            delay(delayMs)
            val state = _listenerState.value
            if (running.get() && state.phase == ListenerPhase.LISTENING && state.port == boundPort) {
                advertise(boundPort, isRetry = true)
            }
        }
    }

    @Synchronized
    private fun unadvertise(resetRetryAttempt: Boolean = true) {
        nsdGeneration++
        nsdRetryJob?.cancel()
        nsdRetryJob = null
        if (resetRetryAttempt) nsdRetryAttempt = 0
        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (error: Exception) {
                Log.warn("NSD unregisterService threw", error)
            }
        }
        registrationListener = null
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (error: Exception) {
                Log.warn("multicast lock release failed", error)
            }
        }
        multicastLock = null
        _nsdState.value = NsdState(NsdPhase.IDLE)
    }

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        try {
            multicastLock = wifi.createMulticastLock("opendisplay-tv-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Exception) {
            Log.warn("multicast lock acquisition failed", error)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered || connectivityManager == null) return
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (error: Exception) {
            Log.warn("network callback registration failed", error)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered || connectivityManager == null) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        networkCallbackRegistered = false
    }

    private fun scheduleNetworkRestart() {
        if (!running.get()) return
        networkRestartJob?.cancel()
        networkRestartJob = scope.launch {
            delay(750)
            Log.info("active network changed; rebuilding listener and NSD")
            unadvertise()
            closeServerSocket()
        }
    }

    private fun loadOrCreateInstallId(): String {
        preferences.getString(KEY_INSTALL_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            preferences.edit { putString(KEY_INSTALL_ID, it) }
        }
    }

    private fun loadServiceName(): String =
        preferences.getString(KEY_SERVICE_NAME, null) ?: Build.MODEL.takeIf { it.isNotBlank() }
        ?: appContext.getString(io.github.mohithdas.opendisplay.tv.R.string.app_name)

    private fun nowMs(): Double = System.currentTimeMillis().toDouble()
}
