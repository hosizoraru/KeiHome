package os.kei.hyper.home.navlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.BitmapShader;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;

/**
 * Cooperative background-owner fixture, not an interceptor for other apps.
 * The supplied artwork remains a local test asset, outside the repository.
 */
public final class NavLabActivity extends Activity {
    private static final int CROPPED_ART = 4;
    private static final int CONTINUOUS_ART = 5;
    private static final int FULL_ART = 6;
    private static final int SEPARATE_ART = 7;
    private static final int OPAQUE_CONTROL = 8;
    private static final int APP_ART_HEIGHT_DP = 52;
    private static final int UNDERLAY = 0xFF1B1D1F;

    private Bitmap footer;
    private Bitmap edge;
    private Bitmap blurred;
    private FrameLayout root;
    private ImageView footerView;
    private LinearLayout tabs;
    private Backdrop backdrop;
    private TextView status;
    private EditText editor;
    private final Matrix footerMatrix = new Matrix();
    private int mode;
    private int selectedTab;
    private int measuredArtworkHeight;
    private Insets navigation = Insets.NONE;
    private int stableNavHeight;
    private int statusHeight;
    private int average;
    private boolean ime;
    private boolean bottomGestureBar;
    private boolean transientWindow;
    private boolean fullscreen;
    private boolean secure;
    private SurfaceView artworkLayer;
    private View opaqueCover;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mode = state == null ? getIntent().getIntExtra("mode", 0) : state.getInt("mode");
        measuredArtworkHeight = getIntent().getIntExtra("artworkHeightPx", 0);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        try (InputStream source = getAssets().open("footer.png")) {
            footer = BitmapFactory.decodeStream(source);
        } catch (Exception failure) {
            throw new IllegalStateException("Supply the owned footer test asset", failure);
        }
        edge = Bitmap.createBitmap(footer, 0, footer.getHeight() - 1, footer.getWidth(), 1);
        blurred = Bitmap.createScaledBitmap(edge, 16, 1, true);
        long r = 0, g = 0, b = 0;
        for (int x = 0; x < edge.getWidth(); x++) {
            int color = edge.getPixel(x, edge.getHeight() - 1);
            r += Color.red(color); g += Color.green(color); b += Color.blue(color);
        }
        average = Color.rgb((int) (r / edge.getWidth()), (int) (g / edge.getWidth()),
                (int) (b / edge.getWidth()));
        root = new FrameLayout(this);
        root.setBackgroundColor(UNDERLAY);
        artworkLayer = new SurfaceView(this);
        artworkLayer.setVisibility(View.INVISIBLE);
        artworkLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        SurfaceHolder artworkHolder = artworkLayer.getHolder();
        artworkHolder.setFormat(PixelFormat.RGBA_8888);
        artworkHolder.addCallback(new SurfaceHolder.Callback2() {
            private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            private final RectF destination = new RectF();

            @Override public void surfaceCreated(SurfaceHolder holder) {}
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {}

            @Override public void surfaceRedrawNeeded(SurfaceHolder holder) {
                Canvas canvas = holder.lockCanvas();
                if (canvas == null) return;
                try {
                    canvas.drawColor(UNDERLAY);
                    float height = (float) canvas.getWidth() * footer.getHeight() / footer.getWidth();
                    destination.set(0f, canvas.getHeight() - height, canvas.getWidth(), canvas.getHeight());
                    canvas.drawBitmap(footer, null, destination, paint);
                } finally {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        });
        root.addView(artworkLayer, new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM));
        opaqueCover = new View(this);
        opaqueCover.setBackgroundColor(UNDERLAY);
        opaqueCover.setVisibility(View.INVISIBLE);
        root.addView(opaqueCover, new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM));
        backdrop = new Backdrop();
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM);
        root.addView(backdrop, bar);
        footerView = new ImageView(this);
        footerView.setImageBitmap(footer);
        footerView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(footerView, new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM));
        tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        addRow(tabs, new String[]{"Home", "Feed", "Mine"}, new Runnable[]{
                () -> selectTab(0), () -> selectTab(1), () -> selectTab(2)});
        root.addView(tabs, new FrameLayout.LayoutParams(-1, dp(APP_ART_HEIGHT_DP), Gravity.BOTTOM));
        selectTab(state == null ? 0 : state.getInt("tab"));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(60), dp(20), dp(20));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        controls.addView(status);
        addRow(controls, new String[]{"Control", "Color", "Edge", "Blur", "Rotate"},
                new Runnable[]{() -> setMode(0), () -> setMode(1),
                        () -> setMode(2), () -> setMode(3),
                        () -> setRequestedOrientation(getResources().getConfiguration().orientation == 1
                                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)});
        addRow(controls, new String[]{"Crop + color", "Continue artwork"},
                new Runnable[]{() -> setMode(CROPPED_ART), () -> setMode(CONTINUOUS_ART)});
        addRow(controls, new String[]{"Full artwork", "Separate layer", "Opaque control"},
                new Runnable[]{() -> setMode(FULL_ART), () -> setMode(SEPARATE_ART),
                        () -> setMode(OPAQUE_CONTROL)});
        editor = new EditText(this);
        editor.setSingleLine(true);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(Color.LTGRAY);
        editor.setHint("IME guard test");
        controls.addView(editor);
        addRow(controls, new String[]{"Keyboard", "Hide IME", "Dialog"},
                new Runnable[]{() -> {
                    editor.requestFocus();
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                }, () -> getWindow().getInsetsController().hide(WindowInsets.Type.ime()),
                        () -> {
                            transientWindow = true; refresh();
                            AlertDialog dialog = new AlertDialog.Builder(this)
                                    .setTitle("Transient-window guard")
                                    .setMessage("Backdrop is suspended while this dialog is visible.")
                                    .setPositiveButton("Close", null).create();
                            dialog.setOnDismissListener(ignored -> {
                                transientWindow = false; refresh();
                            });
                            dialog.show();
                        }});
        addRow(controls, new String[]{"Fullscreen", "Secure"},
                new Runnable[]{() -> {
                            fullscreen = !fullscreen;
                            if (fullscreen) getWindow().getInsetsController()
                                    .hide(WindowInsets.Type.systemBars());
                            else getWindow().getInsetsController()
                                    .show(WindowInsets.Type.systemBars());
                            refresh();
                        }, () -> {
                            secure = !secure;
                            if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                            refresh();
                        }});
        TextView scope = new TextView(this);
        scope.setTextColor(Color.LTGRAY);
        scope.setText("RESEARCH FIXTURE\nOwn window / real skin footer asset\n"
                + "No app interception, networking, or persistent overlay.");
        controls.addView(scope);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            navigation = insets.getInsets(WindowInsets.Type.navigationBars());
            stableNavHeight = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
            bottomGestureBar = navigation.bottom > 0 && navigation.left == 0 && navigation.right == 0
                    && insets.getInsets(WindowInsets.Type.tappableElement()).bottom == 0
                    && insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom > 0;
            statusHeight = insets.getInsets(WindowInsets.Type.statusBars()).top;
            ime = insets.isVisible(WindowInsets.Type.ime());
            controls.setPadding(dp(20), statusHeight + dp(16), dp(20), dp(20));
            refresh();
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r1, b1, ol, ot, or1, ob1) -> {
            if (r1 - l != or1 - ol || b1 - t != ob1 - ot) refresh();
        });
        setContentView(root);
        refresh();
    }

    private void addRow(LinearLayout parent, String[] labels, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            Button button = new Button(this);
            button.setText(labels[i]);
            Runnable action = actions[i];
            button.setOnClickListener(ignored -> action.run());
            row.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        parent.addView(row);
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private void setMode(int next) { mode = next; refresh(); }

    private void selectTab(int index) {
        selectedTab = index;
        LinearLayout row = (LinearLayout) tabs.getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            row.getChildAt(i).setSelected(index == i);
            row.getChildAt(i).setAlpha(index == i ? 1f : 0.7f);
        }
    }

    private void refresh() {
        if (root == null) return;
        boolean eligible = !ime && !transientWindow && !fullscreen && !secure;
        boolean appGeometry = mode >= CROPPED_ART && mode <= OPAQUE_CONTROL;
        int navHeight = appGeometry ? navigation.bottom : stableNavHeight;
        FrameLayout.LayoutParams bar = (FrameLayout.LayoutParams) backdrop.getLayoutParams();
        if (bar.height != navHeight) { bar.height = navHeight; backdrop.setLayoutParams(bar); }
        float intrinsicScale = (float) root.getWidth() / footerView.getDrawable().getIntrinsicWidth();
        float scaledHeight = intrinsicScale * footerView.getDrawable().getIntrinsicHeight();
        int appArtHeight = measuredArtworkHeight > 0 && measuredArtworkHeight <= root.getHeight()
                ? measuredArtworkHeight : dp(APP_ART_HEIGHT_DP);
        boolean continueArt = mode == CONTINUOUS_ART && eligible && bottomGestureBar
                && scaledHeight >= appArtHeight + navHeight;
        boolean fullArt = mode == FULL_ART && eligible && bottomGestureBar
                && scaledHeight > navHeight && scaledHeight <= root.getHeight();
        boolean wantsLayer = (mode == SEPARATE_ART || mode == OPAQUE_CONTROL)
                && eligible && bottomGestureBar
                && scaledHeight > navHeight && scaledHeight <= root.getHeight();
        boolean separateArt = wantsLayer && mode == SEPARATE_ART;
        setLayerHeight(artworkLayer, wantsLayer ? (int) Math.ceil(scaledHeight) : 0);
        setLayerHeight(opaqueCover, wantsLayer && !separateArt ? (int) Math.ceil(scaledHeight) : 0);
        int footerHeight = appGeometry
                ? appArtHeight : Math.max(1, root.getWidth() * footer.getHeight() / footer.getWidth());
        int footerMargin = continueArt || fullArt ? 0 : navHeight;
        if (continueArt) footerHeight += navHeight;
        if (fullArt) footerHeight = (int) Math.ceil(scaledHeight);
        FrameLayout.LayoutParams art = (FrameLayout.LayoutParams) footerView.getLayoutParams();
        if (art.height != footerHeight || art.bottomMargin != footerMargin) {
            art.height = footerHeight;
            art.bottomMargin = footerMargin;
            footerView.setLayoutParams(art);
        }
        footerView.setScaleType(appGeometry ? ImageView.ScaleType.MATRIX : ImageView.ScaleType.FIT_XY);
        if (appGeometry) {
            footerMatrix.setScale(intrinsicScale, intrinsicScale);
            // A fractional offset anchors the last source row to the window edge.
            if (fullArt) footerMatrix.postTranslate(0, footerHeight - scaledHeight);
            footerView.setImageMatrix(footerMatrix);
        }
        FrameLayout.LayoutParams tabLayout = (FrameLayout.LayoutParams) tabs.getLayoutParams();
        if (tabLayout.bottomMargin != navHeight) {
            tabLayout.bottomMargin = navHeight;
            tabs.setLayoutParams(tabLayout);
        }
        tabs.setVisibility(appGeometry ? View.VISIBLE : View.GONE);
        footerView.setVisibility(separateArt ? View.INVISIBLE : View.VISIBLE);
        backdrop.setVisibility(eligible && mode != 0 && !continueArt && !fullArt && !separateArt
                ? View.VISIBLE : View.INVISIBLE);
        backdrop.invalidate();
        int appearance = eligible && mode != 0
                ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        getWindow().getInsetsController().setSystemBarsAppearance(
                appearance, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        status.setText("Gesture backdrop lab | mode=" + mode + "\n"
                + "nav=" + navHeight + "px, ime=" + ime + ", transient=" + transientWindow
                + "\nfullscreen=" + fullscreen + ", secure=" + secure
                + "\nart=" + footerHeight + "px, continued=" + continueArt + ", full=" + fullArt
                + "\nlayer=" + wantsLayer + ", exposed=" + separateArt);
        Log.i("KeiHomeNavLab", "mode=" + mode + " eligible=" + eligible + " nav=" + navHeight
                + " gesture=" + bottomGestureBar + " art=" + footerHeight
                + " scaledHeight=" + scaledHeight + " continued=" + continueArt + " full=" + fullArt
                + " layer=" + wantsLayer + " exposed=" + separateArt);
    }

    private void setLayerHeight(View layer, int height) {
        if (height > 0) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) layer.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                layer.setLayoutParams(params);
            }
        }
        layer.setVisibility(height > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("mode", mode);
        state.putInt("tab", selectedTab);
        super.onSaveInstanceState(state);
    }

    private final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Backdrop() { super(NavLabActivity.this); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            if (width <= 0 || height <= 0) return;
            BitmapShader texture = new BitmapShader(blurred, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale((float) width / blurred.getWidth(), height);
            texture.setLocalMatrix(matrix);
            Shader fade = new LinearGradient(0, 0, 0, height, 0x00FFFFFF, 0xFFFFFFFF,
                    Shader.TileMode.CLAMP);
            blurPaint.setShader(new ComposeShader(texture, fade, PorterDuff.Mode.DST_IN));
        }
        @Override protected void onDraw(Canvas canvas) {
            if (mode == 1 || mode >= CROPPED_ART && mode <= OPAQUE_CONTROL) {
                canvas.drawColor(average);
            }
            else {
                canvas.drawBitmap(edge, null, new Rect(0, 0, getWidth(), getHeight()), paint);
                if (mode == 3) canvas.drawRect(0, 0, getWidth(), getHeight(), blurPaint);
            }
        }
    }
}
