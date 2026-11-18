import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.WindowInsets;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bounded AVD-only research sampler. It neither creates windows nor changes policy.
 * Hidden signatures must be checked against the exact installed framework first.
 */
public final class NavEdgeCapture {
    private static Object call(Object object, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = object.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, args);
    }

    public static void main(String[] args) throws Exception {
        if (!"ranchu".equals(Build.HARDWARE) || Build.VERSION.SDK_INT != 37) {
            throw new IllegalStateException("Only the inspected API 37 AVD is supported");
        }
        // app_process does not receive Zygote's normal application bootstrap.
        Class<?> shared = Class.forName("com.android.internal.os.ApplicationSharedMemory");
        shared.getMethod("setInstance", shared).invoke(null, shared.getMethod("create").invoke(null));
        String expectedPackage = args[0];
        File directory = new File(args[1]);
        if (!directory.getCanonicalPath().startsWith("/data/local/tmp/keihome-nav-")) {
            throw new IllegalArgumentException("Use an owned research directory");
        }
        int count = Integer.parseInt(args[2]);
        if (count < 1 || count > 32) throw new IllegalArgumentException("Sample count: 1..32");
        directory.mkdirs();

        Class<?> taskManagerClass = Class.forName("android.app.ActivityTaskManager");
        Object taskManager = taskManagerClass.getMethod("getInstance").invoke(null);
        Class<?> windowManagerClass = Class.forName("android.view.WindowManagerGlobal");
        Object wm = windowManagerClass.getMethod("getWindowManagerService").invoke(null);
        Class<?> stateClass = Class.forName("android.view.InsetsState");
        Class<?> captureClass = Class.forName("android.window.ScreenCaptureInternal");
        Class<?> builderClass = Class.forName(
                "android.window.ScreenCaptureInternal$CaptureArgs$Builder");
        Class<?> argumentsClass = Class.forName(
                "android.window.ScreenCaptureInternal$CaptureArgs");
        Class<?> listenerClass = Class.forName(
                "android.window.ScreenCaptureInternal$ScreenCaptureListener");
        JSONArray samples = new JSONArray();
        String stopped = "sample-budget";
        Rect originalDisplay = null;
        ComponentName originalActivity = null;
        int originalTask = -1;

        for (int i = 0; i < count; i++) {
            long guardStart = SystemClock.elapsedRealtimeNanos();
            List<?> tasks = (List<?>) taskManagerClass.getMethod("getTasks", int.class)
                    .invoke(taskManager, 1);
            if (tasks.isEmpty()) { stopped = "no-task"; break; }
            ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) tasks.get(0);
            ComponentName top = task.topActivity;
            int displayId = task.getClass().getField("displayId").getInt(task);
            if (top == null || !expectedPackage.equals(top.getPackageName())
                    || displayId != 0) {
                stopped = "owner-changed"; break;
            }
            if (originalActivity == null) {
                originalActivity = top;
                originalTask = task.id;
            } else if (!originalActivity.equals(top) || originalTask != task.id) {
                stopped = "activity-changed"; break;
            }
            int windowingMode = (Integer) call(task, "getWindowingMode", new Class<?>[0]);
            if (windowingMode != 1) { stopped = "not-fullscreen-task"; break; }
            if ((Boolean) call(wm, "isKeyguardLocked", new Class<?>[0])) {
                stopped = "keyguard"; break;
            }
            Object state = stateClass.getConstructor().newInstance();
            call(wm, "getWindowInsets",
                    new Class<?>[]{int.class, IBinder.class, stateClass}, 0, null, state);
            Rect display = (Rect) call(state, "getDisplayFrame", new Class<?>[0]);
            if (originalDisplay == null) originalDisplay = new Rect(display);
            else if (!originalDisplay.equals(display)) { stopped = "geometry-changed"; break; }
            Rect nav = null;
            boolean ime = false;
            int sources = (Integer) call(state, "sourceSize", new Class<?>[0]);
            for (int j = 0; j < sources; j++) {
                Object source = call(state, "sourceAt", new Class<?>[]{int.class}, j);
                boolean visible = (Boolean) call(source, "isVisible", new Class<?>[0]);
                int type = (Integer) call(source, "getType", new Class<?>[0]);
                if (type == WindowInsets.Type.ime() && visible) ime = true;
                if (type == WindowInsets.Type.navigationBars() && visible) {
                    nav = new Rect((Rect) call(source, "getFrame", new Class<?>[0]));
                }
            }
            if (ime) { stopped = "ime"; break; }
            if (nav == null || nav.left != display.left || nav.right != display.right
                    || nav.bottom != display.bottom || nav.height() < 4
                    || nav.height() > display.height() / 15 || nav.top < 8) {
                stopped = "unsupported-insets"; break;
            }
            Rect crop = new Rect(display.left, nav.top - 4, display.right, nav.top);
            Object builder = builderClass.getConstructor().newInstance();
            call(builder, "setSourceCrop", new Class<?>[]{Rect.class}, crop);
            call(builder, "setFrameScale", new Class<?>[]{float.class, float.class},
                    512f / crop.width(), 1f);
            // Policy 2 is THROW_EXCEPTION in the inspected API 37 framework.
            call(builder, "setSecureContentPolicy", new Class<?>[]{int.class}, 2);
            call(builder, "setProtectedContentPolicy", new Class<?>[]{int.class}, 2);
            Object captureArgs = call(builder, "build", new Class<?>[0]);
            Object listener = captureClass.getMethod("createSyncCaptureListener").invoke(null);
            long captureStart = SystemClock.elapsedRealtimeNanos();
            Object buffer;
            try {
                call(wm, "captureDisplay", new Class<?>[]{
                        int.class, argumentsClass, listenerClass}, 0, captureArgs, listener);
                buffer = call(listener, "getBuffer", new Class<?>[0]);
            } catch (java.lang.reflect.InvocationTargetException rejected) {
                stopped = "capture-rejected";
                break;
            }
            long captured = SystemClock.elapsedRealtimeNanos();
            if (buffer == null) {
                stopped = (Boolean) call(listener, "isSecureLayer", new Class<?>[0])
                        ? "secure-content" : "capture-unavailable";
                break;
            }
            HardwareBuffer hardware = (HardwareBuffer) call(
                    buffer, "getHardwareBuffer", new Class<?>[0]);
            try {
                List<?> current = (List<?>) taskManagerClass.getMethod("getTasks", int.class)
                        .invoke(taskManager, 1);
                ActivityManager.RunningTaskInfo latest = current.isEmpty() ? null
                        : (ActivityManager.RunningTaskInfo) current.get(0);
                if (latest == null || latest.id != task.id
                        || !top.equals(latest.topActivity)) {
                    stopped = "owner-changed-during-capture"; break;
                }
                if ((Boolean) call(buffer, "containsSecureLayers", new Class<?>[0])) {
                    stopped = "secure-content"; break;
                }
                Bitmap bitmap = (Bitmap) call(buffer, "asBitmap", new Class<?>[0]);
                if (bitmap.getWidth() != 512 || bitmap.getHeight() != 4) {
                    bitmap.recycle();
                    stopped = "unexpected-capture-size"; break;
                }
                Bitmap readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (readable == null) {
                    bitmap.recycle();
                    stopped = "readback-unavailable"; break;
                }
                try {
                    try (FileOutputStream out = new FileOutputStream(
                            new File(directory, "edge-" + i + ".png"))) {
                        readable.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                } finally {
                    readable.recycle();
                    bitmap.recycle();
                }
            } finally {
                hardware.close();
            }
            long finished = SystemClock.elapsedRealtimeNanos();
            JSONObject sample = new JSONObject();
            sample.put("guard_ms", (captureStart - guardStart) / 1e6);
            sample.put("capture_ms", (captured - captureStart) / 1e6);
            sample.put("total_ms", (finished - guardStart) / 1e6);
            sample.put("crop", crop.toShortString());
            sample.put("navigation", nav.toShortString());
            samples.put(sample);
            System.out.println("SAMPLE " + i);
            SystemClock.sleep(250);
        }
        JSONObject report = new JSONObject();
        report.put("samples", samples);
        report.put("stopped", stopped);
        report.put("output_pixels_per_sample", 2048);
        report.put("production_scene_classifier", false);
        try (FileOutputStream out = new FileOutputStream(new File(directory, "report.json"))) {
            out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        System.out.println(report);
        System.out.flush();
        // Avoid app_process native-static teardown racing capture callback threads.
        Runtime.getRuntime().halt(0);
    }
}
