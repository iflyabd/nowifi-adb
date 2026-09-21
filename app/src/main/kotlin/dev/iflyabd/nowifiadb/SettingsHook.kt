package dev.iflyabd.nowifiadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.iflyabd.nowifiadb.compat.SettingsAppRefs

object SettingsHook {
    private const val TAG_WTS_OBSERVER = "hotspot_adb_observer"
    private const val TAG_WTS_RECEIVER = "hotspot_adb_receiver"
    private const val TAG_WD_OBSERVER = "hotspot_adb_fixed_visibility"
    private const val TAG_NOWIFI_OBSERVER = "nowifi_adb_observer"
    private const val TAG_ONBOOT_OBSERVER = "nowifi_adb_onboot_observer"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookIsWifiConnected(lpparam)
        hookGetIpv4Address(lpparam)
        hookGetAdbWirelessPort(lpparam)
        hookWifiTetherSettings(lpparam)
        hookWirelessDebuggingFragment(lpparam)
        hookFragmentCleanup(lpparam)
    }

    private fun hookFragmentCleanup(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Unregister observers/receivers we attached in injection hooks. Both target
        // fragments extend DashboardFragment so one hook covers them. Android 16 QPR
        // dropped onDestroyView from DashboardFragment; fall back to onStop, which
        // pairs symmetrically with the onStart-time registration.
        val cleanup =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cleanupFragment(param.thisObject)
                }
            }
        for (method in listOf("onDestroyView", "onStop")) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    lpparam.classLoader,
                    method,
                    cleanup,
                )
                return
            } catch (e: Throwable) {
                XposedBridge.log("NoWifiAdb: DashboardFragment.$method unavailable: $e")
            }
        }
    }

    private fun cleanupFragment(fragment: Any) {
        val context =
            XposedHelpers.callMethod(fragment, "getContext") as? Context ?: return
        val resolver = context.contentResolver
        for (tag in listOf(TAG_WTS_OBSERVER, TAG_WD_OBSERVER)) {
            val observer =
                XposedHelpers.getAdditionalInstanceField(fragment, tag) as? ContentObserver
                    ?: continue
            try {
                resolver.unregisterContentObserver(observer)
            } catch (e: Throwable) {
                XposedBridge.log("NoWifiAdb: unregister $tag failed: $e")
            }
            XposedHelpers.removeAdditionalInstanceField(fragment, tag)
        }
        val receiver =
            XposedHelpers.getAdditionalInstanceField(fragment, TAG_WTS_RECEIVER)
                as? BroadcastReceiver
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Throwable) {
                XposedBridge.log("NoWifiAdb: unregister $TAG_WTS_RECEIVER failed: $e")
            }
            XposedHelpers.removeAdditionalInstanceField(fragment, TAG_WTS_RECEIVER)
        }
    }

    private fun hookIsWifiConnected(lpparam: XC_LoadPackage.LoadPackageParam) {
        val controllerClass = SettingsAppRefs.resolveControllerClassName(lpparam.classLoader)
        try {
            XposedHelpers.findAndHookMethod(
                controllerClass,
                lpparam.classLoader,
                "isWifiConnected",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == false) {
                            val context = param.args[0] as Context
                            if (HotspotHelper.isBypassActive(context)) {
                                param.result = true
                                XposedBridge.log("NoWifiAdb: isWifiConnected -> true (bypass active)")
                            }
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("NoWifiAdb: failed to hook isWifiConnected: $e")
        }
    }

    private fun hookGetIpv4Address(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.development.AdbIpAddressPreferenceController",
                lpparam.classLoader,
                "getIpv4Address",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context =
                            XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                                ?: return
                        if (!HotspotHelper.isBypassActive(context)) return
                        if (!HotspotHelper.isHotspotActive(context)) {
                            // No-WiFi mode without hotspot: show any device IP so the
                            // pairing screen has something usable (e.g. Tailscale IP).
                            HotspotHelper.getAnyDeviceIp()?.let { param.result = it }
                            return
                        }
                        if (HotspotHelper.isFixedIpEnabled(context)) {
                            param.result = HotspotHelper.FIXED_IP
                            return
                        }
                        val ip = HotspotHelper.getHotspotIpAddress(context) ?: return
                        param.result = ip
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("NoWifiAdb: failed to hook getIpv4Address: $e")
        }
    }

    private fun hookGetAdbWirelessPort(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Override the port value returned by IAdbManager binder calls in the Settings process only.
        // adbd on the server side keeps binding its real port; the TCP proxy in system_server forwards 5555 to it.
        try {
            XposedHelpers.findAndHookMethod(
                "android.debug.IAdbManager\$Stub\$Proxy",
                lpparam.classLoader,
                "getAdbWirelessPort",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = currentApplication() ?: return
                            if (!HotspotHelper.isFixedPortEnabled(app)) return
                            if (!HotspotHelper.isAdbWifiEnabled(app)) return
                            param.result = HotspotHelper.FIXED_PORT
                        } catch (e: Throwable) {
                            XposedBridge.log("NoWifiAdb: port override failed: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("NoWifiAdb: failed to hook getAdbWirelessPort: $e")
        }
    }

    private fun currentApplication(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookWifiTetherSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tetherSettingsClass =
                XposedHelpers.findClass(
                    "com.android.settings.wifi.tether.WifiTetherSettings",
                    lpparam.classLoader,
                )

            XposedHelpers.findAndHookMethod(
                tetherSettingsClass,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            injectWirelessDebuggingPref(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("NoWifiAdb: failed to inject preference: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("NoWifiAdb: failed to hook WifiTetherSettings: $e")
        }
    }

    private fun injectWirelessDebuggingPref(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val screen =
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
                XposedBridge.log("NoWifiAdb: preferenceScreen is null")
                return
            }
        if (XposedHelpers.callMethod(screen, "findPreference", "hotspot_adb_wireless_debugging") != null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        // PrimarySwitchPreference — split toggle+button, same as Developer Options
        val primarySwitchClass =
            XposedHelpers.findClass(
                "com.android.settingslib.PrimarySwitchPreference",
                lpparam.classLoader,
            )
        val pref = primarySwitchClass.getConstructor(Context::class.java).newInstance(context)

        XposedHelpers.callMethod(pref, "setKey", "hotspot_adb_wireless_debugging")
        XposedHelpers.callMethod(pref, "setTitle", "Wireless debugging")
        updatePrefState(context, pref)

        // Switch toggle listener
        val changeListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                lpparam.classLoader,
            )
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                Settings.Global.putInt(context.contentResolver, HotspotHelper.ADB_WIFI_ENABLED, if (newValue) 1 else 0)
                updatePrefState(context, pref)
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)

        // Click on the left side opens Wireless Debugging screen
        val clickListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceClickListener",
                lpparam.classLoader,
            )
        val clickProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(clickListenerClass),
            ) { _, _, _ ->
                try {
                    val subSettingsClass = XposedHelpers.findClass("com.android.settings.SubSettings", context.classLoader)
                    val fragmentClass = SettingsAppRefs.resolveFragmentClassName(lpparam.classLoader)
                    val intent = android.content.Intent(context, subSettingsClass)
                    intent.putExtra(":settings:show_fragment", fragmentClass)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    XposedBridge.log("NoWifiAdb: failed to open wireless debugging: $e")
                }
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", clickProxy)

        XposedHelpers.callMethod(screen, "addPreference", pref)

        // Sync state from Developer Options; observer stored on the fragment for later cleanup
        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_WTS_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        updatePrefState(context, pref)
                    }
                }
            val resolver = context.contentResolver
            resolver.registerContentObserver(Settings.Global.getUriFor(HotspotHelper.ADB_WIFI_ENABLED), false, observer)
            resolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.FIXED_IP_KEY),
                false,
                observer,
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.FIXED_PORT_KEY),
                false,
                observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_WTS_OBSERVER, observer)
        }

        // Also watch hotspot state changes (on/off) to update the label
        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_WTS_RECEIVER) == null) {
            val handler = Handler(Looper.getMainLooper())
            val updatePref = Runnable { updatePrefState(context, pref) }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        // Run immediately and again after a delay — the hotspot interface
                        // IP may not be available yet when the AP state changes.
                        updatePref.run()
                        handler.postDelayed(updatePref, 1000)
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
                    addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                },
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_WTS_RECEIVER, receiver)
        }

        XposedBridge.log("NoWifiAdb: added wireless debugging toggle to hotspot settings")
    }

    private fun hookWirelessDebuggingFragment(lpparam: XC_LoadPackage.LoadPackageParam) {
        // The target fragment doesn't override onStart() directly, so hook DashboardFragment.onStart() and filter.
        val fragmentClassName = SettingsAppRefs.resolveFragmentClassName(lpparam.classLoader)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass.name != fragmentClassName) return
                        try {
                            injectFixedSwitchesPref(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("NoWifiAdb: failed to inject fixed endpoint pref: $e")
                        }
                        try {
                            injectNoWifiPref(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("NoWifiAdb: failed to inject No-WiFi pref: $e")
                        }
                        try {
                            injectOnBootPref(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("NoWifiAdb: failed to inject on-boot pref: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("NoWifiAdb: failed to hook DashboardFragment.onStart for $fragmentClassName: $e")
        }
    }

    /** Two independent switches on the Wireless Debugging screen: Fixed IP
     *  (always use 192.168.49.1) and Fixed port (always use 5555). */
    private fun injectFixedSwitchesPref(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val screen =
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
                XposedBridge.log("NoWifiAdb: WD preferenceScreen is null")
                return
            }
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        val added = mutableListOf<Any>()
        addFixedSwitch(
            fragment,
            lpparam,
            screen,
            context,
            HotspotHelper.FIXED_IP_KEY,
            "Fixed IP",
            "Always use ${HotspotHelper.FIXED_IP}",
            HotspotHelper.isFixedIpEnabled(context),
        )?.let { added += it }
        addFixedSwitch(
            fragment,
            lpparam,
            screen,
            context,
            HotspotHelper.FIXED_PORT_KEY,
            "Fixed port",
            "Always use ${HotspotHelper.FIXED_PORT}",
            HotspotHelper.isFixedPortEnabled(context),
        )?.let { added += it }
        if (added.isEmpty()) return

        // Place the toggles right after the IP/Port row. Resolve by the "adb_ip_addr_pref"
        // key (present A11–A14, A16) when possible, else default to index 0 (A15, which
        // reorganized the fragment and left the IP row keyless).
        val count = XposedHelpers.callMethod(screen, "getPreferenceCount") as Int
        var targetIndex = 0
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            if (XposedHelpers.callMethod(p, "getKey") as? String == SettingsAppRefs.IP_PREF_KEY) {
                targetIndex = i
                break
            }
        }
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            val newOrder = if (i <= targetIndex) i else i + 1
            XposedHelpers.callMethod(p, "setOrder", newOrder)
        }
        added.forEachIndexed { index, pref ->
            XposedHelpers.callMethod(pref, "setOrder", targetIndex + 1 + index)
            XposedHelpers.callMethod(screen, "addPreference", pref)
        }

        // Toggle visibility with the main Wireless Debugging switch on this screen.
        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_WD_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        val visible = HotspotHelper.isAdbWifiEnabled(context)
                        for (pref in added) {
                            XposedHelpers.callMethod(pref, "setVisible", visible)
                        }
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.ADB_WIFI_ENABLED),
                false,
                observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_WD_OBSERVER, observer)
        }
        XposedBridge.log("NoWifiAdb: added Fixed IP / Fixed port toggles to Wireless Debugging")
    }

    /** Creates one fixed switch pref, or returns null if it already exists. */
    private fun addFixedSwitch(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
        screen: Any,
        context: Context,
        key: String,
        title: String,
        summary: String,
        checked: Boolean,
    ): Any? {
        if (XposedHelpers.callMethod(screen, "findPreference", key) != null) return null

        val switchClass =
            XposedHelpers.findClass(
                "androidx.preference.SwitchPreferenceCompat",
                lpparam.classLoader,
            )
        val pref = switchClass.getConstructor(Context::class.java).newInstance(context)
        XposedHelpers.callMethod(pref, "setKey", key)
        XposedHelpers.callMethod(pref, "setTitle", title)
        XposedHelpers.callMethod(pref, "setSummary", summary)
        XposedHelpers.callMethod(pref, "setChecked", checked)
        XposedHelpers.callMethod(pref, "setVisible", HotspotHelper.isAdbWifiEnabled(context))

        val changeListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                lpparam.classLoader,
            )
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                Settings.Global.putInt(
                    context.contentResolver,
                    key,
                    if (newValue) 1 else 0,
                )
                // Refresh the IP/Port row above so it re-reads our hooks.
                try {
                    XposedHelpers.callMethod(fragment, "updatePreferenceStates")
                } catch (e: Throwable) {
                    XposedBridge.log("NoWifiAdb: updatePreferenceStates failed: $e")
                }
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)
        return pref
    }

    /** No-WiFi mode switch, always visible on the Wireless Debugging screen. When on,
     *  the main Wireless debugging toggle works with no Wi-Fi and no hotspot. */
    private fun injectNoWifiPref(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val screen =
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
                XposedBridge.log("NoWifiAdb: WD preferenceScreen is null (nowifi)")
                return
            }
        if (XposedHelpers.callMethod(screen, "findPreference", HotspotHelper.NOWIFI_KEY) != null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        val switchClass =
            XposedHelpers.findClass(
                "androidx.preference.SwitchPreferenceCompat",
                lpparam.classLoader,
            )
        val pref = switchClass.getConstructor(Context::class.java).newInstance(context)
        XposedHelpers.callMethod(pref, "setKey", HotspotHelper.NOWIFI_KEY)
        XposedHelpers.callMethod(pref, "setTitle", "No-WiFi mode")
        XposedHelpers.callMethod(
            pref,
            "setSummary",
            "Allow Wireless debugging without WiFi or hotspot",
        )
        XposedHelpers.callMethod(pref, "setChecked", HotspotHelper.isNoWifiMode(context))

        val changeListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                lpparam.classLoader,
            )
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                Settings.Global.putInt(
                    context.contentResolver,
                    HotspotHelper.NOWIFI_KEY,
                    if (newValue) 1 else 0,
                )
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)
        XposedHelpers.callMethod(pref, "setOrder", 0)
        XposedHelpers.callMethod(screen, "addPreference", pref)

        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_NOWIFI_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        XposedHelpers.callMethod(pref, "setChecked", HotspotHelper.isNoWifiMode(context))
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.NOWIFI_KEY),
                false,
                observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_NOWIFI_OBSERVER, observer)
        }
        XposedBridge.log("NoWifiAdb: added No-WiFi mode toggle to Wireless Debugging")
    }

    /** "Start on boot" switch. When on, wireless debugging is enabled automatically
     *  after every reboot (only if hotspot is up or No-WiFi mode is on). */
    private fun injectOnBootPref(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val screen =
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
                XposedBridge.log("NoWifiAdb: WD preferenceScreen is null (onboot)")
                return
            }
        if (XposedHelpers.callMethod(screen, "findPreference", HotspotHelper.ONBOOT_KEY) != null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        val switchClass =
            XposedHelpers.findClass(
                "androidx.preference.SwitchPreferenceCompat",
                lpparam.classLoader,
            )
        val pref = switchClass.getConstructor(Context::class.java).newInstance(context)
        XposedHelpers.callMethod(pref, "setKey", HotspotHelper.ONBOOT_KEY)
        XposedHelpers.callMethod(pref, "setTitle", "Start on boot")
        XposedHelpers.callMethod(
            pref,
            "setSummary",
            "Enable Wireless debugging automatically after reboot",
        )
        XposedHelpers.callMethod(pref, "setChecked", HotspotHelper.isOnBootEnabled(context))

        val changeListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                lpparam.classLoader,
            )
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                Settings.Global.putInt(
                    context.contentResolver,
                    HotspotHelper.ONBOOT_KEY,
                    if (newValue) 1 else 0,
                )
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)
        XposedHelpers.callMethod(pref, "setOrder", 1)
        XposedHelpers.callMethod(screen, "addPreference", pref)

        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_ONBOOT_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        XposedHelpers.callMethod(pref, "setChecked", HotspotHelper.isOnBootEnabled(context))
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.ONBOOT_KEY),
                false,
                observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_ONBOOT_OBSERVER, observer)
        }
        XposedBridge.log("NoWifiAdb: added Start on boot toggle to Wireless Debugging")
    }

    private fun updatePrefState(
        context: Context,
        pref: Any,
    ) {
        val on = HotspotHelper.isAdbWifiEnabled(context) && HotspotHelper.isHotspotActive(context)
        XposedHelpers.callMethod(pref, "setChecked", on)
        XposedHelpers.callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, on))
    }

    private fun getWirelessDebuggingSummary(
        context: Context,
        enabled: Boolean,
    ): String {
        if (!enabled) return ""
        val ip =
            if (HotspotHelper.isFixedIpEnabled(context)) {
                HotspotHelper.FIXED_IP
            } else {
                HotspotHelper.getHotspotIpAddress(context)
                    ?: HotspotHelper.getAnyWlanIp()
                    ?: return ""
            }
        val port =
            if (HotspotHelper.isFixedPortEnabled(context)) {
                HotspotHelper.FIXED_PORT
            } else {
                HotspotHelper.getAdbWirelessPort()
            }
        return if (port > 0) "$ip:$port" else ip
    }
}
