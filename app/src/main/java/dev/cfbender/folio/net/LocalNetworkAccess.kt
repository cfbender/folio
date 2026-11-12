package dev.cfbender.folio.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Android 17 drops an app's traffic to the local network until the user allows it, and the only
 * sign is a connection that times out. A self-hosted library usually lives on that network, so
 * this asks for the permission the first time a request is about to go to a local address, and
 * says what happened when a request times out without it.
 *
 * [granted] reports whether the app may use the local network; [resolve] looks up a host's
 * addresses, which Android allows without the permission.
 */
class LocalNetworkAccess(
    private val granted: () -> Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : Interceptor {

    constructor(context: Context) : this(granted = {
        Build.VERSION.SDK_INT < FIRST_ENFORCING_SDK ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    })

    /**
     * Shows the system's permission prompt and returns once the user has answered it. Set only
     * while a screen that can show the prompt is visible.
     */
    @Volatile var prompt: (suspend () -> Unit)? = null

    private val asking = Mutex()
    private var asked = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (granted() || !isLocal(request.url.host)) return chain.proceed(request)
        runBlocking { askOnce() }
        try {
            return chain.proceed(request)
        } catch (e: SocketTimeoutException) {
            if (granted()) throw e
            throw IOException(BLOCKED, e)
        }
    }

    /** One prompt per run of the app, however many requests are waiting on the answer. */
    private suspend fun askOnce() = asking.withLock {
        if (asked || granted()) return
        val show = prompt ?: return
        asked = true
        show()
    }

    private fun isLocal(host: String): Boolean =
        try {
            resolve(host).any(::isLocalNetworkAddress)
        } catch (_: IOException) {
            false // The request itself will report that the name does not resolve.
        }

    companion object {
        // Only checked and requested on Android 17 and later, where it exists.
        @SuppressLint("InlinedApi")
        const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
        const val BLOCKED =
            "Android is blocking Folio from your local network. Allow Nearby devices for Folio in Android's app settings, then try again."
        private const val FIRST_ENFORCING_SDK = 37
    }
}

/**
 * Whether Android counts [address] as part of the local network:
 * https://developer.android.com/privacy-and-security/local-network-definition.
 * A public IPv6 address on the same link also counts there, and is not recognised here.
 */
internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress) return false
    if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
    val bytes = address.address
    val first = bytes[0].toInt() and 0xFF
    return if (bytes.size == 4) first == 100 && (bytes[1].toInt() and 0xC0) == 64 // 100.64.0.0/10
    else (first and 0xFE) == 0xFC // fc00::/7
}
