import android.content.Context;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Grants the USB device permission for every attached Apple device to a target
 * uid, without any system dialog. Run as root via app_process:
 *
 *   CLASSPATH=/data/data/com.shihab.diplay/files/usb_grant.dex \
 *       app_process /system/bin GrantUsb <uid>
 *
 * Vendor-only match (0x05AC) because iPhone PIDs differ per generation and
 * enumeration mode. Android 9 keeps USB grants in memory keyed by device path,
 * so this must be re-run after the device re-enumerates (devnum changes).
 */
public class GrantUsb {

    static final int APPLE_VENDOR_ID = 0x05AC;
    static Object usbManager;
    static int targetUid;
    static Method mVid, mGrant2, mGrant1;

    public static void main(String[] args) throws Exception {
        boolean watch = false;
        for (int i = 0; i < args.length; i++) {
            if ("watch".equals(args[i])) {
                watch = true;
            } else if ("--uid".equals(args[i]) && i + 1 < args.length) {
                targetUid = Integer.parseInt(args[++i]);
            } else {
                // bare numeric argument is the uid
                try { targetUid = Integer.parseInt(args[i]); } catch (NumberFormatException ignored) {}
            }
        }

        Looper.prepareMainLooper();
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object at = atClass.getMethod("systemMain").invoke(null);
        Context context = (Context) atClass.getMethod("getSystemContext").invoke(at);
        if (targetUid <= 0) {
            targetUid = context.getPackageManager().getPackageUid("com.shihab.diplay", 0);
        }
        usbManager = context.getSystemService(Context.USB_SERVICE);
        Class<?> devClass = Class.forName("android.hardware.usb.UsbDevice");
        mVid = devClass.getMethod("getVendorId");
        mGrant2 = null;
        mGrant1 = null;
        for (Method m : usbManager.getClass().getMethods()) {
            if (!m.getName().equals("grantPermission")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == devClass && p[1] == int.class) mGrant2 = m;
            if (p.length == 1 && p[0] == devClass) mGrant1 = m;
        }

        do {
            scanAndGrant();
            if (watch) Thread.sleep(2000);
        } while (watch);

        System.out.println("DONE uid=" + targetUid);
    }

    static void scanAndGrant() throws Exception {
        HashMap<String, Object> devices =
                (HashMap<String, Object>) usbManager.getClass().getMethod("getDeviceList").invoke(usbManager);
        for (Object device : devices.values()) {
            int vid = (Integer) mVid.invoke(device);
            if (vid != APPLE_VENDOR_ID) continue;
            if (mGrant2 != null) {
                mGrant2.invoke(usbManager, device, targetUid);
            } else if (mGrant1 != null) {
                mGrant1.invoke(usbManager, device);
            }
            System.out.println("GRANTED vid=" + vid + " to uid " + targetUid);
        }
    }
}
