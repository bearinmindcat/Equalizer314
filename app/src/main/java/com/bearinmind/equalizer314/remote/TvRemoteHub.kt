package com.bearinmind.equalizer314.remote

import com.bearinmind.equalizer314.R
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Process-wide coordinator for TV Mode (issues #35 / #55). Owns the server
 * or client instance for the selected mode, bridges them to whatever UI is
 * alive (MainActivity registers [stateProvider] / [stateApplier]), and
 * debounces outgoing state syncs.
 *
 * Modes:
 *  - [MODE_SERVER] ("TV"): this device runs the DSP and is controlled
 *    remotely. Advertises on the LAN, shows a PIN, applies incoming state.
 *  - [MODE_CLIENT] ("Remote"): this device's UI edits a TV's state — every
 *    local EQ change is serialized (preset JSON) and streamed to the TV.
 */
object TvRemoteHub {
    private const val TAG = "TvRemoteHub"
    private const val PREFS = "tv_remote"
    private const val KEY_MODE = "tvMode"
    private const val KEY_CLIENT_TOKENS = "clientTokens" // JSON map serverName -> token

    const val MODE_OFF = 0
    const val MODE_SERVER = 1
    const val MODE_CLIENT = 2

    private val main = Handler(Looper.getMainLooper())

    /** Serialize the device's current EQ state (MainActivity's preset JSON). */
    var stateProvider: (() -> String)? = null

    /** Apply a received EQ state to the local UI + DSP. */
    var stateApplier: ((JSONObject) -> Unit)? = null

    /** Status line for the Experimental card (set while that screen is open). */
    var statusListener: ((String) -> Unit)? = null

    /** Server mode: connected-remote count changes (MainActivity uses this
     *  to show/hide the "remote controlled" touch-lock scrim). */
    var serverClientsListener: ((Int) -> Unit)? = null

    /** Simple name of the activity currently on top — maintained by
     *  [RemoteScrim]'s lifecycle callbacks, shipped in the nav block so the
     *  peer can follow navigation across screens. */
    @Volatile
    var topScreen: String = "MainActivity"

    var server: TvRemoteServer? = null
        private set
    var client: TvRemoteClient? = null
        private set

    /** Guards against echo loops: while a remote state is being applied
     *  locally, pushEqUpdate fires — that must not be re-sent. */
    @Volatile
    private var applyingRemote = false

    /** Wider echo guard: a remote apply has ASYNC fallout (DP-start
     *  broadcasts, activity resumes) landing after the [applyingRemote]
     *  window; echoing that stale state killed remote power-ons (echoed
     *  power=false shut the remote back off). Stay quiet after any incoming
     *  apply — the driving peer is the source of truth. */
    @Volatile
    private var suppressSendUntil = 0L

    /** State received before MainActivity registered its applier. */
    private var pendingState: JSONObject? = null

    private var sendJob: Runnable? = null
    private var lastStatus: String = ""

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(context: Context): Int = prefs(context).getInt(KEY_MODE, MODE_OFF)

    /** Switch modes: tears down whatever is running, starts the new role,
     *  persists the choice. Client mode starts "armed" — the actual
     *  connection happens via [connectClient] after discovery/pairing. */
    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
        server?.stop(); server = null
        client?.close(); client = null
        when (mode) {
            MODE_SERVER -> startServer(context)
            else -> status("")
        }
        // Role active → pin EqService foreground (cached-app freezer kills
        // the socket otherwise) + retitle notification; Off → normal title.
        try {
            val i = android.content.Intent(
                context.applicationContext,
                com.bearinmind.equalizer314.audio.EqService::class.java,
            ).setAction(com.bearinmind.equalizer314.audio.EqService.ACTION_TVMODE_REFRESH)
            if (mode != MODE_OFF && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(i)
            } else {
                context.applicationContext.startService(i)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TV mode service refresh failed", e)
        }
    }

    /** TV Mode is session-only: the server/client live in this process, so
     *  nothing survives a cold start — reset the persisted mode to Off so
     *  the app never launches "armed" from a previous session. */
    fun resetModeOnColdStart(context: Context) {
        if (server == null && client == null && getMode(context) != MODE_OFF) {
            prefs(context).edit().putInt(KEY_MODE, MODE_OFF).apply()
        }
    }

    private fun startServer(context: Context) {
        val s = TvRemoteServer(
            context,
            getState = { stateProvider?.invoke() ?: "{}" },
            applyState = { st -> applyRemoteState(st) },
            onStatus = { msg -> status(msg) },
            onClientsChanged = { n ->
                // Hub owns the touch lock (works on any screen); extra
                // listeners (PIN popup auto-dismiss) ride along.
                RemoteScrim.setActive(n > 0)
                serverClientsListener?.invoke(n)
            },
        )
        server = s
        s.start()
    }

    /** Connect this device (as Remote) to a discovered TV. */
    fun connectClient(context: Context, name: String, host: String, port: Int, pin: String?) {
        val appContext = context.applicationContext
        client?.close()
        val token = if (pin == null) getClientToken(appContext, name) else null
        val c = TvRemoteClient(object : TvRemoteClient.Listener {
            override fun onConnected(serverName: String, initialState: JSONObject?) {
                status(appContext.getString(R.string.remote_connected_to, serverName))
                initialState?.let { applyRemoteState(it) }
            }
            override fun onPaired(serverName: String, token: String) {
                saveClientToken(appContext, serverName, token)
            }
            override fun onStateEvent(state: JSONObject) {
                applyRemoteState(state)
            }
            override fun onError(message: String) {
                status(appContext.getString(R.string.remote_connection_failed, message))
            }
            override fun onDisconnected() {
                status(appContext.getString(R.string.remote_disconnected_from_tv))
            }
        })
        client = c
        status(appContext.getString(R.string.remote_connecting_to, name))
        c.connect(name, host, port, token, pin)
    }

    /** True when this device needs a PIN to reach [name] (no stored token). */
    fun needsPairing(context: Context, name: String): Boolean =
        getClientToken(context, name) == null

    /** Hooked into EqStateManager.pushEqUpdate: any local EQ change while a
     *  link is up streams the fresh state to the peer (debounced — drags
     *  fire this per frame). */
    fun onLocalEqChanged() {
        if (applyingRemote) return
        if (android.os.SystemClock.elapsedRealtime() < suppressSendUntil) return
        val mode = when {
            client?.connected == true -> MODE_CLIENT
            server?.running == true && (server?.connectedRemotes() ?: 0) > 0 -> MODE_SERVER
            else -> return
        }
        sendJob?.let { main.removeCallbacks(it) }
        val job = Runnable {
            sendJob = null
            val state = try { stateProvider?.invoke() } catch (e: Exception) {
                Log.e(TAG, "stateProvider failed", e); null
            } ?: return@Runnable
            Log.d(TAG, "syncing state to peer (mode=$mode, ${state.length} bytes)")
            when (mode) {
                MODE_CLIENT -> client?.sendState(state)
                MODE_SERVER -> server?.broadcastState(state)
            }
        }
        sendJob = job
        main.postDelayed(job, 150L)
    }

    /** MainActivity is alive and registered — flush anything that arrived
     *  while no UI could apply it. */
    fun onUiReady() {
        pendingState?.let { st ->
            pendingState = null
            applyRemoteState(st)
        }
    }

    fun lastStatus(): String = lastStatus

    // ---- internals ------------------------------------------------------

    private fun applyRemoteState(state: JSONObject) {
        val applier = stateApplier
        if (applier == null) {
            pendingState = state
            return
        }
        main.post {
            suppressSendUntil = android.os.SystemClock.elapsedRealtime() + 2000L
            applyingRemote = true
            try {
                applier(state)
            } catch (e: Exception) {
                Log.e(TAG, "state apply failed", e)
            } finally {
                applyingRemote = false
            }
        }
    }

    private fun getClientToken(context: Context, serverName: String): String? {
        val map = try { JSONObject(prefs(context).getString(KEY_CLIENT_TOKENS, "{}") ?: "{}") }
            catch (_: Exception) { JSONObject() }
        return map.optString(serverName, "").ifEmpty { null }
    }

    private fun saveClientToken(context: Context, serverName: String, token: String) {
        val map = try { JSONObject(prefs(context).getString(KEY_CLIENT_TOKENS, "{}") ?: "{}") }
            catch (_: Exception) { JSONObject() }
        map.put(serverName, token)
        prefs(context).edit().putString(KEY_CLIENT_TOKENS, map.toString()).apply()
    }

    private fun status(msg: String) {
        lastStatus = msg
        main.post { statusListener?.invoke(msg) }
        Log.i(TAG, msg)
    }
}
