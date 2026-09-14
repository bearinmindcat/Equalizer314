package com.bearinmind.equalizer314.remote

import com.bearinmind.equalizer314.R
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * TV Mode client side (issues #35 / #55) — runs on the phone acting as the
 * remote control. [TvRemoteDiscovery] finds servers via NSD; [TvRemoteClient]
 * holds one TCP connection, does pair/hello auth, streams "apply" commands,
 * and surfaces server-pushed state events.
 */
class TvRemoteClient(
    private val listener: Listener,
) {
    interface Listener {
        /** Auth succeeded; [initialState] is the TV's current preset JSON. */
        fun onConnected(serverName: String, initialState: JSONObject?)
        /** First-time pairing minted a token — persist it for reconnects. */
        fun onPaired(serverName: String, token: String)
        /** The TV's state changed on the TV side. */
        fun onStateEvent(state: JSONObject)
        fun onError(message: String)
        fun onDisconnected()
    }

    companion object {
        private const val TAG = "TvRemoteClient"
        private const val CONNECT_TIMEOUT_MS = 4000
    }

    private val main = Handler(Looper.getMainLooper())
    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "TvRemoteClientWrite") }
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    @Volatile
    private var lastRxAt = 0L

    @Volatile
    var connected = false
        private set
    var serverName: String = ""
        private set

    /** Connect + authenticate on a background thread. Exactly one of
     *  [token] (reconnect) or [pin] (first pairing) should be non-null. */
    fun connect(name: String, host: String, port: Int, token: String?, pin: String?) {
        serverName = name
        Thread({
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket = s
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                val w = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                writer = w
                val auth = if (token != null) {
                    JSONObject().put("cmd", "hello").put("token", token)
                } else {
                    JSONObject().put("cmd", "pair").put("pin", pin ?: "")
                }
                w.write(auth.toString()); w.write("\n"); w.flush()
                val replyLine = r.readLine() ?: throw Exception("server closed during auth")
                val reply = JSONObject(replyLine)
                if (!reply.optBoolean("ok", false)) {
                    throw Exception(reply.optString("err", "auth failed"))
                }
                connected = true
                lastRxAt = System.currentTimeMillis()
                val mintedToken = reply.optString("token", "")
                if (mintedToken.isNotEmpty()) {
                    main.post { listener.onPaired(name, mintedToken) }
                }
                val state = reply.optJSONObject("state")
                main.post { listener.onConnected(name, state) }
                startKeepalive()
                readLoop(r)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed: ${e.message}")
                main.post { listener.onError(e.message ?: "connection failed") }
                close()
            }
        }, "TvRemoteClientRead").start()
    }

    /** Detects silently-dead links (Wi-Fi power-save, killed peer): pings
     *  every 4s; if nothing at all is received for 12s, declare the
     *  connection lost — a dead TCP socket otherwise swallows writes
     *  without erroring and the remote looks connected but does nothing. */
    private fun startKeepalive() {
        Thread({
            while (connected) {
                try { Thread.sleep(4000) } catch (_: InterruptedException) { break }
                if (!connected) break
                if (System.currentTimeMillis() - lastRxAt > 12_000) {
                    Log.w(TAG, "keepalive timeout — connection lost")
                    val wasConnected = connected
                    close()
                    if (wasConnected) main.post {
                        listener.onError("connection lost")
                        listener.onDisconnected()
                    }
                    break
                }
                val line = JSONObject().put("cmd", "ping").toString()
                writeExecutor.execute {
                    try { writer?.apply { write(line); write("\n"); flush() } } catch (_: Exception) {}
                }
            }
        }, "TvRemoteKeepalive").start()
    }

    private fun readLoop(r: BufferedReader) {
        try {
            while (connected) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                lastRxAt = System.currentTimeMillis()
                val msg = try { JSONObject(line) } catch (_: Exception) { continue }
                if (msg.optString("event") == "state") {
                    msg.optJSONObject("state")?.let { st ->
                        main.post { listener.onStateEvent(st) }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            val wasConnected = connected
            close()
            if (wasConnected) main.post { listener.onDisconnected() }
        }
    }

    /** Stream the full current state to the TV (debounce at the call site). */
    fun sendState(stateJson: String) {
        if (!connected) {
            Log.w(TAG, "sendState skipped — not connected")
            return
        }
        val line = JSONObject().put("cmd", "apply").put("state", JSONObject(stateJson)).toString()
        writeExecutor.execute {
            try {
                writer?.apply { write(line); write("\n"); flush() }
                Log.d(TAG, "apply sent (${line.length} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
            }
        }
    }

    fun close() {
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
    }
}

/**
 * NSD discovery of TV Mode servers. Resolved services are reported on the
 * main thread. Holds a multicast lock while scanning — some devices drop
 * mDNS packets without it.
 */
class TvRemoteDiscovery(
    context: Context,
    private val onFound: (name: String, host: String, port: Int) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val TAG = "TvRemoteDiscovery"
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        stop()
        try {
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("eq314-tv-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                // Quiet while scanning — results speak via the picker dialog.
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_eq314.")) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        main.post { onFound(resolved.serviceName, host, resolved.port) }
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "resolve failed ($code) for ${i.serviceName}")
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                main.post { onStatus(appContext.getString(R.string.discovery_failed_to_start, code)) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(TvRemoteServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices failed", e)
            main.post { onStatus("Discovery unavailable: ${e.message}") }
        }
    }

    fun stop() {
        discoveryListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discoveryListener = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
