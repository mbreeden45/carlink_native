package com.carlink.usb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Sends the adapter the same web-config request the user's iOS Shortcut sends
 * (`POST /cgi-bin/server.cgi`, `cmd=set item=Udisk val=1`, signed), which is what makes an adapter
 * that powered up without presenting its CarPlay USB interface switch over and appear on USB.
 *
 * Requires the head unit to be joined to the adapter's Wi-Fi. That network has no internet, so
 * Android keeps cellular as the default route; this request is bound explicitly to the Wi-Fi
 * network so it reaches 192.168.43.1 while everything else stays on cellular.
 *
 * The signing secret comes from the build (BuildConfig.BOX_API_SECRET, from the CARLINK_BOX_SECRET
 * CI secret), never from source. Without it this is a no-op.
 */
object AdapterWifiKick {
    const val BOX_HOST = "192.168.43.1"
    private const val PATH = "/cgi-bin/server.cgi"
    private const val TIMEOUT_MS = 3_000

    enum class Outcome { ACCEPTED, REJECTED, FAILED, NO_WIFI, NO_SECRET }

    data class SignedForm(
        val body: String,
        val signedText: String,
    )

    /**
     * Build the form exactly as the Shortcut does: sign = MD5(query + secret) with
     * query = "cmd=set&item=Udisk&ts=<ts>&val=1" (fields in that order), lowercase hex.
     * [separator] goes between query and secret ("" normally; " " as a fallback variant, since the
     * Shortcut's text block cannot show whether its two tokens are separated by a space).
     */
    fun buildForm(
        tsMs: Long,
        secret: String,
        separator: String = "",
    ): SignedForm {
        val query = "cmd=set&item=Udisk&ts=$tsMs&val=1"
        val toSign = query + separator + secret
        val sign = md5Hex(toSign)
        return SignedForm(body = "cmd=set&item=Udisk&val=1&ts=$tsMs&sign=$sign", signedText = toSign)
    }

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** The Shortcut treats a reply containing ":0" as success (e.g. {"err":0}). */
    fun isSuccess(reply: String?): Boolean = reply != null && reply.contains(":0")

    /** The Wi-Fi network the head unit is joined to, if any. */
    @Suppress("DEPRECATION") // allNetworks: fine for a one-shot lookup on API 32+
    fun findWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Send the request. Blocking (call from IO). Returns a one-line outcome for the log; never
     * contains the secret or the signature.
     */
    fun send(
        context: Context,
        secret: String,
        log: (String) -> Unit,
    ): Outcome {
        if (secret.isBlank()) {
            log("[BOX_KICK] Not configured (no CARLINK_BOX_SECRET in this build) - skipping")
            return Outcome.NO_SECRET
        }
        val wifi = findWifiNetwork(context)
        if (wifi == null) {
            log("[BOX_KICK] Head unit is not on any Wi-Fi network - join the adapter's Wi-Fi in car settings")
            return Outcome.NO_WIFI
        }
        for ((i, separator) in listOf("", " ").withIndex()) {
            val form = buildForm(System.currentTimeMillis(), secret, separator)
            val reply =
                try {
                    post(wifi, form.body)
                } catch (e: Exception) {
                    log("[BOX_KICK] Request to $BOX_HOST failed: ${e.javaClass.simpleName}: ${e.message}")
                    return Outcome.FAILED // network-level failure: a second signature variant will not help
                }
            val ok = isSuccess(reply)
            log("[BOX_KICK] Sent Udisk=1 to $BOX_HOST (variant ${i + 1}) -> ${if (ok) "accepted" else "rejected"}: ${reply.take(120)}")
            if (ok) return Outcome.ACCEPTED
        }
        return Outcome.REJECTED
    }

    private fun post(
        network: Network,
        body: String,
    ): String {
        val conn = network.openConnection(URL("http://$BOX_HOST$PATH")) as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() }?.trim() ?: "HTTP ${conn.responseCode}"
        } finally {
            conn.disconnect()
        }
    }
}
