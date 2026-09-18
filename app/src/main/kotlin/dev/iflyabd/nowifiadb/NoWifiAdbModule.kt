package dev.iflyabd.nowifiadb

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class NoWifiAdbModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("NoWifiAdb: handleLoadPackage ${lpparam.packageName} / ${lpparam.processName}")
        when (lpparam.packageName) {
            "com.android.settings" -> {
                XposedBridge.log("NoWifiAdb: hooking Settings")
                SettingsHook.init(lpparam)
            }
            "android" -> {
                XposedBridge.log("NoWifiAdb: hooking framework")
                FrameworkHook.init(lpparam)
            }
        }
    }
}
