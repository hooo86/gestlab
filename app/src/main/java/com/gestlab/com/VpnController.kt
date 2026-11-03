package com.gestlab.com

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.InputStreamReader

/**
 * Pilote le tunnel OpenVPN intégré.
 *
 * Le profil vient de assets/client.ovpn (embarqué dans l'application). L'identifiant
 * et le mot de passe (auth-user-pass) sont ajoutés au moment de la connexion.
 * Seul le trafic de l'application Gestlab passe par le VPN (tunnel par application).
 */
class VpnController(context: Context) : VpnStatus.StateListener, VpnStatus.LogListener {

    fun interface Listener {
        fun onVpnState(level: ConnectionStatus, detail: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var logListener: ((String) -> Unit)? = null
    private val logLines = ArrayDeque<String>()
    private val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

    @Volatile
    var level: ConnectionStatus = ConnectionStatus.LEVEL_NOTCONNECTED
        private set

    fun hasConfig(): Boolean =
        try {
            appContext.assets.open(CONFIG_ASSET).close(); true
        } catch (e: Exception) {
            false
        }

    fun attach(l: Listener, onLog: (String) -> Unit) {
        listener = l
        logListener = onLog
        synchronized(logLines) {
            logLines.clear()
            VpnStatus.getlogbuffer().forEach { addLine(it) }
        }
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
    }

    fun detach() {
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        listener = null
        logListener = null
    }

    /** Dernières lignes du journal OpenVPN (diagnostic affiché à l'écran). */
    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    private fun addLine(item: LogItem) {
        val text = try {
            item.getString(appContext)
        } catch (e: Exception) {
            return
        }
        logLines.addLast("${timeFormat.format(java.util.Date(item.logtime))} $text")
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
    }

    override fun newLog(logItem: LogItem) {
        val text = synchronized(logLines) {
            addLine(logItem)
            logLines.joinToString("\n")
        }
        main.post { logListener?.invoke(text) }
    }

    /** Construit le profil. Lève une exception avec un message lisible si le fichier est invalide. */
    fun buildProfile(credentials: VpnCredentials): VpnProfile {
        val parser = ConfigParser()
        appContext.assets.open(CONFIG_ASSET).use { input ->
            parser.parseConfig(InputStreamReader(input, Charsets.UTF_8))
        }
        val profile = parser.convertProfile()
        profile.mName = appContext.getString(R.string.app_name)
        profile.mUsername = credentials.username
        profile.mPassword = credentials.password
        // Tunnel limité à cette application : le reste du téléphone n'est pas concerné.
        profile.mAllowedAppsVpnAreDisallowed = false
        profile.mAllowedAppsVpn = hashSetOf(appContext.packageName)
        profile.mAllowAppVpnBypass = false

        val check = profile.checkProfile(appContext)
        if (check != de.blinkt.openvpn.R.string.no_error_found) {
            throw IllegalStateException(appContext.getString(check))
        }
        return profile
    }

    /** À appeler une fois l'autorisation VPN Android accordée (VpnService.prepare). */
    fun start(profile: VpnProfile) {
        // Enregistré chiffré dans le stockage privé de l'application.
        ProfileManager.setTemporaryProfile(appContext, profile)
        VPNLaunchHelper.startOpenVpn(profile, appContext, "gestlab", true)
    }

    fun stop() {
        val intent = Intent(appContext, OpenVPNService::class.java)
            .setAction(OpenVPNService.START_SERVICE)
        appContext.bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    IOpenVPNServiceInternal.Stub.asInterface(binder).stopVPN(false)
                } catch (e: Exception) {
                    VpnStatus.logException(e)
                }
                appContext.unbindService(this)
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }, Context.BIND_AUTO_CREATE)
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus,
        intent: Intent?
    ) {
        this.level = level
        val detail = logmessage.orEmpty()
        main.post { listener?.onVpnState(level, detail) }
    }

    override fun setConnectedVPN(uuid: String?) {}

    companion object {
        const val CONFIG_ASSET = "client.ovpn"
        private const val MAX_LOG_LINES = 60

        fun label(context: Context, level: ConnectionStatus): String = context.getString(
            when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> R.string.vpn_connected
                ConnectionStatus.LEVEL_VPNPAUSED -> R.string.vpn_paused
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                ConnectionStatus.LEVEL_START -> R.string.vpn_connecting
                ConnectionStatus.LEVEL_NONETWORK -> R.string.vpn_no_network
                ConnectionStatus.LEVEL_AUTH_FAILED -> R.string.vpn_auth_failed
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> R.string.vpn_waiting
                ConnectionStatus.LEVEL_NOTCONNECTED,
                ConnectionStatus.UNKNOWN_LEVEL -> R.string.vpn_disconnected
            }
        )
    }
}
