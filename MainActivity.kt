package com.satrughannapurna.pos

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    @Volatile private var socket: BluetoothSocket? = null
    private val prefs by lazy { getSharedPreferences("mt580p", MODE_PRIVATE) }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val req = 501

    override fun onCreate(state: Bundle?) { super.onCreate(state)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = WebViewClient()
            addJavascriptInterface(PrinterBridge(), "AndroidPrinter")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        requestBtPermissionIfNeeded()
    }

    private fun requestBtPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), req)
        } else {
            triggerSavedPrinterReconnect()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == req && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            triggerSavedPrinterReconnect()
        }
    }

    private fun triggerSavedPrinterReconnect() {
        web.postDelayed({
            try {
                val js = "window.satrughanNativeAutoReconnect && window.satrughanNativeAutoReconnect()"
                web.evaluateJavascript(js, null)
            } catch (_: Exception) {}
        }, 250)
    }

    private fun pairedPrinter(name: String, address: String): BluetoothDevice? {
        val a = adapter ?: return null
        if (!a.isEnabled) return null
        val bonded = a.bondedDevices
        if (address.isNotBlank()) bonded.firstOrNull { it.address.equals(address, true) }?.let { return it }
        bonded.firstOrNull { it.name.equals(name, true) }?.let { return it }
        bonded.firstOrNull { it.name?.contains("MT580P", true) == true }?.let { return it }
        return null
    }

    private fun notifyJs(connected: Boolean, name: String) {
        runOnUiThread {
            val js = "window.satrughanNativePrinterStatus && window.satrughanNativePrinterStatus(${connected},${org.json.JSONObject.quote(name)})"
            web.evaluateJavascript(js, null)
        }
    }

    private fun connectDevice(device: BluetoothDevice, name: String): Boolean {
        var lastError: Exception? = null
        repeat(3) {
            try {
                socket?.close()
                try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
                val s = try {
                    device.createRfcommSocketToServiceRecord(spp)
                } catch (_: Exception) {
                    device.createInsecureRfcommSocketToServiceRecord(spp)
                }
                try {
                    s.connect()
                } catch (first: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    // Some MT580P firmware/variants accept insecure RFCOMM only.
                    val fallback = device.createInsecureRfcommSocketToServiceRecord(spp)
                    fallback.connect()
                    socket = fallback
                    prefs.edit().putString("name", device.name ?: name).putString("address", device.address).apply()
                    notifyJs(true, device.name ?: name)
                    return true
                }
                socket = s
                prefs.edit().putString("name", device.name ?: name).putString("address", device.address).apply()
                notifyJs(true, device.name ?: name)
                return true
            } catch (e: Exception) {
                lastError = e
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                try { Thread.sleep(350) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
        notifyJs(false, name)
        return false
    }

    inner class PrinterBridge {
        @JavascriptInterface fun isConnected(): Boolean = socket?.isConnected == true

        @JavascriptInterface fun connectSaved(name: String?, address: String?): Boolean {
            val savedName = prefs.getString("name", null) ?: name ?: "MT580P"
            val savedAddress = prefs.getString("address", null) ?: address.orEmpty()
            val d = try { pairedPrinter(savedName, savedAddress) } catch (_: SecurityException) { null }
                ?: return false
            executor.execute { connectDevice(d, savedName) }
            return true
        }

        @JavascriptInterface fun connect(): Boolean {
            val savedName = prefs.getString("name", null) ?: "MT580P"
            val savedAddress = prefs.getString("address", null) ?: ""
            val d = try { pairedPrinter(savedName, savedAddress) } catch (_: SecurityException) { null } ?: return false
            executor.execute { connectDevice(d, savedName) }
            return true
        }

        @JavascriptInterface fun savePrinter(name: String?, address: String?): Boolean {
            prefs.edit().putString("name", name ?: "MT580P").putString("address", address ?: "").apply()
            return true
        }

        @JavascriptInterface fun disconnect() {
            executor.execute { try { socket?.close() } catch (_: Exception) {}; socket = null; notifyJs(false, prefs.getString("name", "MT580P") ?: "MT580P") }
        }

        @JavascriptInterface fun print(base64: String?): Boolean {
            if (socket?.isConnected != true || base64.isNullOrBlank()) return false
            return try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                executor.execute { try { socket?.outputStream?.write(bytes); socket?.outputStream?.flush() } catch (_: IOException) { socket = null; notifyJs(false, prefs.getString("name", "MT580P") ?: "MT580P") } }
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun printerName(): String = prefs.getString("name", "MT580P") ?: "MT580P"
        @JavascriptInterface fun printerAddress(): String = prefs.getString("address", "") ?: ""
    }

    override fun onDestroy() { try { socket?.close() } catch (_: Exception) {}; executor.shutdownNow(); web.removeJavascriptInterface("AndroidPrinter"); super.onDestroy() }
}
