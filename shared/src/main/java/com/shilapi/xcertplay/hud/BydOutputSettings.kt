package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context

/**
 * One user switch for BYD navigation output. On the tested car the windshield HUD mirrors what the
 * instrument cluster receives, so separate HUD/cluster switches cannot behave independently.
 */
object BydOutputSettings {
    private const val PREFS = "diplay_byd_outputs"
    private const val KEY_ENABLED = "navigation_enabled"

    private const val SOMEIP_PACKAGE = "com.ts.car.someip.service"
    private const val SOMEIP_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /**
     * Whether the head unit has a BYD navigation receiver, so settings can hide a switch that
     * cannot work.
     *
     * Package presence alone is not enough: non-BYD LingOS units ship unrelated packages under
     * similar names, and treating those as a gateway makes the app bind a service that does not
     * exist — the retry loop then burns CPU and floods logcat forever
     * (see docs/SGMW-LINGOS-COMPAT.md). So probe the actual SOME/IP component.
     */
    fun available(context: Context): Boolean =
        BydStandaloneHudOutput.available(context) ||
            hasSomeIpGateway(context) ||
            installed(context, "com.byd.amapservice")

    private fun hasSomeIpGateway(context: Context): Boolean = runCatching {
        context.packageManager.getServiceInfo(ComponentName(SOMEIP_PACKAGE, SOMEIP_CLASS), 0)
    }.isSuccess

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
