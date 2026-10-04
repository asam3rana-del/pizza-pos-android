package com.pizzashop.pos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Sends ESC/POS bytes to a paired Bluetooth (serial / SPP) thermal printer. */
object BtPrinter {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    /** Printers already paired in the phone's Bluetooth settings. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        return try {
            val set = adapter(context)?.bondedDevices ?: return emptyList()
            set.toList().sortedBy { nameOf(it) }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun nameOf(device: BluetoothDevice): String {
        return try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
    }

    /** The same commands the server sends to a network printer: a black/white picture, 128 rows at a time, then feed and cut. */
    fun rasterJob(width: Int, height: Int, data: ByteArray, beep: Boolean): ByteArray {
        val rowBytes = width / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1b, 0x40))
        if (beep) out.write(byteArrayOf(0x1b, 0x42, 2, 2))
        var y = 0
        while (y < height) {
            val h = minOf(128, height - y)
            out.write(
                byteArrayOf(
                    0x1d, 0x76, 0x30, 0,
                    (rowBytes and 255).toByte(), (rowBytes shr 8).toByte(),
                    (h and 255).toByte(), (h shr 8).toByte()
                )
            )
            out.write(data, y * rowBytes, h * rowBytes)
            y += h
        }
        out.write(byteArrayOf(0x1b, 0x64, 4, 0x1d, 0x56, 0x42, 0))
        return out.toByteArray()
    }

    /** Plain English text, used for the "Test print" button. */
    fun textJob(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1b, 0x40))
        for (line in lines) out.write((line + "\n").toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x1b, 0x64, 4, 0x1d, 0x56, 0x42, 0))
        return out.toByteArray()
    }

    /** Connects, sends in small pieces (cheap printers have tiny buffers), waits for the paper to come out, disconnects. */
    @SuppressLint("MissingPermission")
    fun send(context: Context, address: String, bytes: ByteArray) {
        if (address.isEmpty()) throw IllegalStateException("No Bluetooth printer is chosen")
        if (!hasPermission(context)) throw IllegalStateException("Allow 'Nearby devices' for this app, then try again")
        val bt = adapter(context) ?: throw IllegalStateException("This phone has no Bluetooth")
        if (!bt.isEnabled) throw IllegalStateException("Bluetooth is switched off")
        val device = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("The printer address is not valid. Choose the printer again")
        }
        bt.cancelDiscovery()
        val socket = connect(device)
        try {
            val os = socket.outputStream
            var i = 0
            while (i < bytes.size) {
                val n = minOf(512, bytes.size - i)
                os.write(bytes, i, n)
                os.flush()
                i += n
                Thread.sleep(25)
            }
            Thread.sleep(minOf(4000L, 500L + bytes.size / 25))
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // already closed
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        for (secure in listOf(true, false)) {
            var opened: BluetoothSocket? = null
            try {
                val s = if (secure) device.createRfcommSocketToServiceRecord(SPP) else device.createInsecureRfcommSocketToServiceRecord(SPP)
                opened = s
                s.connect()
                return s
            } catch (e: Exception) {
                try {
                    opened?.close()
                } catch (x: Exception) {
                    // ignore
                }
            }
        }
        throw IllegalStateException("Cannot connect to the printer. Is it switched on and close to the phone?")
    }
}
