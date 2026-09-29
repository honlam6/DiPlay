package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.shilapi.xcertplay.network.CarPlayVpnService

/**
 * Best-effort self-healing for head units whose SystemUI swallows permission
 * dialogs (common on trimmed Android 9/10 automotive builds that also ship
 * without com.android.vpndialogs). Uses `su` when the unit is rooted; silently
 * no-ops on stock devices. Every action is additive — granting permissions the
 * app is entitled to request anyway — so nothing here can degrade a device.
 */
object RootCompat {
    private const val TAG = "50play-root"

    private val RUNTIME_PERMS = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** True when the device exposes a working `su`. Cheap to call; no root, no wait. */
    fun hasRoot(): Boolean {
        val p = try {
            ProcessBuilder("su", "-c", "id").start()
        } catch (_: Exception) {
            return false
        }
        return try {
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Grants the app's own runtime permissions and the VPN app-op when missing.
     * Returns human-readable descriptions of what was actually applied.
     */
    fun autoFix(context: Context): List<String> {
        val applied = mutableListOf<String>()
        if (!hasRoot()) return applied
        val pkg = context.packageName
        for (perm in RUNTIME_PERMS) {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                if (su("pm grant $pkg $perm")) {
                    applied += perm
                    Log.i(TAG, "granted $perm via su")
                }
            }
        }
        // With ACTIVATE_VPN allowed, VpnService.prepare() returns null so the wired
        // flow never needs the consent dialog that trimmed units cannot show.
        if (CarPlayVpnService.prepare(context) != null) {
            if (su("appops set $pkg ACTIVATE_VPN allow")) {
                applied += "ACTIVATE_VPN"
                Log.i(TAG, "allowed ACTIVATE_VPN app op via su")
            }
        }
        return applied
    }

    private fun su(cmd: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", cmd).start()
        val out = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        p.exitValue() == 0 && out.isEmpty()
    } catch (_: Exception) {
        false
    }
}
