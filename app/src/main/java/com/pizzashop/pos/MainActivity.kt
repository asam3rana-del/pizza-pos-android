package com.pizzashop.pos

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Shows the POS screens (cashier, kitchen, ...) served by the shop server inside a WebView and adds
 * what a browser cannot do: printing to a Bluetooth printer paired with this phone.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private val printQueue = Executors.newSingleThreadExecutor()
    private val netQueue = Executors.newSingleThreadExecutor()      // server search (kept apart so a slow printer never delays it)
    private var loadedServer = ""
    private var pendingScreen: String? = null                      // screen asked for by a tapped notification (Step 28)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pendingScreen = intent?.getStringExtra(EXTRA_SCREEN)
        web = WebView(this)
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true                       // the login token lives in localStorage
        s.mediaPlaybackRequiresUserGesture = false       // kitchen beep
        s.setSupportZoom(false)
        s.userAgentString = s.userAgentString + " PizzaPOSApp/1.2"

        web.webChromeClient = WebChromeClient()          // lets confirm() / alert() dialogs of the screens work
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                val base = Uri.parse(loadedServer)
                if (target.scheme == base.scheme && target.host == base.host && target.port == base.port) return false
                openExternal(target)
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showMessage(
                        "Cannot reach the POS server",
                        "Check the shop Wi-Fi and that the server PC is running.<br>Server: " + TextUtils.htmlEncode(loadedServer)
                    )
                }
            }
        }
        web.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }
        web.addJavascriptInterface(Bridge(), "AndroidApp")

        if (Prefs.normalizeServer(prefs.server).isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (prefs.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val server = Prefs.normalizeServer(prefs.server)
        if (server.isEmpty()) {
            showNoServer()
        } else if (server != loadedServer) {
            loadStart()
        }
        openPendingScreen(server)
        askNotificationPermission()
        AlertService.sync(this)                          // starts or stops the "order ready" alerts to match the settings
    }

    /** A tapped alert asks for a screen by name (never a web address): today's orders or the delivery board. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingScreen = intent.getStringExtra(EXTRA_SCREEN)       // opened by onResume, which follows
    }

    private fun openPendingScreen(server: String) {
        val screen = pendingScreen
        pendingScreen = null
        intent?.removeExtra(EXTRA_SCREEN)
        if (screen == null || server.isEmpty()) return
        val path = when (screen) {
            SCREEN_ORDERS -> "/cashier.html?tab=orders"
            SCREEN_DELIVERY -> "/delivery.html"
            else -> return
        }
        loadedServer = server
        web.loadUrl(server + path)
    }

    /** Android 13+ asks once whether this app may show notifications (needed for the order ready alerts). */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || prefs.askedNotifications || (prefs.alertMode == Prefs.MODE_OFF && prefs.extraAlerts == Prefs.EXTRA_OFF)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.askedNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        printQueue.shutdown()
        netQueue.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private fun loadStart() {
        val server = Prefs.normalizeServer(prefs.server)
        if (server.isEmpty()) {
            showNoServer()
            return
        }
        loadedServer = server
        web.loadUrl("$server/cashier.html")
    }

    private fun showNoServer() {
        showMessage("Set the server address", "Open Phone settings and type the address of the POS server, for example 192.168.1.10:3000")
    }

    private fun showMessage(title: String, body: String) {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="font-family:sans-serif;padding:24px;text-align:center;background:#f4efe8;color:#2b2420">
            <h2>$title</h2>
            <p>$body</p>
            <p><button style="font-size:18px;padding:12px 24px" onclick="AndroidApp.retry()">Try again</button></p>
            <p><button style="font-size:18px;padding:12px 24px" onclick="AndroidApp.findServer()">Find the server</button></p>
            <p><button style="font-size:18px;padding:12px 24px" onclick="AndroidApp.openSettings()">Phone settings</button></p>
            </body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            // no app can open it
        }
    }

    private fun isRole(target: String): Boolean = target == Prefs.ROLE_KITCHEN || target == Prefs.ROLE_RECEIPT

    /** Tells the page that a print finished (empty text = success). */
    private fun reportPrint(id: Int, error: String) {
        runOnUiThread {
            web.evaluateJavascript("window.__btDone && window.__btDone($id, ${JSONObject.quote(error)})", null)
        }
    }

    /** What the screens (slip.js, auth.js) can call: window.AndroidApp.xxx(...) */
    inner class Bridge {
        /** Is a Bluetooth printer chosen on this phone for "receipt" or "kitchen"? */
        @JavascriptInterface
        fun has(target: String): Boolean = isRole(target) && prefs.printerAddress(target).isNotEmpty()

        /** Printable width in dots: 384 for 58 mm paper, 576 for 80 mm paper. */
        @JavascriptInterface
        fun paperDots(target: String): Int = prefs.printerDots(if (isRole(target)) target else Prefs.ROLE_RECEIPT)

        /** Prints a black/white picture (1 bit per dot, rows packed in bytes, base64). The result comes back through window.__btDone(id, error). */
        @JavascriptInterface
        fun printRaster(id: Int, target: String, width: Int, height: Int, base64: String) {
            printQueue.execute {
                var error = ""
                try {
                    if (!has(target)) throw IllegalStateException("No Bluetooth printer is set for $target")
                    if (width < 8 || width > 832 || width % 8 != 0) throw IllegalStateException("Bad picture width")
                    if (height < 1 || height > 8000) throw IllegalStateException("Bad picture height")
                    val data = Base64.decode(base64, Base64.DEFAULT)
                    if (data.size != width / 8 * height) throw IllegalStateException("Picture data does not match its size")
                    val job = BtPrinter.rasterJob(width, height, data, target == Prefs.ROLE_KITCHEN)
                    BtPrinter.send(this@MainActivity, prefs.printerAddress(target), job)
                } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                reportPrint(id, error)
            }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread { loadStart() }
        }

        /** The screens give the login to the app after log in, so the background "order ready" alerts can use it. */
        @JavascriptInterface
        fun setSession(token: String, name: String, role: String) {
            prefs.setSession(token, name, role)
            AlertService.sync(this@MainActivity)
        }

        /** Called at log out or when the login ended. */
        @JavascriptInterface
        fun clearSession() {
            prefs.clearSession()
            AlertService.sync(this@MainActivity)
        }

        /** Looks for the POS server on the Wi-Fi, saves its address and opens it. Used when the saved address stopped working (new IP). */
        @JavascriptInterface
        fun findServer() {
            netQueue.execute {
                val list = DiscoveryClient.find()
                runOnUiThread {
                    if (list.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No POS server found. Is this phone on the shop Wi-Fi and the server running?", Toast.LENGTH_LONG).show()
                    } else {
                        prefs.server = list[0].address
                        Toast.makeText(this@MainActivity, "Found " + list[0].label, Toast.LENGTH_LONG).show()
                        loadStart()
                    }
                }
            }
        }

        @JavascriptInterface
        fun version(): String = "1.2"
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_ORDERS = "orders"
        const val SCREEN_DELIVERY = "delivery"
    }
}
