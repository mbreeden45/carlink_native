package com.carlink.usb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock

/**
 * Joins the head unit to the adapter's Wi-Fi from inside the app, so the Wi-Fi wake request
 * ([AdapterWifiKick]) can reach the adapter even when the car does not rejoin that network by itself.
 *
 * Field finding (GM AAOS, build 1008): the car does not auto-join the adapter's network at power-up,
 * and its "no internet" notification offers no "stay connected / always" choice that would change
 * that. This uses Android's app-requested local network API ([WifiNetworkSpecifier]): Android shows
 * a one-time "connect to <ssid>?" approval and remembers it for this app and access point, so later
 * joins should be silent. The network is local-only; the car keeps cellular for everything else.
 *
 * The passphrase comes from the build (CARLINK_BOX_WIFI_PSK CI secret); without it this is a no-op.
 */
class AdapterWifiLink(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val log: (String) -> Unit,
    private val onJoined: (Network) -> Unit,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The adapter's network while this app holds it, else null. */
    @Volatile var network: Network? = null
        private set

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var nextRequestAllowedMs = 0L

    val isConfigured: Boolean get() = ssid.isNotBlank() && passphrase.isNotBlank()

    /** Ask Android to join the adapter's Wi-Fi, unless a request is already pending or recently failed. */
    @Synchronized
    fun ensureRequested() {
        if (!isConfigured || callback != null) return
        val now = SystemClock.uptimeMillis()
        if (now < nextRequestAllowedMs) return
        val spec =
            WifiNetworkSpecifier
                .Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // the adapter has none
                .setNetworkSpecifier(spec)
                .build()
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    network = n
                    log("[BOX_WIFI] Joined \"$ssid\" (app-managed)")
                    onJoined(n)
                }

                override fun onLost(n: Network) {
                    if (network == n) network = null
                    log("[BOX_WIFI] Lost \"$ssid\"")
                }

                override fun onUnavailable() {
                    log("[BOX_WIFI] \"$ssid\" not found or not approved - retrying in ${RETRY_MS / 1000}s")
                    synchronized(this@AdapterWifiLink) {
                        callback = null
                        nextRequestAllowedMs = SystemClock.uptimeMillis() + RETRY_MS
                    }
                }
            }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
            log("[BOX_WIFI] Asking Android to join \"$ssid\" (first time only: approve the prompt)")
        } catch (e: Exception) {
            nextRequestAllowedMs = now + RETRY_MS
            log("[BOX_WIFI] Join request refused: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Drop the app's hold on the adapter's Wi-Fi (e.g. once the adapter is connected over USB). */
    @Synchronized
    fun release() {
        val cb = callback ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        callback = null
        network = null
        log("[BOX_WIFI] Released \"$ssid\"")
    }

    companion object {
        /** Gap before asking again after "not found / not approved", so prompts never stack. */
        const val RETRY_MS = 30_000L
    }
}
