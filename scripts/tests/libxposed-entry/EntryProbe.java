import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated cold-construction/ABI probe. Actual framework attachment is tested by API102 reload. */
public final class EntryProbe {
    public static void main(String[] args) {
        try {
            verify(args);
            System.out.flush();
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("installed APK, exact API102 dex jar");
        int packagedApi = 0;
        DexFile dex = new DexFile(args[0]);
        try {
            Enumeration<String> entries = dex.entries();
            while (entries.hasMoreElements())
                if (entries.nextElement().startsWith("io.github.libxposed.api.")) packagedApi++;
        } finally { dex.close(); }
        if (packagedApi != 0) throw new AssertionError("compile-only API packaged in module");
        android.os.Looper.prepareMainLooper();
        ClassLoader loader = new PathClassLoader(args[0] + ":" + args[1], EntryProbe.class.getClassLoader());
        Class<?> base = loader.loadClass("io.github.libxposed.api.XposedModule");
        String owner = "io.github.libxposed.api.XposedModuleInterface$";
        String[][] callbacks = {
            { "onModuleLoaded", "ModuleLoadedParam" }, { "onPackageLoaded", "PackageLoadedParam" },
            { "onPackageReady", "PackageReadyParam" }, { "onSystemServerStarting", "SystemServerStartingParam" },
            { "onHotReloading", "HotReloadingParam" }, { "onHotReloaded", "HotReloadedParam" }
        };
        JSONArray result = new JSONArray();
        try (ZipFile apk = new ZipFile(args[0]);
             InputStream input = apk.getInputStream(apk.getEntry("META-INF/xposed/java_init.list"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String names = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : names.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                Class<?> entry = loader.loadClass(name);
                if (!base.isAssignableFrom(entry)) throw new AssertionError("entry base");
                Object instance = entry.getConstructor().newInstance();
                for (String[] callback : callbacks) {
                    Method method = entry.getDeclaredMethod(callback[0], loader.loadClass(owner + callback[1]));
                    if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != ("onHotReloading".equals(callback[0]) ? boolean.class : void.class))
                        throw new AssertionError("callback ABI " + callback[0]);
                }
                Class<?> loaded = loader.loadClass(owner + "ModuleLoadedParam");
                Object param = Proxy.newProxyInstance(loader, new Class<?>[] { loaded },
                    (proxy, method, values) -> "getProcessName".equals(method.getName()) ? "com.miui.home"
                        : method.getReturnType() == boolean.class ? false : null);
                entry.getDeclaredMethod("onModuleLoaded", loaded).invoke(instance, param);
                Class<?> retiring = loader.loadClass(owner + "HotReloadingParam");
                Object retiringParam = Proxy.newProxyInstance(loader, new Class<?>[] { retiring },
                    (proxy, method, values) -> "getSavedInstanceState".equals(method.getName())
                        ? new HashMap<String, Object>() : null);
                if (!Boolean.TRUE.equals(entry.getDeclaredMethod("onHotReloading", retiring)
                    .invoke(instance, retiringParam)))
                    throw new AssertionError("empty Launcher generation retirement");
                result.put(new JSONObject().put("entry", name).put("publicNoArgConstruction", true)
                    .put("declaredLifecycleCallbacks", callbacks.length).put("emptyLauncherRetirement", true));
            }
        }
        if (result.length() != 1) throw new AssertionError("unexpected entry count");
        System.out.println(new JSONObject().put("packagedApiClasses", packagedApi).put("entries", result));
    }
}
