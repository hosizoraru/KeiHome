package os.kei.home.wallpaper.fixture;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

/** A real app identity is required by WallpaperManager's package/UID checks. */
public final class Main extends Activity {
    private static final java.util.concurrent.ExecutorService WORKER =
        java.util.concurrent.Executors.newSingleThreadExecutor(task ->
            new Thread(task, "KeiHomeWallpaperFixture"));
    private String currentRequest;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        enqueue(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        enqueue(intent);
    }

    private void enqueue(Intent intent) {
        String mode = intent.getStringExtra("mode");
        String request = intent.getStringExtra("request");
        if (request == null) request = "manual";
        currentRequest = request;
        final String token = request;
        WORKER.execute(() -> applyWallpaper(mode, token));
    }

    private void applyWallpaper(String mode, String request) {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(this);
            int both = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
            if ("restore".equals(mode)) {
                boolean separateLock = java.util.Arrays.asList(getAssets().list("")).contains("lock");
                try (var input = getAssets().open("original")) {
                    manager.setStream(input, null, true, separateLock ? WallpaperManager.FLAG_SYSTEM : both);
                }
                if (separateLock) {
                    try (var input = getAssets().open("lock")) {
                        manager.setStream(input, null, true, WallpaperManager.FLAG_LOCK);
                    }
                }
            } else {
                Bitmap image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                try {
                    image.eraseColor(0xff388e3c);
                    manager.setBitmap(image, null, true, both);
                } finally {
                    image.recycle();
                }
            }
            Log.i("KeiWallpaperFixture", "Applied wallpaper id=" + manager.getWallpaperId(1)
                + " request=" + request + " mode=" + mode);
        } catch (Exception failure) {
            Log.e("KeiWallpaperFixture", "Failed request=" + request + " mode=" + mode, failure);
        }
        runOnUiThread(() -> {
            if (request.equals(currentRequest)) finishAndRemoveTask();
        });
    }
}
