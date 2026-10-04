package com.pizzashop.pos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Server address, Bluetooth printers (one for bills, one for the kitchen) and a screen option. Built in code, no layout files. */
class SettingsActivity : Activity() {
    private class Choice(val address: String, val label: String)
    private class Row(val devices: Spinner, val paper: Spinner)

    private lateinit var prefs: Prefs
    private lateinit var serverInput: EditText
    private lateinit var keepAwake: CheckBox
    private lateinit var permissionNote: TextView
    private val rows = LinkedHashMap<String, Row>()
    private var choices: List<Choice> = listOf(Choice("", "(no printer)"))
    private val worker = Executors.newSingleThreadExecutor()
    private val paperLabels = listOf("58 mm paper (384 dots)", "80 mm paper (576 dots)")
    private val paperDots = listOf(384, 576)
    private val notPaired = " (not paired now)"
    private lateinit var alertSpinner: Spinner
    private val alertLabels = listOf("Off", "Only orders I took", "All orders")
    private val alertValues = listOf(Prefs.MODE_OFF, Prefs.MODE_MINE, Prefs.MODE_ALL)
    private lateinit var extraSpinner: Spinner
    private val extraLabels = listOf("Automatic (cashier and manager: on, waiter: off)", "On", "Off")
    private val extraValues = listOf(Prefs.EXTRA_AUTO, Prefs.EXTRA_ON, Prefs.EXTRA_OFF)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(16), dp(16), dp(32))

        box.addView(title("Phone settings"))
        box.addView(caption("POS server address"))
        serverInput = EditText(this)
        serverInput.hint = "192.168.1.10:3000"
        serverInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        serverInput.setSingleLine(true)
        serverInput.setText(prefs.server)
        box.addView(serverInput)
        box.addView(button("Find server automatically") { findServer() })
        box.addView(button("Test connection") { testServer() })

        box.addView(title("Bluetooth printers"))
        box.addView(note("Pair the printer first in the phone's Bluetooth settings (the PIN is usually 0000 or 1234), then choose it here. Slips are printed by this phone."))
        permissionNote = note("Allow 'Nearby devices' for this app to see your paired printers.")
        box.addView(permissionNote)
        box.addView(button("Open Bluetooth settings (pair a printer)") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        addPrinterRow(box, Prefs.ROLE_RECEIPT, "Bill printer (customer bill)")
        addPrinterRow(box, Prefs.ROLE_KITCHEN, "Kitchen printer (kitchen slips)")

        box.addView(title("Order ready alerts"))
        box.addView(note("The phone makes a sound and vibrates when the kitchen marks an order ready, even when the app is closed (you must stay logged in)."))
        alertSpinner = Spinner(this)
        alertSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, alertLabels)
        alertSpinner.setSelection(alertValues.indexOf(prefs.alertMode).coerceAtLeast(0))
        box.addView(alertSpinner)
        box.addView(title("New order and delivery alerts"))
        box.addView(note("A sound when somebody else places an order, when a delivery fails, or when a rider's cash is waiting to be collected. Orders you took yourself do not alert you."))
        extraSpinner = Spinner(this)
        extraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, extraLabels)
        extraSpinner.setSelection(extraValues.indexOf(prefs.extraAlerts).coerceAtLeast(0))
        box.addView(extraSpinner)
        box.addView(note("Alerts start again by themselves after the phone restarts, as long as you did not log out. Tap an alert to open today's orders (or the delivery board for delivery news)."))
        box.addView(note("Some phones (Xiaomi, Oppo, Vivo, Samsung...) stop background apps to save battery. If alerts do not come, choose this app in the list that opens and set it to 'Not optimized' / 'Unrestricted'."))
        box.addView(button("Battery settings (keep alerts working)") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("Open the phone settings > Apps > Pizza POS > Battery")
            }
        })

        box.addView(title("Screen"))
        keepAwake = CheckBox(this)
        keepAwake.text = "Keep the screen on while the app is open"
        keepAwake.isChecked = prefs.keepAwake
        box.addView(keepAwake)

        box.addView(button("Save") { save() })
        box.addView(button("Close without saving") { finish() })

        val scroll = ScrollView(this)
        scroll.addView(box)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !BtPrinter.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7)
        }
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()                                 // after coming back from the Bluetooth settings
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDevices()
    }

    private fun addPrinterRow(parent: LinearLayout, role: String, heading: String) {
        parent.addView(caption(heading))
        val devices = Spinner(this)
        val paper = Spinner(this)
        paper.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paperLabels)
        paper.setSelection(if (prefs.printerDots(role) >= 576) 1 else 0)
        parent.addView(devices)
        parent.addView(paper)
        parent.addView(button("Test print on this printer") { testPrint(role) })
        rows[role] = Row(devices, paper)
    }

    /** Fills both printer lists with the paired devices, keeping whatever is selected right now. */
    private fun refreshDevices() {
        permissionNote.visibility = if (BtPrinter.hasPermission(this)) android.view.View.GONE else android.view.View.VISIBLE

        val keep = HashMap<String, String>()
        for ((role, row) in rows) {
            val pos = row.devices.selectedItemPosition
            keep[role] = if (row.devices.adapter != null && pos >= 0 && pos < choices.size) choices[pos].address else prefs.printerAddress(role)
        }

        val list = ArrayList<Choice>()
        list.add(Choice("", "(no printer)"))
        for (d in BtPrinter.pairedDevices(this)) list.add(Choice(d.address, BtPrinter.nameOf(d)))
        for (role in rows.keys) {
            val saved = keep[role] ?: ""
            if (saved.isNotEmpty() && list.none { it.address == saved }) {
                list.add(Choice(saved, prefs.printerName(role).ifEmpty { saved } + notPaired))
            }
        }
        choices = list

        val labels = list.map { it.label }
        for ((role, row) in rows) {
            row.devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val wanted = keep[role] ?: ""
            val index = list.indexOfFirst { it.address == wanted }
            row.devices.setSelection(if (index >= 0) index else 0)
        }
    }

    private fun selectedChoice(role: String): Choice {
        val row = rows[role] ?: return choices[0]
        val pos = row.devices.selectedItemPosition
        return if (pos >= 0 && pos < choices.size) choices[pos] else choices[0]
    }

    private fun save() {
        val raw = serverInput.text.toString()
        val server = Prefs.normalizeServer(raw)
        if (raw.isNotBlank() && server.isEmpty()) {
            toast("The server address is not valid. Example: 192.168.1.10:3000")
            return
        }
        prefs.server = server
        prefs.keepAwake = keepAwake.isChecked
        prefs.alertMode = alertValues[alertSpinner.selectedItemPosition.coerceIn(0, alertValues.size - 1)]
        prefs.extraAlerts = extraValues[extraSpinner.selectedItemPosition.coerceIn(0, extraValues.size - 1)]
        if ((prefs.alertMode != Prefs.MODE_OFF || prefs.extraAlerts != Prefs.EXTRA_OFF) && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)     // the answer is not needed here; Android remembers it
        }
        AlertService.sync(this)
        for ((role, row) in rows) {
            val c = selectedChoice(role)
            val dots = paperDots[row.paper.selectedItemPosition.coerceIn(0, 1)]
            val name = if (c.address.isEmpty()) "" else c.label.removeSuffix(notPaired)
            prefs.setPrinter(role, c.address, name, dots)
        }
        toast("Saved")
        finish()
    }

    /** Shouts on the Wi-Fi and fills in the address of the POS server that answers. */
    private fun findServer() {
        toast("Looking for the server...")
        worker.execute {
            val list = DiscoveryClient.find()
            runOnUiThread {
                when {
                    list.isEmpty() -> toast("No server found. Is the phone on the shop Wi-Fi and the POS server running? You can still type the address.")
                    list.size == 1 -> {
                        serverInput.setText(list[0].address)
                        toast("Found " + list[0].label + ". Press Save.")
                    }
                    else -> {
                        val labels = list.map { it.label }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Which server?")
                            .setItems(labels) { _, which -> serverInput.setText(list[which].address) }
                            .show()
                    }
                }
            }
        }
    }

    private fun testServer() {
        val base = Prefs.normalizeServer(serverInput.text.toString())
        if (base.isEmpty()) {
            toast("Type the server address first, for example 192.168.1.10:3000")
            return
        }
        toast("Testing...")
        worker.execute {
            val message = try {
                val c = URL("$base/api/auth/status").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                if (body.contains("setupNeeded")) "Connected to the POS server" else "That address answers, but it is not the POS server"
            } catch (e: Exception) {
                "Cannot connect: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { toast(message) }
        }
    }

    private fun testPrint(role: String) {
        val c = selectedChoice(role)
        if (c.address.isEmpty()) {
            toast("Choose a printer first")
            return
        }
        val which = if (role == Prefs.ROLE_KITCHEN) "KITCHEN printer" else "BILL printer"
        val job = BtPrinter.textJob(listOf("", "PIZZA POS", "Bluetooth test print", which, c.label.removeSuffix(notPaired), "", "If you can read this,", "the printer works.", ""))
        toast("Printing...")
        worker.execute {
            val message = try {
                BtPrinter.send(this, c.address, job)
                "Sent to the printer"
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread { toast(message) }
        }
    }

    // ---- tiny helpers to build the screen without layout files ----
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun title(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 20f
        v.setTypeface(null, Typeface.BOLD)
        v.setPadding(0, dp(20), 0, dp(6))
        return v
    }

    private fun caption(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 15f
        v.setPadding(0, dp(10), 0, dp(2))
        return v
    }

    private fun note(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.textSize = 13f
        v.setPadding(0, 0, 0, dp(6))
        return v
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setAllCaps(false)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
