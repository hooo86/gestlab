package com.gestlab.com.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ExpandableListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.gestlab.com.BuildConfig
import com.gestlab.com.CredentialStore
import com.gestlab.com.Downloads
import com.gestlab.com.MenuAdapter
import com.gestlab.com.OdooMenu
import com.gestlab.com.OdooMenus
import com.gestlab.com.OdooSessionExpired
import com.gestlab.com.R
import com.gestlab.com.VpnController
import com.gestlab.com.VpnCredentials
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Écran principal : connexion VPN, puis Odoo dans une WebView, avec un menu
 * latéral natif construit à partir des menus autorisés pour l'utilisateur.
 */
class MainActivity : AppCompatActivity() {

    private val baseUrl = BuildConfig.ODOO_URL.trimEnd('/')
    private val odooHost: String? = Uri.parse(BuildConfig.ODOO_URL).host

    private lateinit var vpn: VpnController
    private lateinit var store: CredentialStore

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var web: WebView
    private lateinit var pageProgress: LinearProgressIndicator
    private lateinit var statusPanel: View
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var statusRetry: MaterialButton
    private lateinit var statusLogin: MaterialButton
    private lateinit var statusLogScroll: android.widget.ScrollView
    private lateinit var statusLog: TextView
    private lateinit var statusCopyLog: MaterialButton
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val vpnTimeout = Runnable {
        if (awaitingVpn && vpn.level != ConnectionStatus.LEVEL_CONNECTED) {
            statusLog.text = vpn.logText().ifEmpty {
                "(aucun message du moteur VPN : le service ne s'est pas lancé)\n" +
                    "État : ${vpn.level}"
            }
            showPanel(getString(R.string.vpn_timeout), progress = false, retry = true)
        }
    }
    private lateinit var vpnState: TextView
    private lateinit var vpnToggle: TextView
    private lateinit var menuHint: TextView
    private val menuAdapter = MenuAdapter()

    /** Mot de passe saisi sans « Se souvenir de moi » : gardé en mémoire uniquement. */
    private var transientCredentials: VpnCredentials? = null
    private var pendingProfile: VpnProfile? = null
    private var awaitingVpn = false
    private var sawVpnProgress = false
    private var loginShown = false
    private var odooLoaded = false
    private var menusLoaded = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var connectedAt = 0L
    private var autoRetried = false

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            loginShown = false
            val creds = LoginActivity.credentialsFrom(result.data)
            if (result.resultCode == Activity.RESULT_OK && creds != null) {
                transientCredentials = creds
                connect()
            } else {
                showPanel(getString(R.string.vpn_login_required), progress = false, retry = false, login = true)
            }
        }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val profile = pendingProfile
            if (result.resultCode == Activity.RESULT_OK && profile != null) {
                vpn.start(profile)
            } else {
                awaitingVpn = false
                showPanel(getString(R.string.vpn_permission_denied), progress = false, retry = true)
            }
        }

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = CredentialStore(this)
        vpn = VpnController(this)

        bindViews()
        setupDrawer()
        setupWebView()
        setupBack()

        vpn.attach(::onVpnState, ::onVpnLog)
        requestNotificationPermission()
        connect()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(vpnTimeout)
        vpn.detach()
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ VPN

    private fun connect() {
        if (!vpn.hasConfig()) {
            showPanel(getString(R.string.vpn_config_missing), progress = false, retry = false)
            return
        }
        if (vpn.level == ConnectionStatus.LEVEL_CONNECTED) {
            onVpnState(ConnectionStatus.LEVEL_CONNECTED, "")
            return
        }
        val creds = transientCredentials ?: store.load()
        if (creds == null) {
            showLogin(null)
            return
        }
        val profile = try {
            vpn.buildProfile(creds)
        } catch (e: Exception) {
            showPanel(getString(R.string.vpn_config_invalid, e.message.orEmpty()), progress = false, retry = false)
            return
        }
        pendingProfile = profile
        awaitingVpn = true
        sawVpnProgress = false
        showPanel(getString(R.string.vpn_connecting), progress = true, retry = false)
        timeoutHandler.removeCallbacks(vpnTimeout)
        timeoutHandler.postDelayed(vpnTimeout, VPN_TIMEOUT_MS)

        val consent = VpnService.prepare(this)
        if (consent != null) vpnPermissionLauncher.launch(consent) else vpn.start(profile)
    }

    private fun onVpnState(level: ConnectionStatus, detail: String) {
        val label = VpnController.label(this, level)
        val connected = level == ConnectionStatus.LEVEL_CONNECTED
        vpnState.text = getString(R.string.vpn_state, label)
        vpnToggle.setText(if (connected || awaitingVpn) R.string.menu_vpn_disconnect else R.string.menu_vpn_connect)

        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                awaitingVpn = false
                com.gestlab.com.GestlabApp.clearCrashReports(this)
                timeoutHandler.removeCallbacks(vpnTimeout)
                toolbar.subtitle = null
                hidePanel()
                connectedAt = android.os.SystemClock.elapsedRealtime()
                autoRetried = false
                if (!odooLoaded) {
                    odooLoaded = true
                    // Laisse Android basculer le réseau de l'application sur le tunnel.
                    web.postDelayed({ web.loadUrl("$baseUrl/web") }, 1500)
                }
            }

            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                awaitingVpn = false
                vpn.stop()
                store.clearPassword()
                transientCredentials = null
                showLogin(getString(R.string.vpn_auth_failed))
            }

            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                // Juste après la demande de connexion, l'ancien état « déconnecté » est
                // encore renvoyé : on l'ignore tant que la connexion n'a pas démarré.
                if (awaitingVpn && !sawVpnProgress) return
                awaitingVpn = false
                if (odooLoaded) toolbar.subtitle = label
                else showPanel(label, progress = false, retry = true)
            }

            else -> {
                sawVpnProgress = true
                if (odooLoaded) toolbar.subtitle = label
                else showPanel(if (detail.isNotBlank()) "$label\n$detail" else label, progress = true, retry = false)
            }
        }
    }

    private fun onVpnLog(text: String) {
        statusLog.text = text
        if (statusPanel.visibility == View.VISIBLE && text.isNotEmpty()) {
            statusLogScroll.visibility = View.VISIBLE
            statusCopyLog.visibility = View.VISIBLE
        }
        statusLogScroll.post { statusLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Android 13+ : la notification du VPN (état, déconnexion) demande une autorisation. */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun showLogin(error: String?) {
        if (loginShown) return
        loginShown = true
        loginLauncher.launch(LoginActivity.intent(this, error))
    }

    // ------------------------------------------------------------------ Panneau d'état

    private fun showPanel(text: String, progress: Boolean, retry: Boolean, login: Boolean = false) {
        statusPanel.visibility = View.VISIBLE
        statusText.text = text
        statusProgress.visibility = if (progress) View.VISIBLE else View.GONE
        statusRetry.visibility = if (retry) View.VISIBLE else View.GONE
        statusLogin.visibility = if (login || retry) View.VISIBLE else View.GONE
        val showLog = statusLog.text.isNotEmpty() && (progress || retry)
        statusLogScroll.visibility = if (showLog) View.VISIBLE else View.GONE
        statusCopyLog.visibility = statusLogScroll.visibility
    }

    private fun hidePanel() {
        statusPanel.visibility = View.GONE
    }

    // ------------------------------------------------------------------ Vues

    private fun bindViews() {
        drawer = findViewById(R.id.drawer)
        toolbar = findViewById(R.id.toolbar)
        web = findViewById(R.id.web)
        pageProgress = findViewById(R.id.page_progress)
        statusPanel = findViewById(R.id.status_panel)
        statusProgress = findViewById(R.id.status_progress)
        statusText = findViewById(R.id.status_text)
        statusRetry = findViewById(R.id.status_retry)
        statusLogin = findViewById(R.id.status_login)
        statusLogScroll = findViewById(R.id.status_log_scroll)
        statusLog = findViewById(R.id.status_log)
        statusCopyLog = findViewById(R.id.status_copy_log)
        statusLog.text = vpn.logText()
        statusCopyLog.setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Journal VPN", vpn.logText()))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
        vpnState = findViewById(R.id.drawer_vpn_state)
        vpnToggle = findViewById(R.id.drawer_vpn_toggle)
        menuHint = findViewById(R.id.drawer_menu_hint)

        statusRetry.setOnClickListener {
            if (odooLoaded && vpn.level == ConnectionStatus.LEVEL_CONNECTED) {
                hidePanel(); web.reload()
            } else connect()
        }
        statusLogin.setOnClickListener {
            vpn.stop()
            transientCredentials = null
            showLogin(null)
        }

        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        toolbar.inflateMenu(R.menu.toolbar)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_reload -> web.reload()
                R.id.action_home -> web.loadUrl("$baseUrl/web")
            }
            true
        }
    }

    private fun setupDrawer() {
        val list = findViewById<ExpandableListView>(R.id.drawer_menu)
        list.setAdapter(menuAdapter)
        list.setOnGroupClickListener { _, _, group, _ ->
            if (menuAdapter.getChildrenCount(group) == 0) {
                open(menuAdapter.getGroup(group)); true
            } else false
        }
        list.setOnChildClickListener { _, _, group, child, _ ->
            val entry = menuAdapter.getChild(group, child)
            if (!entry.isSection) open(entry.menu)
            true
        }

        findViewById<View>(R.id.drawer_refresh).setOnClickListener {
            menusLoaded = false
            loadMenus()
        }
        findViewById<View>(R.id.drawer_odoo_logout).setOnClickListener {
            drawer.closeDrawers()
            web.loadUrl("$baseUrl/web/session/logout")
        }
        vpnToggle.setOnClickListener {
            drawer.closeDrawers()
            if (vpn.level == ConnectionStatus.LEVEL_CONNECTED || awaitingVpn) {
                awaitingVpn = false
                vpn.stop()
            } else connect()
        }
        findViewById<View>(R.id.drawer_forget).setOnClickListener {
            drawer.closeDrawers()
            store.clear()
            transientCredentials = null
            vpn.stop()
            Toast.makeText(this, R.string.credentials_forgotten, Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(menu: OdooMenu) {
        drawer.closeDrawers()
        web.loadUrl(OdooMenus.urlOf(baseUrl, menu))
    }

    private fun loadMenus() {
        if (menusLoaded) return
        menusLoaded = true
        menuHint.setText(R.string.menu_loading)
        menuHint.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val apps = OdooMenus.load(baseUrl, CookieManager.getInstance().getCookie(baseUrl))
                menuAdapter.submit(apps)
                menuHint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                if (apps.isEmpty()) menuHint.setText(R.string.menu_empty)
            } catch (e: OdooSessionExpired) {
                menusLoaded = false
                menuAdapter.submit(emptyList())
                menuHint.setText(R.string.menu_login_first)
            } catch (e: Exception) {
                menusLoaded = false
                menuHint.text = getString(R.string.menu_error, e.message.orEmpty())
            }
        }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawers()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ------------------------------------------------------------------ WebView Odoo

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(DownloadBridge(), "GestlabDownloads")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == odooHost && (uri.scheme == "http" || uri.scheme == "https")) return false
                // Lien externe (site web, e-mail, téléphone, carte…) : application du téléphone.
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageProgress.visibility = View.GONE
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
                val path = Uri.parse(url).path.orEmpty()
                if (path.startsWith("/web/login") || path.startsWith("/web/database")) {
                    menusLoaded = false
                    menuAdapter.submit(emptyList())
                    menuHint.setText(R.string.menu_login_first)
                    menuHint.visibility = View.VISIBLE
                } else if (path.startsWith("/web") || path.startsWith("/odoo")) {
                    loadMenus()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    // Juste après l'ouverture du tunnel, une connexion peut être coupée par le
                    // changement de réseau : on réessaie une fois automatiquement.
                    val sinceConnect = android.os.SystemClock.elapsedRealtime() - connectedAt
                    if (!autoRetried && sinceConnect < 20_000) {
                        autoRetried = true
                        view.postDelayed({ view.reload() }, 2000)
                        return
                    }
                    showPanel(
                        getString(R.string.odoo_unreachable, error.description),
                        progress = false, retry = true
                    )
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageProgress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileLauncher.launch(params.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                // Odoo (rapports PDF, exports) crée le fichier dans la page : on le relit en JS.
                web.evaluateJavascript(blobFetchJs(url, mimeType), null)
            } else {
                downloadHttp(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    private fun downloadHttp(url: String, userAgent: String?, disposition: String?, mime: String?) {
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val (bytes, type) = withContext(Dispatchers.IO) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
                        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
                        conn.inputStream.use { it.readBytes() } to (conn.contentType ?: mime)
                    } finally {
                        conn.disconnect()
                    }
                }
                saveAndOpen(bytes, URLUtil.guessFileName(url, disposition, type), type)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.download_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAndOpen(bytes: ByteArray, name: String, mime: String?) {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { Downloads.save(this@MainActivity, bytes, name, mime) }
                Toast.makeText(this@MainActivity, getString(R.string.download_saved, name), Toast.LENGTH_SHORT).show()
                Downloads.open(this@MainActivity, uri, mime)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.download_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun save(dataUrl: String, name: String, mime: String) {
            val bytes = Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
            runOnUiThread {
                saveAndOpen(bytes, name.ifBlank { URLUtil.guessFileName("", null, mime) }, mime)
            }
        }

        @JavascriptInterface
        fun error(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, getString(R.string.download_failed, message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun blobFetchJs(url: String, mime: String?): String = """
        (function(){
          var n = window.__gestlabName || ''; window.__gestlabName = '';
          fetch(${JSONObject.quote(url)}).then(function(r){ return r.blob(); }).then(function(b){
            var f = new FileReader();
            f.onloadend = function(){ GestlabDownloads.save(f.result, n, b.type || ${JSONObject.quote(mime ?: "")}); };
            f.readAsDataURL(b);
          }).catch(function(e){ GestlabDownloads.error(String(e)); });
        })();
    """.trimIndent()

    private companion object {
        const val VPN_TIMEOUT_MS = 45_000L

        /** Retient le nom de fichier proposé par Odoo (<a download="facture.pdf">). */
        val DOWNLOAD_HOOK_JS = """
            (function(){
              if (window.__gestlabHook) return; window.__gestlabHook = true;
              var click = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                if (this.download) window.__gestlabName = this.download;
                return click.apply(this, arguments);
              };
              document.addEventListener('click', function(e){
                var a = e.target && e.target.closest && e.target.closest('a[download]');
                if (a) window.__gestlabName = a.download;
              }, true);
            })();
        """.trimIndent()
    }
}
