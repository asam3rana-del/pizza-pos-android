package com.pizzashop.pos

import android.content.Context
import android.content.SharedPreferences

/** Everything this phone remembers: the server address, its Bluetooth printers and a screen option. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("pizza_pos", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(value) {
            sp.edit().putString("server", value).apply()
        }

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(value) {
            sp.edit().putBoolean("keep_awake", value).apply()
        }

    /** Order-ready alerts: MODE_OFF, MODE_MINE (orders I took) or MODE_ALL. */
    var alertMode: String
        get() = sp.getString("alert_mode", MODE_MINE) ?: MODE_MINE
        set(value) {
            sp.edit().putString("alert_mode", value).apply()
        }

    /** New-order and delivery alerts (Step 23): EXTRA_AUTO (on for cashier and manager, off for waiter), EXTRA_ON or EXTRA_OFF. */
    var extraAlerts: String
        get() = sp.getString("extra_alerts", EXTRA_AUTO) ?: EXTRA_AUTO
        set(value) {
            sp.edit().putString("extra_alerts", value).apply()
        }

    /** Whether this phone should hear about new orders and delivery news, for the person who is logged in now. */
    fun extraAlertsOn(): Boolean = when (extraAlerts) {
        EXTRA_ON -> true
        EXTRA_OFF -> false
        else -> sessionRole == "cashier" || sessionRole == "manager"
    }

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(value) {
            sp.edit().putBoolean("asked_notifications", value).apply()
        }

    // The screens hand over the login so the background alert check can ask the server "is any order ready?".
    // It stays in this app's private storage and is removed at log out.
    val sessionToken: String get() = sp.getString("session_token", "") ?: ""
    val sessionName: String get() = sp.getString("session_name", "") ?: ""
    val sessionRole: String get() = sp.getString("session_role", "") ?: ""

    fun setSession(token: String, name: String, role: String) {
        sp.edit().putString("session_token", token).putString("session_name", name).putString("session_role", role).apply()
    }

    fun clearSession() {
        sp.edit().remove("session_token").remove("session_name").remove("session_role").apply()
    }

    // role is "receipt" (customer bill) or "kitchen"
    fun printerAddress(role: String): String = sp.getString("bt_${role}_addr", "") ?: ""

    fun printerName(role: String): String = sp.getString("bt_${role}_name", "") ?: ""

    fun printerDots(role: String): Int = sp.getInt("bt_${role}_dots", 384)

    fun setPrinter(role: String, address: String, name: String, dots: Int) {
        sp.edit()
            .putString("bt_${role}_addr", address)
            .putString("bt_${role}_name", name)
            .putInt("bt_${role}_dots", dots)
            .apply()
    }

    companion object {
        const val ROLE_RECEIPT = "receipt"
        const val ROLE_KITCHEN = "kitchen"

        const val MODE_OFF = "off"
        const val MODE_MINE = "mine"
        const val MODE_ALL = "all"

        const val EXTRA_AUTO = "auto"
        const val EXTRA_ON = "on"
        const val EXTRA_OFF = "off"

        /** "192.168.1.10:3000" or "http://192.168.1.10:3000/" becomes "http://192.168.1.10:3000". Empty text when unusable. */
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.trimEnd('/')
            val rest = s.substringAfter("://")
            if (rest.isEmpty() || rest.contains(' ') || rest.contains('/')) return ""
            return s
        }
    }
}
