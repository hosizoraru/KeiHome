package os.kei.hyper.home.xposed;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import os.kei.hyper.home.xposed.SurfaceIconKeyIndex.Key;

/** Isolated key operations, not a bitmap/render/IPC or frame-time benchmark. */
public final class KeyBenchmark {
    private static final String[] NAMES = new String[64];
    private static final String[] ACTIVITIES = new String[64];
    private static volatile long sink;
    private static Method allocation;
    private static Object allocationOwner;
    private static Object[] allocationArgs;

    static {
        for (int i = 0; i < NAMES.length; i++) {
            NAMES[i] = "com.example.app" + (i / 2);
            ACTIVITIES[i] = i % 2 == 0 ? null : NAMES[i] + ".AlternateIcon";
        }
        try {
            allocation = Class.forName("android.os.Debug")
                .getMethod("getRuntimeStat", String.class);
            allocationArgs = new Object[] { "art.gc.bytes-allocated" };
        } catch (ClassNotFoundException host) {
            try {
                Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
                allocationOwner = factory.getMethod("getThreadMXBean").invoke(null);
                Class<?> bean = Class.forName("com.sun.management.ThreadMXBean");
                allocation = bean.getMethod("getThreadAllocatedBytes", long.class);
                allocationArgs = new Object[] { Thread.currentThread().getId() };
            } catch (Exception error) { throw new AssertionError(error); }
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static long allocated() throws Exception {
        return Long.parseLong(allocation.invoke(allocationOwner, allocationArgs).toString());
    }

    private static void run(boolean indexed, int iterations, int sample) throws Exception {
        SurfaceIconKeyIndex index = new SurfaceIconKeyIndex();
        Map<Key, Boolean> wanted = new LinkedHashMap<>(128, 0.75f, true);
        Map<Key, Object> cache = new HashMap<>();
        Object bitmap = new Object();
        for (int i = 0; i < NAMES.length; i++) {
            Key key = index.retain(NAMES[i], ACTIVITIES[i]);
            wanted.put(key, Boolean.TRUE);
            cache.put(key, bitmap);
        }
        long bytes = allocated();
        long start = System.nanoTime();
        long hits = 0;
        for (int n = 0; n < iterations; n++) {
            int i = n & 63;
            Key key = indexed ? index.retain(NAMES[i], ACTIVITIES[i])
                : new Key(NAMES[i], ACTIVITIES[i]);
            wanted.put(key, Boolean.TRUE);
            if (cache.get(key) == bitmap) hits++;
        }
        long nanos = System.nanoTime() - start;
        long growth = allocated() - bytes;
        sink = hits;
        if (sink != iterations || wanted.size() != 64 || index.getSize() != 64)
            throw new AssertionError("working set changed");
        System.out.println("{\"sample\":" + sample + ",\"indexed\":" + indexed
            + ",\"iterations\":" + iterations + ",\"allocatedBytes\":" + growth
            + ",\"elapsedNanos\":" + nanos + "}");
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 4; i++) {
            run(false, 100_000, -1);
            run(true, 100_000, -1);
        }
        for (int i = 0; i < 7; i++) {
            run(i % 2 == 0, 1_000_000, i);
            run(i % 2 != 0, 1_000_000, i);
        }
    }
}
