package com.pizzashop.pos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps asking the shop server "has an order become ready?" (it answers as soon as there is news) and shows a
 * notification with sound and vibration. Runs as a foreground service so Android does not stop it.
 * It stops by itself when the person logs out, the login expires, or alerts are switched off.
 */
class AlertService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        try {
            val note = foregroundNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FOREGROUND_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FOREGROUND_ID, note)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            val t = Thread { loop() }
            t.isDaemon = true
            worker = t
            t.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun shouldRun(prefs: Prefs): Boolean = wanted(prefs)

    private fun loop() {
        val prefs = Prefs(this)
        var since = -1L
        var failures = 0
        while (running) {
            if (!shouldRun(prefs)) {
                finishSelf()
                return
            }
            try {
                val server = Prefs.normalizeServer(prefs.server)
                val url = if (since < 0) "$server/api/events?quiet=1" else "$server/api/events?since=$since&wait=25&quiet=1"
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8000
                    c.readTimeout = 40000
                    c.setRequestProperty("Authorization", "Bearer " + prefs.sessionToken)
                    val code = c.responseCode
                    if (code == 401 || code == 403) {
                        // the login ended (or this role has no alerts): stop and let the person log in again
                        prefs.clearSession()
                        notifyStopped()
                        finishSelf()
                        return
                    }
                    if (code != 200) throw IOException("HTTP $code")
                    val body = c.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val latest = json.optLong("latest", since)
                    if (since >= 0 && latest >= since) {
                        val list = json.optJSONArray("events")
                        if (list != null && running) {
                            for (i in 0 until list.length()) handle(prefs, list.getJSONObject(i))
                        }
                    }
                    since = latest                 // also covers a server that was restarted (its numbers start again from 0)
                    failures = 0
                } finally {
                    c.disconnect()
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                failures++
                try {
                    Thread.sleep(minOf(30000L, 3000L * failures))
                } catch (x: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun handle(prefs: Prefs, e: JSONObject) {
        when (e.optString("type")) {
            "order_ready" -> handleReady(prefs, e)
            "order_new" -> handleNew(prefs, e)
            "delivery_failed", "delivery_delivered" -> handleDelivery(prefs, e)
        }
    }

    private fun placeOf(e: JSONObject): String {
        val table = e.optString("table_no", "")
        return when {
            table.isNotEmpty() && table != "null" -> "Table $table"
            e.optString("order_type") == "delivery" -> "Delivery"
            e.optString("order_type") == "takeaway" -> "Takeaway"
            else -> "Dine-in"
        }
    }

    private fun show(channel: String, e: JSONObject, title: String, text: String, screen: String) {
        val builder = builder(channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(screen))
            .setAutoCancel(true)
        applyAlertSound(builder)
        getSystemService(NotificationManager::class.java)?.notify(200000 + (e.optInt("id") % 100000), builder.build())
    }

    /** A new order from somebody else (the order you took yourself needs no alert). Cashier and manager by default (Step 23). */
    private fun handleNew(prefs: Prefs, e: JSONObject) {
        if (!prefs.extraAlertsOn()) return
        val by = e.optString("created_by")
        if (by.isNotEmpty() && by == prefs.sessionName) return
        val count = e.optInt("item_count")
        val items = if (count > 0) " - $count item" + (if (count == 1) "" else "s") else ""
        show(NEW_CHANNEL, e, "New order #${e.optInt("order_no")}", placeOf(e) + items + (if (by.isNotEmpty()) " (by $by)" else ""), MainActivity.SCREEN_ORDERS)
    }

    /** A delivery that failed (needs a decision) or came back with cash still to collect (Step 23). */
    private fun handleDelivery(prefs: Prefs, e: JSONObject) {
        if (!prefs.extraAlertsOn()) return
        val no = e.optInt("order_no")
        val rider = e.optString("rider_name", "").let { if (it == "null") "" else it }
        if (e.optString("type") == "delivery_failed") {
            val note = e.optString("note", "")
            show(DELIVERY_CHANNEL, e, "Delivery failed: order #$no", listOf(rider, note).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { "Open the delivery board" }, MainActivity.SCREEN_DELIVERY)
        } else {
            val due = e.optDouble("due", 0.0)
            if (due <= 0.0) return                     // nothing to collect, so nothing to do
            val rs = if (due == Math.floor(due)) due.toLong().toString() else String.format("%.2f", due)
            show(DELIVERY_CHANNEL, e, "Order #$no delivered", (if (rider.isNotEmpty()) "$rider: " else "") + "collect Rs $rs", MainActivity.SCREEN_DELIVERY)
        }
    }

    private fun handleReady(prefs: Prefs, e: JSONObject) {
        if (prefs.alertMode == Prefs.MODE_OFF) return
        val mine = e.optString("created_by").isNotEmpty() && e.optString("created_by") == prefs.sessionName
        if (prefs.alertMode == Prefs.MODE_MINE && !mine) return
        val no = e.optInt("order_no")
        val table = e.optString("table_no", "")
        val where = when {
            table.isNotEmpty() && table != "null" -> "Table $table"
            e.optString("order_type") == "delivery" -> "Delivery"
            e.optString("order_type") == "takeaway" -> "Takeaway"
            else -> "Dine-in"
        }
        val title = "Order #$no is ready"
        val builder = builder(READY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(where)
            .setContentIntent(openApp(MainActivity.SCREEN_ORDERS))
            .setAutoCancel(true)
        applyAlertSound(builder)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1000 + (e.optInt("id") % 100000), builder.build())
    }

    private fun notifyStopped() {
        val builder = builder(READY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Order alerts stopped")
            .setContentText("Open the app and log in again to get them back")
            .setContentIntent(openApp())
            .setAutoCancel(true)
        getSystemService(NotificationManager::class.java)?.notify(STOPPED_ID, builder.build())
    }

    @Suppress("DEPRECATION")
    private fun finishSelf() {
        running = false
        stopForeground(true)
        stopSelf()
    }

    private fun foregroundNotification(): Notification {
        return builder(WAITING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pizza POS")
            .setContentText("Waiting for order alerts")
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()
    }

    /** Opens the app; with a screen name (Step 28) it opens that screen: today's orders, or the delivery board. */
    private fun openApp(screen: String? = null): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (screen != null) i.putExtra(MainActivity.EXTRA_SCREEN, screen)
        // a different request code per screen, so one notification's screen never replaces another's
        val code = when (screen) {
            MainActivity.SCREEN_ORDERS -> 1
            MainActivity.SCREEN_DELIVERY -> 2
            else -> 0
        }
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("DEPRECATION")
    private fun builder(channel: String): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channel) else Notification.Builder(this)
    }

    @Suppress("DEPRECATION")
    private fun applyAlertSound(b: Notification.Builder) {
        // Android 8+ takes sound and vibration from the channel; older Android needs it on the notification
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setDefaults(Notification.DEFAULT_ALL)
            b.setPriority(Notification.PRIORITY_HIGH)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val ready = NotificationChannel(READY_CHANNEL, "Orders ready", NotificationManager.IMPORTANCE_HIGH)
        ready.enableVibration(true)
        ready.description = "A sound and vibration when an order is ready"
        manager.createNotificationChannel(ready)
        val fresh = NotificationChannel(NEW_CHANNEL, "New orders", NotificationManager.IMPORTANCE_HIGH)
        fresh.enableVibration(true)
        fresh.description = "A sound and vibration when somebody else places an order"
        manager.createNotificationChannel(fresh)
        val delivery = NotificationChannel(DELIVERY_CHANNEL, "Delivery news", NotificationManager.IMPORTANCE_HIGH)
        delivery.enableVibration(true)
        delivery.description = "A failed delivery, or a rider's cash that is waiting to be collected"
        manager.createNotificationChannel(delivery)
        val waiting = NotificationChannel(WAITING_CHANNEL, "Alerts are running", NotificationManager.IMPORTANCE_LOW)
        waiting.description = "Shows that the phone is waiting for order alerts"
        manager.createNotificationChannel(waiting)
    }

    companion object {
        private const val READY_CHANNEL = "order_ready"
        private const val NEW_CHANNEL = "order_new"
        private const val DELIVERY_CHANNEL = "delivery_news"
        private const val WAITING_CHANNEL = "alerts_running"
        private const val FOREGROUND_ID = 41
        private const val STOPPED_ID = 42

        /** Alerts make sense only for a logged-in person (not kitchen) while a server address is known and at least one kind of alert is on. */
        fun wanted(prefs: Prefs): Boolean {
            return (prefs.alertMode != Prefs.MODE_OFF || prefs.extraAlertsOn()) &&
                prefs.sessionToken.isNotEmpty() &&
                prefs.sessionRole != "kitchen" &&
                Prefs.normalizeServer(prefs.server).isNotEmpty()
        }

        /** Starts or stops the service to match the current settings. Safe to call as often as you like. */
        fun sync(context: Context) {
            val intent = Intent(context, AlertService::class.java)
            try {
                if (wanted(Prefs(context))) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: Exception) {
                // Android refused to start it from the background; it starts the next time the app is opened
            }
        }
    }
}
