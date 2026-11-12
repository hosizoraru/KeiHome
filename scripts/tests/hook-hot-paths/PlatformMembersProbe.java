package os.kei.hyper.home.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.json.JSONObject;

/** Run in an isolated shell process; it never changes users, settings or module state. */
public final class PlatformMembersProbe {
    private static Object[] members() {
        NativeMonetPlatformMembers p = NativeMonetPlatformMembers.INSTANCE;
        return new Object[] { p.getCurrentUser(), p.getContextUser(), p.getPackageUid(),
            p.getSocketConstructor(), p.getUserHandle(), p.getUserContext() };
    }

    public static void main(String[] args) {
        try {
            verify();
            System.out.flush();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify() throws Exception {
        Object[] first = members();
        for (int n = 0; n < 1000; n++) {
            Object[] next = members();
            for (int i = 0; i < first.length; i++)
                if (first[i] != next[i]) throw new AssertionError("member not reused");
        }
        NativeMonetPlatformMembers p = NativeMonetPlatformMembers.INSTANCE;
        int user = (Integer) p.getCurrentUser().invoke(null);
        android.os.Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object owner = thread.getMethod("systemMain").invoke(null);
        Context base = (Context) thread.getMethod("getSystemContext").invoke(owner);
        Object handle = p.getUserHandle().invoke(null, user);
        Context context = (Context) p.getUserContext().invoke(base, handle, 0);
        int contextUser = (Integer) p.getContextUser().invoke(context);
        int uid = (Integer) p.getPackageUid()
            .invoke(context.getPackageManager(), "com.miui.home", 0, user);
        if (user < 0 || user != contextUser || uid < 0 ||
            !Integer.valueOf(user).equals(p.getCurrentUser().invoke(null)))
            throw new AssertionError("live identity mismatch");
        Method entry = Class.forName("android.app.ApplicationPackageManager")
            .getDeclaredMethod("loadUnbadgedItemIcon", PackageItemInfo.class, ApplicationInfo.class);
        if (!Modifier.isPublic(entry.getModifiers()) || Modifier.isStatic(entry.getModifiers())
            || entry.getReturnType() != Drawable.class)
            throw new AssertionError("framework icon getter ABI mismatch");
        System.out.println(new JSONObject().put("memberIdentityChecks", 6000)
            .put("liveUser", user).put("contextUser", contextUser).put("launcherUid", uid)
            .put("verifiedRootProbe",
                "Landroid/app/ApplicationPackageManager;->loadUnbadgedItemIcon"
                + "(Landroid/content/pm/PackageItemInfo;Landroid/content/pm/ApplicationInfo;)"
                + "Landroid/graphics/drawable/Drawable;")
            .put("verifiedRootProbeFingerprint", android.os.Build.FINGERPRINT));
    }
}
