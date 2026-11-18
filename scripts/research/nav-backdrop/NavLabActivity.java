package os.kei.hyper.home.navlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BitmapShader;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
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
    private Bitmap footer;
    private Bitmap edge;
    private Bitmap blurred;
    private FrameLayout root;
    private ImageView footerView;
    private Backdrop backdrop;
    private TextView status;
    private EditText editor;
    private int mode;
    private int navHeight;
    private int statusHeight;
    private int average;
    private boolean ime;
    private boolean transientWindow;
    private boolean fullscreen;
    private boolean secure;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mode = state == null ? getIntent().getIntExtra("mode", 0) : state.getInt("mode");
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
        root.setBackgroundColor(Color.rgb(27, 29, 31));
        backdrop = new Backdrop();
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM);
        root.addView(backdrop, bar);
        footerView = new ImageView(this);
        footerView.setImageBitmap(footer);
        footerView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(footerView, new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(20), dp(60), dp(20), dp(20));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        controls.addView(status);
        addRow(controls, new String[]{"Control", "Color", "Edge", "Blur"},
                new Runnable[]{() -> setMode(0), () -> setMode(1),
                        () -> setMode(2), () -> setMode(3)});
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
        addRow(controls, new String[]{"Rotate", "Fullscreen", "Secure"},
                new Runnable[]{() -> setRequestedOrientation(
                        getResources().getConfiguration().orientation == 1
                                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
                        () -> {
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
            navHeight = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
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

    private void refresh() {
        if (root == null) return;
        boolean eligible = !ime && !transientWindow && !fullscreen && !secure;
        FrameLayout.LayoutParams bar = (FrameLayout.LayoutParams) backdrop.getLayoutParams();
        if (bar.height != navHeight) { bar.height = navHeight; backdrop.setLayoutParams(bar); }
        int footerHeight = Math.max(1, root.getWidth() * footer.getHeight() / footer.getWidth());
        FrameLayout.LayoutParams art = (FrameLayout.LayoutParams) footerView.getLayoutParams();
        if (art.height != footerHeight || art.bottomMargin != navHeight) {
            art.height = footerHeight; art.bottomMargin = navHeight; footerView.setLayoutParams(art);
        }
        backdrop.setVisibility(eligible && mode != 0 ? View.VISIBLE : View.INVISIBLE);
        backdrop.invalidate();
        int appearance = eligible && mode != 0
                ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        getWindow().getInsetsController().setSystemBarsAppearance(
                appearance, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        status.setText("Gesture backdrop lab | mode=" + mode + "\n"
                + "nav=" + navHeight + "px, ime=" + ime + ", transient=" + transientWindow
                + "\nfullscreen=" + fullscreen + ", secure=" + secure);
        Log.i("KeiHomeNavLab", "mode=" + mode + " eligible=" + eligible + " nav=" + navHeight);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("mode", mode);
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
            if (mode == 1) canvas.drawColor(average);
            else {
                canvas.drawBitmap(edge, null, new Rect(0, 0, getWidth(), getHeight()), paint);
                if (mode == 3) canvas.drawRect(0, 0, getWidth(), getHeight(), blurPaint);
            }
        }
    }
}
