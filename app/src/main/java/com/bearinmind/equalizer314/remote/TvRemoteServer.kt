package com.bearinmind.equalizer314.remote

import com.bearinmind.equalizer314.R
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TV Mode server (issues #35 / #55). Runs on the device doing the DSP (the
 * "TV"): advertises itself on the LAN via NSD as [SERVICE_TYPE], accepts
 * newline-delimited JSON commands from paired remotes over plain TCP, and
 * pushes state events back to them.
 *
 * Protocol (one JSON object per line):
 *   remote → tv: {"cmd":"pair","pin":"483920"}          first-time pairing
 *                {"cmd":"hello","token":"<uuid>"}       reconnect
 *                {"cmd":"apply","state":{<preset json>}} apply EQ state
 *                {"cmd":"ping"}
 *   tv → remote: {"ok":true,"token":...,"state":{...}}  pair/hello reply
 *                {"event":"state","state":{...}}        TV-side change
 *
 * Security model: LAN-only, PIN shown on the TV screen at enable time; a
 * successful pair mints a persistent token so reconnects skip the PIN.
 */
class TvRemoteServer(
    context: Context,
    private val getState: () -> String,
    private val applyState: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onClientsChanged: (Int) -> Unit = {},
) {
    companion object {
        private const val TAG = "TvRemoteServer"
        const val SERVICE_TYPE = "_eq314._tcp."
        const val DEFAULT_PORT = 31414
        private const val PREFS = "tv_remote"
        private const val KEY_TOKENS = "pairedTokens"
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "TvRemoteWrite") }
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val clients = Collections.synchronizedList(mutableListOf<ClientConn>())

    @Volatile
    var running = false
        private set
    var pin: String = ""
        private set
    var port: Int = 0
        private set
    private var lastApplyToastAt = 0L

    private fun onRemoteConnected() {
        status(appContext.getString(R.string.remote_connected_controlling))
        main.post {
            android.widget.Toast.makeText(
                appContext, appContext.getString(R.string.remote_connected), android.widget.Toast.LENGTH_SHORT
            ).show()
            onClientsChanged(connectedRemotes())
        }
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start() {
        if (running) return
        pin = "%06d".format(SecureRandom().nextInt(1_000_000))
        val ss = try {
            ServerSocket(DEFAULT_PORT)
        } catch (_: Exception) {
            ServerSocket(0) // port taken — let the OS pick; NSD carries it
        }
        serverSocket = ss
        port = ss.localPort
        running = true
        Thread({ acceptLoop(ss) }, "TvRemoteAccept").start()
        registerNsd()
        // PIN is surfaced via the Experimental card's popup, not the status line.
        status("")
        Log.i(TAG, "TV mode server started on port $port (PIN $pin)")
    }

    fun stop() {
        if (!running) return
        running = false
        try { registrationListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        registrationListener = null
        synchronized(clients) {
            for (c in clients) c.closeQuietly()
            clients.clear()
        }
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        status("")
        main.post { onClientsChanged(0) }
        Log.i(TAG, "TV mode server stopped")
    }

    /** Push the current state to every authed remote (TV-side change). */
    fun broadcastState(stateJson: String) {
        val msg = JSONObject().put("event", "state").put("state", JSONObject(stateJson)).toString()
        synchronized(clients) {
            for (c in clients) if (c.authed) c.send(msg)
        }
    }

    fun connectedRemotes(): Int = synchronized(clients) { clients.count { it.authed } }

    // ---- internals ------------------------------------------------------

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (_: Exception) { break }
            val conn = ClientConn(socket)
            clients.add(conn)
            Thread({ readLoop(conn) }, "TvRemoteRead").start()
        }
    }

    private fun readLoop(c: ClientConn) {
        try {
            while (running) {
                val line = c.reader.readLine() ?: break
                if (line.isBlank()) continue
                handleLine(c, line)
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(c)
            c.closeQuietly()
            if (running && c.authed) {
                status(appContext.getString(R.string.remote_disconnected))
                main.post { onClientsChanged(connectedRemotes()) }
            }
        }
    }

    private fun handleLine(c: ClientConn, line: String) {
        val msg = try { JSONObject(line) } catch (_: Exception) { return }
        when (msg.optString("cmd")) {
            "pair" -> {
                if (msg.optString("pin") == pin) {
                    val token = UUID.randomUUID().toString()
                    val set = HashSet(prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet())
                    set.add(token)
                    prefs.edit().putStringSet(KEY_TOKENS, set).apply()
                    c.authed = true
                    c.send(JSONObject().put("ok", true).put("token", token)
                        .put("state", JSONObject(stateOnMain())).toString())
                    onRemoteConnected()
                } else {
                    c.send(JSONObject().put("ok", false).put("err", "bad pin").toString())
                }
            }
            "hello" -> {
                val tokens = prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()
                if (msg.optString("token") in tokens) {
                    c.authed = true
                    c.send(JSONObject().put("ok", true)
                        .put("state", JSONObject(stateOnMain())).toString())
                    onRemoteConnected()
                } else {
                    c.send(JSONObject().put("ok", false).put("err", "bad token").toString())
                }
            }
            "apply" -> {
                Log.d(TAG, "apply received (authed=${c.authed})")
                if (!c.authed) return
                val st = msg.optJSONObject("state") ?: return
                main.post { try { applyState(st) } catch (e: Exception) { Log.e(TAG, "apply failed", e) } }
                // Flash a heads-up so whoever's at the TV knows the remote is
                // actually driving it (proof-of-life even if they're not on the
                // EQ graph screen). Coalesced to at most one per second.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastApplyToastAt > 1000) {
                    lastApplyToastAt = now
                    main.post {
                        android.widget.Toast.makeText(
                            appContext, appContext.getString(R.string.settings_changed_by_remote), android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            "ping" -> c.send(JSONObject().put("ok", true).put("pong", true).toString())
        }
    }

    /** State reads touch live EQ objects owned by the main thread. */
    private fun stateOnMain(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return safeGetState()
        var out = "{}"
        val latch = CountDownLatch(1)
        main.post {
            out = safeGetState()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return out
    }

    private fun safeGetState(): String =
        try { getState() } catch (e: Exception) { Log.e(TAG, "getState failed", e); "{}" }

    private fun registerNsd() {
        try {
            val info = NsdServiceInfo().apply {
                // Just the device model — the service TYPE already scopes
                // discovery to Equalizer314 instances.
                serviceName = android.os.Build.MODEL ?: "Device"
                serviceType = SERVICE_TYPE
                setPort(this@TvRemoteServer.port)
            }
            val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = nsd
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {
                    Log.i(TAG, "NSD registered as ${i.serviceName}")
                }
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                    status(appContext.getString(R.string.discovery_ad_failed, code))
                }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            registrationListener = listener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD registration failed", e)
        }
    }

    private fun status(msg: String) = main.post { onStatus(msg) }

    private inner class ClientConn(private val socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        @Volatile var authed = false

        fun send(line: String) {
            writeExecutor.execute {
                try {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                } catch (_: Exception) {
                    closeQuietly()
                }
            }
        }

        fun closeQuietly() {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
