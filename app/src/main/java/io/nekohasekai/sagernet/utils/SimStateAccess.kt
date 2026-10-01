package io.nekohasekai.sagernet.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.CoreLog

/**
 * What reading the active data SIM takes, and which SIM is the one currently carrying mobile data.
 *
 * [read] mirrors [WifiStateAccess.read]: it reports the SIM behind the default network and nothing
 * when the default network is not cellular. [status] reports the permission the query needs, the way
 * the Wi-Fi rules report missing location access, so the settings screen can explain itself instead
 * of silently binding nothing.
 *
 * The slot number is 1-based (TelephonyManager's `simSlotIndex` is 0-based) and the carrier is the
 * MCC+MNC of the subscription, the only operator identifier that survives a switch of the phone's
 * language and a re-issued subscription id.
 */
object SimStateAccess {

    enum class Status { OK, NEED_PHONE_STATE, UNAVAILABLE }

    /** Slot (1-based), MCC+MNC and the readable operator name of the active data SIM. */
    data class State(val slot: Int, val carrier: String, val carrierName: String) {
        val available: Boolean get() = slot > 0

        companion object {
            val NONE = State(-1, "", "")
        }
    }

    fun status(context: Context): Status {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return Status.NEED_PHONE_STATE
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return Status.UNAVAILABLE
        return try {
            // An empty list is a device without a readable SIM (tablet, or a ROM hiding it): not an error to fix.
            if (hasActiveSubscription(manager)) Status.OK else Status.UNAVAILABLE
        } catch (e: SecurityException) {
            Logs.w(e)
            Status.NEED_PHONE_STATE
        } catch (e: Throwable) {
            Logs.w(e)
            Status.UNAVAILABLE
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasActiveSubscription(manager: SubscriptionManager): Boolean = try {
        @Suppress("DEPRECATION")
        manager.activeSubscriptionInfoList?.isNotEmpty() == true
        // The typed overload needs API 30; subscriptionId is fine since 22, so the deprecated list is the portable one.
    } catch (e: SecurityException) {
        throw e
    } catch (e: Throwable) {
        false
    }

    /**
     * The SIM carrying mobile data right now, or [State.NONE] when the default network is not cellular
     * or the values cannot be read.
     *
     * `getActiveSubscriptionInfoForSimSlotIndex` needs `READ_PHONE_STATE`; on Android 11 and newer a
     * carrier-privileged app would need `READ_PHONE_NUMBERS` as well, so a failure to list is answered
     * with "no SIM" rather than an exception, and the caller falls back to the group's front proxy.
     */
    fun read(context: Context): State {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) {
            trace("read: no READ_PHONE_STATE")
            return State.NONE
        }
        val manager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        if (manager == null) {
            trace("read: no SubscriptionManager")
            return State.NONE
        }
        val slot = dataSlot(context, manager)
        if (slot == null) {
            trace("read: dataSlot() = null")
            return State.NONE
        }
        val info = subscriptionForSlot(manager, slot)
        if (info == null) {
            trace("read: no subscription for slot $slot")
            return State.NONE
        }
        val carrier = if (info.mccString.isNullOrEmpty() || info.mncString.isNullOrEmpty()) {
            ""
        } else {
            info.mccString.orEmpty() + info.mncString.orEmpty()
        }
        return State(slot + 1, carrier, info.carrierName?.toString().orEmpty())
    }

    /** See [Monitor.trace]: core.log directly, so log_level cannot hide the reason a SIM went unread. */
    private fun trace(message: String) {
        runCatching { CoreLog.write("[Debug] [SimStateAccess] $message") }
    }

    /** The 0-based slot of the subscription on mobile data, or null. */
    private fun dataSlot(context: Context, manager: SubscriptionManager): Int? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        if (Build.VERSION.SDK_INT >= 24) {
            val subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                val info = subscriptionInfoForId(manager, subscriptionId)
                if (info != null) return info.simSlotIndex
            }
        }
        // Fall back to the subscription of the SIM the TelephonyManager itself speaks for.
        if (Build.VERSION.SDK_INT >= 24) {
            val fromManager = SubscriptionManager.getSlotIndex(telephony.subscriptionId)
            if (fromManager != SubscriptionManager.INVALID_SIM_SLOT_INDEX) return fromManager
        }
        return null
    }

    private fun subscriptionInfoForId(manager: SubscriptionManager, subscriptionId: Int): SubscriptionInfo? = try {
        @Suppress("DEPRECATION")
        manager.getActiveSubscriptionInfo(subscriptionId)
    } catch (e: SecurityException) {
        Logs.w(e)
        null
    } catch (e: Throwable) {
        null
    }

    private fun subscriptionForSlot(manager: SubscriptionManager, slotIndex: Int): SubscriptionInfo? = try {
        @Suppress("DEPRECATION")
        manager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
    } catch (e: SecurityException) {
        Logs.w(e)
        null
    } catch (e: Throwable) {
        null
    }

    /**
     * Calls [onChange] when the SIM behind mobile data changes while the proxy runs.
     *
     * The connectivity callback is the source that is watched, exactly like [WifiStateAccess.Monitor]
     * watches the Wi-Fi transport: switching the default data SIM brings up a new cellular network,
     * which always reaches a registered [ConnectivityManager.NetworkCallback]. The telephony broadcast
     * is kept as a second, earlier signal, because it fires a moment before the new network is usable.
     * It has to be registered exported: the broadcast comes from the phone process, not from this app,
     * and Android 13+ drops anything not from this app from a NOT_EXPORTED receiver.
     */
    class Monitor(private val context: Context, private val onChange: () -> Unit) {

        private var last: State? = null
        private var registeredReceiver = false
        private var registeredCallback = false

        private inner class Callback : ConnectivityManager.NetworkCallback {
            override fun onAvailable(network: Network) = check("onAvailable")
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                check("onCapabilitiesChanged")

            override fun onLost(network: Network) = check("onLost")
        }

        private val callback = Callback()

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) =
                check(intent?.action ?: "broadcast")
        }

        fun start() {
            synchronized(this) { last = runCatching { read(context) }.getOrNull() }
            trace("monitor start, initial=${last}")
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            try {
                SagerNet.connectivity.registerNetworkCallback(request, callback)
                registeredCallback = true
            } catch (e: Throwable) {
                Logs.w(e)
                trace("registerNetworkCallback failed: $e")
            }
            val filter = IntentFilter().apply {
                // The telephony action names, spelled out: ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED and
                // ACTION_SIM_CARD_STATE_CHANGED are not in the SDK's android.telephony.TelephonyManager,
                // and the broadcast is a plain string that survives any platform version.
                addAction("android.telephony.action.DEFAULT_DATA_SUBSCRIPTION_CHANGED")
                addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
                addAction("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED")
                addAction("android.intent.action.SIM_STATE_CHANGED")
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    // Exported: the sender is the phone process, a different UID, so a NOT_EXPORTED
                    // receiver never sees it and the switch would only be noticed by the callback above.
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                registeredReceiver = true
            } catch (e: Throwable) {
                Logs.w(e)
                trace("registerReceiver failed: $e")
            }
            trace("monitor started, callback=$registeredCallback receiver=$registeredReceiver")
        }

        fun stop() {
            if (registeredCallback) {
                registeredCallback = false
                runCatching { SagerNet.connectivity.unregisterNetworkCallback(callback) }
            }
            if (registeredReceiver) {
                registeredReceiver = false
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        private fun check(why: String) {
            val state = runCatching { read(context) }.getOrNull()
            if (state == null) {
                trace("check($why): read threw")
                return
            }
            val changed = synchronized(this) {
                if (state == last) return@synchronized false
                last = state
                true
            }
            if (!changed) {
                trace("check($why): unchanged ${state.slot}/${state.carrier}")
                return
            }
            trace("check($why): SIM changed -> ${state.slot}/${state.carrier}, rebuilding")
            try {
                onChange()
            } catch (e: Throwable) {
                Logs.w(e)
            }
        }

        /**
         * Written straight to core.log instead of through [Logs]: the SIM switch is exactly the case that
         * has to be diagnosable from a log export, and [Logs.i] is dropped whenever log_level is below
         * "info", which silently hid this monitor's every line.
         */
        private fun trace(message: String) {
            runCatching { CoreLog.write("[Debug] [SimStateAccess] $message") }
        }
    }
}
