package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.File

/**
 * Self-healing over the head unit's own adbd.
 *
 * Trimmed automotive builds (LingOS and friends) swallow the USB permission and VPN consent
 * dialogs, so a normal third-party install can never obtain what it needs through the regular
 * Android paths. Those same builds tend to run adbd as root with TCP enabled on
 * `127.0.0.1:5555` — and on this class of unit with no RSA authentication at all (`ro.secure=0`,
 * empty `/data/misc/adb/adb_keys`) — so the app can reach its own device's adbd over loopback and
 * get a root shell with no computer, no root binary and no user interaction.
 *
 * Units that do authenticate are handled by the same [LocalAdb] client: it reports `NOT_APPROVED`
 * and we simply fall back to the normal Android paths, so nothing here can regress a device that
 * never needed the workaround.
 *
 * Every action here is additive and idempotent and only ever touches this package: grant its own
 * runtime permissions, allow its own VPN app-op, authorise the attached USB device for its own
 * uid, and AOT-compile itself.
 */
object AdbSelfHeal {

    private const val TAG = "50play-adb"

    private val RUNTIME_PERMS = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Cheap probe: does this unit give us a usable loopback shell? */
    fun isAvailable(context: Context): Boolean = withAdb(context) { true } ?: false

    /**
     * One-shot repair. Returns what was actually applied; empty when the unit gives us no
     * loopback adbd (stock devices) or nothing was missing.
     */
    fun autoFix(context: Context): List<String> {
        val applied = mutableListOf<String>()
        val reachable = withAdb(context) { adb ->
            val pkg = context.packageName
            // With ACTIVATE_VPN allowed, VpnService.prepare() returns null so the wired flow
            // never needs the consent dialog that trimmed units cannot show.
            if (CarPlayVpnService.prepare(context) != null &&
                adb.shell("appops set $pkg ACTIVATE_VPN allow") != null
            ) {
                applied += "ACTIVATE_VPN"
                Log.i(TAG, "allowed ACTIVATE_VPN via loopback adb")
            }
            for (perm in RUNTIME_PERMS) {
                if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED &&
                    adb.shell("pm grant $pkg $perm") != null
                ) {
                    applied += perm
                    Log.i(TAG, "granted $perm via loopback adb")
                }
            }
            true
        }
        if (reachable != true) {
            Log.i(TAG, "no usable loopback adbd, skipping self-heal")
            return emptyList()
        }
        if (grantUsb(context)) applied += "USB"
        return applied
    }

    /** Authorise every attached Apple device for this app's uid. */
    fun grantUsb(context: Context): Boolean {
        val dex = stageUsbDex(context) ?: return false
        val out = withAdb(context) {
            it.shell("CLASSPATH=$dex app_process /system/bin GrantUsb ${Process.myUid()}")
        } ?: return false
        val ok = out.contains("DONE")
        if (ok) Log.i(TAG, "usb permission granted via loopback adb: ${out.trim()}")
        return ok
    }

    /** Full AOT pass so the first launch after a reboot is not interpreted. */
    fun aotCompile(context: Context): Boolean =
        withAdb(context) { it.shell("cmd package compile -m speed -f ${context.packageName}") } != null

    /**
     * Opens one loopback shell for [block]. Never asks for key approval (`mayAsk = false`), so no
     * dialog can appear on screen; returns null when there is no usable shell.
     */
    private fun <T> withAdb(context: Context, block: (LocalAdb) -> T): T? = try {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) == LocalAdb.Access.READY) block(adb) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "loopback adb unavailable", e)
        null
    }

    private fun stageUsbDex(context: Context): String? = try {
        val target = File(context.filesDir, "usb_grant.dex")
        if (!target.exists() || target.length() == 0L) {
            context.assets.open("usb_grant.dex").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target.absolutePath
    } catch (e: Exception) {
        Log.w(TAG, "cannot stage usb_grant.dex", e)
        null
    }
}
