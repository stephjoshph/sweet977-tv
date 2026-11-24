package fm.sweet977.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.AnimatorSet;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The LED screen, drawn by the app itself (no web browser), so it runs on small TVs (1 GB of memory).
 * Every 15 s it reads api/led.php from the website: the station, colours, lock and video-loop state, and the
 * Center stage items with their content already worked out. It shows the items one after another, each for its time.
 */
public class LedActivity extends Activity {
    static final String PREFS = MainActivity.PREFS, AUTOSTART = MainActivity.AUTOSTART;
    private static final long POLL_MS = 1000, REPORT_MS = 5 * 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(), img = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private FrameLayout root, stage;
    private LinearLayout cover, card, rows;
    private TextView coverTitle, coverSub, big1, big2, eye, title, sub;
    private ImageView coverLogo, pic, lockLogo, bgPic;
    private ProgressBar coverBar;
    private final Glow[] glows = new Glow[3];
    private View lockView, blackView, grain, vignette;
    private VideoView video;
    private Typeface anton, bold, semi;
    private float k = 1; // the screen's size against the 1920 × 1080 design the website uses
    private float stageScale = 1; private boolean small = false;
    private long lastFrame = 0;

    private JSONObject feed;
    private String feedH = "";
    private List<JSONObject> items = new ArrayList<>();
    private int idx = -1;
    private long slideEnds = 0;
    private String shownLogo = "", shownVideo = "", shownBg = "";
    private boolean started = false;
    private long backAt = 0, lastFeedOk = 0, lastFull = 0;
    private final Map<String, Bitmap> pics = new HashMap<>();
    private final java.util.Set<String> loading = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private int acc = Color.rgb(255, 22, 34), ink = Color.WHITE;
    private Glass glass;
    private JSONObject G = new JSONObject();
    private float g(String k, double d) { return (float) G.optDouble(k, d); }
    private TextView verLine;
    private long lastCmdReport = 0;
    private String tvId() { String id = prefs.getString("tvId", ""); if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16); prefs.edit().putString("tvId", id).apply(); } return id; }

    /** A soft colour glow: a radial fade (what the website draws with a 140 px blur, without its cost). */
    static final class Glow extends View {
        int color = Color.RED;
        Glow(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f; if (r <= 0) return;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(getWidth() / 2f, getHeight() / 2f, r, new int[]{color, alpha(color, (int) (Color.alpha(color) * 0.55f)), alpha(color, 0)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try { anton = Typeface.createFromAsset(getAssets(), "fonts/Anton.ttf"); } catch (Throwable e) { anton = Typeface.DEFAULT_BOLD; }
        try { bold = Typeface.createFromAsset(getAssets(), "fonts/Inter-Bold.ttf"); } catch (Throwable e) { bold = Typeface.DEFAULT_BOLD; }
        try { semi = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.ttf"); } catch (Throwable e) { semi = Typeface.DEFAULT; }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        k = Math.min(Math.max(dm.widthPixels, dm.heightPixels) / 1920f, Math.min(dm.widthPixels, dm.heightPixels) / 1080f);
        try { android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE); android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); if (am != null) am.getMemoryInfo(mi);
            small = mi.totalMem > 0 && mi.totalMem < 1800L * 1024 * 1024; } catch (Throwable ignored) { }
        if (small && k > 1280f / 1920f) { stageScale = k / (1280f / 1920f); k = 1280f / 1920f; }
        build();
        setContentView(root);
        hideBars();
        setCover("Updating", "Connecting to " + Site.host(this) + "…", 3);
        ui.post(tick);
        ui.post(poll);
        android.view.Choreographer.getInstance().postFrameCallback(frame);
        ui.postDelayed(report, 20_000);
        checkSite();
        ui.postDelayed(new Runnable() { @Override public void run() { askAutostartOnce(); } }, 8000);
    }

    private float px(float v) { return v * k; }
    private void size(TextView t, float v) { t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(v)); }
    static int alpha(int c, int a) { return Color.argb(Math.max(0, Math.min(255, a)), Color.red(c), Color.green(c), Color.blue(c)); }
    private static int col(JSONObject o, String key, int def) { try { String v = o.optString(key); return v.isEmpty() ? def : Color.parseColor(v); } catch (Throwable e) { return def; } }
    private FrameLayout.LayoutParams at(float x, float y, float w, float h) { FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) px(w), (int) px(h)); lp.leftMargin = (int) px(x); lp.topMargin = (int) px(y); return lp; }

    /* ── the Glass look, laid out like the website's LED page (1920 × 1080) ── */
    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        stage.setClipChildren(true);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams((int) px(1920), (int) px(1080), Gravity.CENTER);
        root.addView(stage, sl);
        stage.setScaleX(stageScale); stage.setScaleY(stageScale); // a small TV: drawn at 1280 × 720, enlarged by the TV

        float[][] g = {{-332, 255, 1227}, {1144, -365, 1013}, {446, 416, 873}};
        for (int i = 0; i < 3; i++) { glows[i] = new Glow(this); stage.addView(glows[i], at(g[i][0], g[i][1], g[i][2], g[i][2])); }

        bgPic = new ImageView(this); bgPic.setScaleType(ImageView.ScaleType.FIT_CENTER); bgPic.setVisibility(View.GONE);
        stage.addView(bgPic, new FrameLayout.LayoutParams((int) px(1600), (int) px(900)));
        big1 = text(anton, 760, Color.WHITE); big1.setSingleLine(true); big1.setLetterSpacing(-0.01f); big1.setAllCaps(true);
        FrameLayout.LayoutParams b1 = new FrameLayout.LayoutParams(-2, -2); b1.leftMargin = (int) px(24); b1.topMargin = (int) px(-60);
        stage.addView(big1, b1);
        big2 = text(anton, 300, Color.argb(18, 255, 255, 255)); big2.setSingleLine(true); big2.setAllCaps(true); big2.setLetterSpacing(0.01f);
        FrameLayout.LayoutParams b2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); b2.bottomMargin = (int) px(-70);
        stage.addView(big2, b2);

        grain = new View(this); stage.addView(grain, new FrameLayout.LayoutParams(-1, -1)); grain.setBackground(noise());
        vignette = new View(this) { @Override protected void onDraw(Canvas cv) { // dark edges
            Paint p = new Paint(); p.setShader(new RadialGradient(getWidth() / 2f, getHeight() / 2f, Math.max(getWidth(), getHeight()) * 0.75f, new int[]{0, 0, Color.argb(150, 0, 0, 0)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, getWidth(), getHeight(), p); } };
        stage.addView(vignette, new FrameLayout.LayoutParams(-1, -1));

        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding((int) px(76), (int) px(56), (int) px(76), (int) px(64));
        if (Build.VERSION.SDK_INT >= 23) card.setForeground(wipe);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() { @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, (int) px(30), v.getWidth(), v.getHeight() + (int) px(20), px(g("radius", 56))); o.setAlpha(0.75f); } });
        card.setElevation(px(60));
        if (Build.VERSION.SDK_INT >= 28) { card.setOutlineSpotShadowColor(Color.BLACK); card.setOutlineAmbientShadowColor(Color.BLACK); }
        stage.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        eye = text(bold, 24, Color.WHITE); eye.setAllCaps(true); eye.setLetterSpacing(0.22f); eye.setGravity(Gravity.CENTER_VERTICAL);
        eye.setPadding((int) px(18), (int) px(11), (int) px(22), (int) px(11)); eye.setCompoundDrawablePadding((int) px(12));
        card.addView(eye, wrap(0));
        title = text(anton, 230, Color.WHITE); title.setShadowLayer(px(40), 0, px(26), Color.argb(140, 0, 0, 0)); title.setGravity(Gravity.CENTER); title.setAllCaps(true); title.setLineSpacing(0, 0.86f); title.setMaxWidth((int) px(1560));
        card.addView(title, wrap(26));
        pic = new ImageView(this); pic.setScaleType(ImageView.ScaleType.FIT_CENTER); pic.setAdjustViewBounds(true); pic.setVisibility(View.GONE);
        card.addView(pic, wrap(26));
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(rows, wrap(0));
        sub = text(semi, 40, Color.argb(205, 255, 255, 255)); sub.setGravity(Gravity.CENTER); sub.setMaxLines(2); sub.setMaxWidth((int) px(1500));
        card.addView(sub, wrap(26));
        card.setVisibility(View.INVISIBLE);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVisibility(View.GONE);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { @Override public void onPrepared(MediaPlayer mp) { mp.setLooping(true); mp.setVolume(0, 0); } });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() { @Override public boolean onError(MediaPlayer mp, int a, int b) { shownVideo = ""; video.setVisibility(View.GONE); return true; } });

        LinearLayout lock = new LinearLayout(this); lock.setGravity(Gravity.CENTER); lock.setBackgroundColor(Color.BLACK);
        lockLogo = new ImageView(this); lockLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lock.setOrientation(LinearLayout.VERTICAL);
        lock.addView(lockLogo, new LinearLayout.LayoutParams((int) px(760), (int) px(460)));
        verLine = text(semi, 26, Color.argb(150, 255, 255, 255)); verLine.setLetterSpacing(0.04f); lock.addView(verLine, wrap(34)); // App 1.0.20 · Website 261124.07
        lockView = lock; lockView.setVisibility(View.GONE);
        root.addView(lockView, new FrameLayout.LayoutParams(-1, -1));
        blackView = new View(this); blackView.setBackgroundColor(Color.BLACK); blackView.setVisibility(View.GONE);
        root.addView(blackView, new FrameLayout.LayoutParams(-1, -1));

        cover = new LinearLayout(this); cover.setOrientation(LinearLayout.VERTICAL); cover.setGravity(Gravity.CENTER); cover.setBackgroundColor(Color.rgb(7, 4, 5));
        coverLogo = new ImageView(this); coverLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverLogo.setImageResource(R.drawable.logo);
        File bf = new File(getFilesDir(), "brand-logo");
        if (bf.exists()) { Bitmap b = decode(bf, 600); if (b != null) coverLogo.setImageBitmap(b); }
        cover.addView(coverLogo, new LinearLayout.LayoutParams((int) px(360), (int) px(260)));
        coverTitle = text(bold, 52, Color.WHITE); cover.addView(coverTitle, wrap(28));
        coverBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); coverBar.setMax(100);
        if (Build.VERSION.SDK_INT >= 21) coverBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 22, 34)));
        cover.addView(coverBar, new LinearLayout.LayoutParams((int) px(620), (int) px(14)));
        coverSub = text(semi, 28, Color.argb(190, 255, 255, 255)); coverSub.setGravity(Gravity.CENTER);
        cover.addView(coverSub, wrap(22));
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        applyLook(new JSONObject(), new JSONObject());
    }

    /** The card's frosted glass: the background behind it, blurred (drawn small and soft, then stretched), under the card colour. */
    final class Glass extends Drawable {
        static final int D = 12; // 1920 × 1080 drawn at 160 × 90
        Bitmap snap; int tint = Color.argb(72, 30, 6, 10); final Paint pb = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), pt = new Paint(Paint.ANTI_ALIAS_FLAG), pe = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path clip = new android.graphics.Path(); final android.graphics.Matrix inv = new android.graphics.Matrix();
        void snap() {
            int w = Math.max(1, stage.getWidth() / D), h = Math.max(1, stage.getHeight() / D);
            if (snap == null || snap.getWidth() != w || snap.getHeight() != h) snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(snap); c.drawColor(((android.graphics.drawable.ColorDrawable) stage.getBackground()).getColor()); c.scale(1f / D, 1f / D);
            for (View v : new View[]{glows[0], glows[1], glows[2], bgPic, big1, big2}) {
                if (v.getVisibility() != View.VISIBLE) continue;
                int sc = c.save(); c.translate(v.getLeft(), v.getTop()); c.concat(v.getMatrix());
                if (v.getAlpha() < 1) c.saveLayerAlpha(0, 0, v.getWidth(), v.getHeight(), (int) (v.getAlpha() * 255));
                v.draw(c); c.restoreToCount(sc);
            }
            blur(snap, Math.max(1, Math.min(8, (int) g("blur", 3)))); invalidateSelf();
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds(); float r = px(g("radius", 56));
            clip.reset(); clip.addRoundRect(new android.graphics.RectF(b), r, r, android.graphics.Path.Direction.CW);
            c.save(); c.clipPath(clip);
            if (snap != null) { c.save(); card.getMatrix().invert(inv); c.concat(inv); c.translate(-card.getLeft(), -card.getTop()); c.scale(D, D); c.drawBitmap(snap, 0, 0, pb); c.restore(); }
            pt.setColor(tint); c.drawRect(b, pt);
            pt.setShader(new LinearGradient(b.left, b.top, b.right, b.top + b.width() * 0.27f, new int[]{0, Color.argb(56, 255, 255, 255), 0}, new float[]{0.4f, 0.5f, 0.6f}, Shader.TileMode.CLAMP)); // the gloss
            c.drawRect(b, pt); pt.setShader(null); c.restore();
            pe.setStyle(Paint.Style.STROKE); pe.setStrokeWidth(Math.max(1, px(1.2f))); pe.setColor(Color.argb(72, 255, 255, 255)); // the border
            android.graphics.RectF rf = new android.graphics.RectF(b); rf.inset(pe.getStrokeWidth() / 2, pe.getStrokeWidth() / 2); c.drawRoundRect(rf, r, r, pe);
            c.save(); c.clipRect(b.left, b.top, b.right, b.top + r); pe.setStrokeWidth(Math.max(1, px(1.5f))); pe.setColor(Color.argb(140, 255, 255, 255)); c.drawRoundRect(rf, r, r, pe); c.restore(); // the bright top edge
        }
        @Override public void setAlpha(int a) { } @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    /** The wipe: a band of light across the card (the website's shine), over the card's words too. */
    final class Wipe extends Drawable {
        float pos = -2; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final android.graphics.Path clip = new android.graphics.Path();
        @Override public void draw(Canvas c) {
            if (pos <= -1.3f || pos >= 1.3f) return;
            android.graphics.Rect b = getBounds(); float r = px(g("radius", 56)), w = b.width(), x = b.left + w * pos;
            clip.reset(); clip.addRoundRect(new android.graphics.RectF(b), r, r, android.graphics.Path.Direction.CW);
            c.save(); c.clipPath(clip);
            float dx = b.height() * 0.18f; // skewed like the website's 100° band
            p.setShader(new LinearGradient(x, b.top, x + w + dx, b.top + dx * 0.4f, new int[]{0, Color.argb(20, 255, 255, 255), Color.argb(107, 255, 255, 255), Color.argb(20, 255, 255, 255), 0}, new float[]{0.3f, 0.42f, 0.5f, 0.58f, 0.7f}, Shader.TileMode.CLAMP));
            c.drawRect(b, p); c.restore();
        }
        @Override public void setAlpha(int a) { } @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private final Wipe wipe = new Wipe();
    private float wipeEvery = 7;

    /** One letter of a title, rising and fading in after the ones before it (the website's letter animation). */
    final class Letter extends android.text.style.ReplacementSpan {
        final int i; Letter(int i) { this.i = i; }
        @Override public int getSize(Paint p, CharSequence t, int s, int e, Paint.FontMetricsInt fm) { if (fm != null) p.getFontMetricsInt(fm); return Math.round(p.measureText(t, s, e)); }
        @Override public void draw(Canvas c, CharSequence t, int s, int e, float x, int top, int y, int bot, Paint p) {
            float f = Math.max(0, Math.min(1, (SystemClock.uptimeMillis() - lettersT0 - i * 38) / 620f)), q = 1 - (float) Math.pow(1 - f, 3);
            int a = p.getAlpha(); p.setAlpha((int) (a * q));
            c.save(); c.translate(0, (1 - q) * p.getTextSize() * 0.45f); c.rotate((1 - q) * 8, x, y); c.drawText(t, s, e, x, y, p); c.restore(); p.setAlpha(a);
        }
    }
    private long lettersT0 = 0; private int lettersN = 0;
    private void letters(String txt) {
        if (txt == null || txt.isEmpty() || !G.optBoolean("letters", true)) { title.setText(txt == null ? "" : txt.toUpperCase(Locale.US)); lettersN = 0; return; }
        String u = txt.toUpperCase(Locale.US); android.text.SpannableString sp = new android.text.SpannableString(u); int n = 0;
        for (int i = 0; i < u.length(); i++) if (!Character.isWhitespace(u.charAt(i))) sp.setSpan(new Letter(n++), i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        lettersT0 = SystemClock.uptimeMillis() + 300; lettersN = n; title.setText(sp);
    }

    /** A QR code (black on white) for the QR item. */
    private Bitmap qr(String text, int size) {
        try { com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.MARGIN, 2));
            int w = m.getWidth(), h = m.getHeight(); int[] px = new int[w * h]; for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) { return null; }
    }

    /** A quick box blur, a few passes, for the small glass picture. */
    private static void blur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight(); int[] p = new int[w * h], q = new int[w * h]; b.getPixels(p, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int ra = 0, ga = 0, ba = 0, n = 0; for (int d = -r; d <= r; d++) { int xx = Math.min(w - 1, Math.max(0, x + d)), c = p[y * w + xx]; ra += (c >> 16) & 255; ga += (c >> 8) & 255; ba += c & 255; n++; } q[y * w + x] = 0xff000000 | ((ra / n) << 16) | ((ga / n) << 8) | (ba / n); }
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int ra = 0, ga = 0, ba = 0, n = 0; for (int d = -r; d <= r; d++) { int yy = Math.min(h - 1, Math.max(0, y + d)), c = q[yy * w + x]; ra += (c >> 16) & 255; ga += (c >> 8) & 255; ba += c & 255; n++; } p[y * w + x] = 0xff000000 | ((ra / n) << 16) | ((ga / n) << 8) | (ba / n); }
        }
        b.setPixels(p, 0, w, 0, 0, w, h);
    }

    private Drawable noise() { // fine film grain, tiled
        int n = 192; Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); java.util.Random r = new java.util.Random(7);
        int[] px = new int[n * n]; for (int i = 0; i < px.length; i++) { int v = r.nextInt(256); px[i] = Color.argb(r.nextInt(34), v, v, v); }
        b.setPixels(px, 0, n, 0, 0, n, n);
        BitmapDrawable d = new BitmapDrawable(getResources(), b); d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); return d;
    }

    private TextView text(Typeface tf, float sizePx, int color) {
        TextView t = new TextView(this); t.setTypeface(tf); size(t, sizePx); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }
    private LinearLayout.LayoutParams wrap(float top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = Gravity.CENTER_HORIZONTAL; p.topMargin = (int) px(top); return p; }

    /* ── colours and settings from Studio → LED screen (Glass) ── */
    private float motionSpeed = 1, motionSway = 110;
    private String lookSig = "";
    private void applyLook(JSONObject cs, JSONObject lk) {
        String sig = cs.toString() + lk.toString() + G.toString(); if (sig.equals(lookSig)) return; lookSig = sig;
        int bg = col(cs, "bg", Color.rgb(7, 4, 5)); acc = col(cs, "acc", Color.rgb(255, 22, 34)); ink = col(cs, "ink", Color.WHITE);
        stage.setBackgroundColor(bg);
        float go = g("glowOp", 0.9); int[] gc = {alpha(acc, (int) (255 * go)), alpha(col(cs, "b2", Color.rgb(165, 13, 23)), (int) (241 * go)), alpha(col(cs, "b3", Color.rgb(91, 7, 16)), (int) (227 * go))};
        for (int i = 0; i < 3; i++) { glows[i].color = gc[i]; glows[i].invalidate(); }
        // the giant words: a red gradient across them (the website's colours)
        JSONArray bc = cs.optJSONArray("big"); final int[] cols = new int[5]; String[] def = {"#ff2a36", "#ff1b28", "#4a2a2e", "#3a2326", "#8e1a22"};
        for (int i = 0; i < 5; i++) { try { cols[i] = Color.parseColor(bc != null && i < bc.length() ? bc.optString(i) : def[i]); } catch (Throwable e) { cols[i] = Color.parseColor(def[i]); } }
        big1.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            big1.getPaint().setShader(new LinearGradient(0, 0, Math.max(1, r - l), (r - l) * 0.087f, cols, new float[]{0f, .26f, .52f, .78f, 1f}, Shader.TileMode.CLAMP)); big1.invalidate(); } });
        big1.getPaint().setShader(new LinearGradient(0, 0, Math.max(1, big1.getWidth()), 0, cols, new float[]{0f, .26f, .52f, .78f, 1f}, Shader.TileMode.CLAMP));
        big1.setAlpha(g("bigOp", 0.92)); big1.invalidate();
        // the glass card: a light sheen over the card colour, a fine light edge
        String cardHex = cs.optString("card", "");
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.argb(51, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.argb(18, 255, 255, 255)});
        sheen.setCornerRadius(px(g("radius", 56))); sheen.setStroke((int) Math.max(1, px(1.6f)), Color.argb(46, 255, 255, 255));
        if (glass == null) glass = new Glass();
        glass.tint = cardHex.isEmpty() ? Color.argb((int) g("tint", 72), 30, 6, 10) : alpha(col(cs, "card", Color.rgb(30, 6, 10)), 209);
        card.setBackground(new LayerDrawable(new Drawable[]{glass, sheen}));
        GradientDrawable pill = new GradientDrawable(); pill.setColor(alpha(acc, 230)); pill.setCornerRadius(px(60)); eye.setBackground(pill);
        eye.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND); eye.setElevation(px(16));
        if (Build.VERSION.SDK_INT >= 28) { eye.setOutlineSpotShadowColor(acc); eye.setOutlineAmbientShadowColor(acc); }
        wipeEvery = (float) lk.optDouble("wipe", 7);
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(Color.WHITE); dot.setSize((int) px(11), (int) px(11));
        eye.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
        title.setTextColor(ink); sub.setTextColor(alpha(ink, 205));
        size(big1, g("big", 760)); size(big2, g("big2", 300)); big2.setTextColor(Color.argb((int) g("big2Op", 18), 255, 255, 255));
        FrameLayout.LayoutParams b1 = (FrameLayout.LayoutParams) big1.getLayoutParams(); b1.leftMargin = (int) px(g("bigX", 24)); b1.topMargin = (int) px(g("bigY", -60)); big1.setLayoutParams(b1);
        card.setPadding((int) px(g("padX", 76)), (int) px(g("padY", 56)), (int) px(g("padX", 76)), (int) px(g("padY", 56) + 8));
        for (int i = 1; i < card.getChildCount(); i++) { LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getChildAt(i).getLayoutParams(); if (card.getChildAt(i) != rows) { lp.topMargin = (int) px(g("gap", 26)); card.getChildAt(i).setLayoutParams(lp); } }
        // motion: the giant words sway; the glows drift (Studio → Sway and Speed)
        float speed = (float) Math.max(0.2, Math.min(4, lk.optDouble("speed", 1))), sw = (float) lk.optDouble("sway", 110);
        motionSpeed = speed; motionSway = sw;
        grain.setVisibility(lk.optBoolean("grain", true) ? View.VISIBLE : View.GONE); grain.setAlpha((float) Math.max(0, Math.min(1, lk.optDouble("grainK", 0.8))) * g("grain", 0.9));
        // a picture instead of the giant words (Studio → Background picture: position, size, opacity)
        String bgu = lk.optString("bgImg", "");
        if (!bgu.equals(shownBg)) {
            shownBg = bgu;
            if (bgu.isEmpty()) { bgPic.setVisibility(View.GONE); big1.setVisibility(View.VISIBLE); big2.setVisibility(View.VISIBLE); }
            else { big1.setVisibility(View.GONE); big2.setVisibility(View.GONE); picture(bgu, 1600, new Got() { @Override public void got(Bitmap b) { bgPic.setImageBitmap(b); bgPic.setVisibility(View.VISIBLE); } }); }
        }
        if (!bgu.isEmpty()) {
            float sc = (float) lk.optDouble("bgScale", 100) / 100f, w = 1600 * sc, h = 900 * sc;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) px(w), (int) px(h));
            lp.leftMargin = (int) px((float) (1920 * lk.optDouble("bgX", 39) / 100 - w / 2)); lp.topMargin = (int) px((float) (1080 * lk.optDouble("bgY", 33) / 100 - h / 2));
            bgPic.setLayoutParams(lp); bgPic.setAlpha((float) Math.max(0, Math.min(1, lk.optDouble("bgOp", 92) / 100)));
        }
    }

    /* ── the Updating screen ── */
    private void setCover(String t, String s, int pct) {
        coverTitle.setText(t); coverSub.setText(s == null ? "" : s);
        coverBar.setVisibility(pct < 0 ? View.GONE : View.VISIBLE); if (pct >= 0) coverBar.setProgress(pct);
        cover.setAlpha(1); cover.setVisibility(View.VISIBLE);
    }
    private void hideCover() { if (cover.getVisibility() == View.VISIBLE) tween(cover, A, 1, 0, 0, 700, LIN, new Runnable() { @Override public void run() { cover.setVisibility(View.GONE); cover.setAlpha(1); } }); }

    /* ── the website, every second: "anything changed?" (a tiny answer when not) ── */
    private volatile boolean asking = false;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!asking) {
                asking = true;
                final String since = SystemClock.elapsedRealtime() - lastFull > 10 * 60_000 ? "" : feedH; // a full copy at least every 10 minutes
                net.execute(new Runnable() { @Override public void run() {
                    final JSONObject j = get(Site.get(LedActivity.this) + "api/led.php?tv=" + tvId() + (since.isEmpty() ? "" : "&since=" + since));
                    ui.post(new Runnable() { @Override public void run() { asking = false; onFeed(j); } });
                } });
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    private JSONObject get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000); c.setReadTimeout(12_000); c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "SweetTV/" + Updater.currentName(this) + " (Android " + Build.VERSION.RELEASE + ")");
            if (c.getResponseCode() != 200) return null;
            JSONObject j = new JSONObject(new String(Store.readAll(c.getInputStream()), "UTF-8"));
            return j.optBoolean("ok") ? j : null;
        } catch (Throwable e) { return null; } finally { if (c != null) c.disconnect(); }
    }

    private void onFeed(JSONObject j) {
        if (j == null) {
            if (feed == null && !started) setCover("Waiting for the internet", "The LED screen starts as soon as " + Site.host(this) + " can be reached.", -1);
            return;
        }
        lastFeedOk = SystemClock.elapsedRealtime();
        if (j.optBoolean("cmd") && SystemClock.elapsedRealtime() - lastCmdReport > 8000) { lastCmdReport = SystemClock.elapsedRealtime(); ui.removeCallbacks(report); ui.post(report); } // the developer asked something
        if (j.optBoolean("same")) return; // nothing changed
        feedH = j.optString("h", ""); lastFull = lastFeedOk; feed = j;
        JSONObject cs = j.optJSONObject("colors"), lk = j.optJSONObject("look"); if (j.optJSONObject("g") != null) G = j.optJSONObject("g");
        applyLook(cs == null ? new JSONObject() : cs, lk == null ? new JSONObject() : lk);
        big1.setText(j.optString("big", "97.7")); big2.setText(j.optString("big2", "FM"));
        String lg = j.optString("logo", "");
        if (!lg.equals(shownLogo)) { shownLogo = lg; picture(lg, 900, new Got() { @Override public void got(Bitmap b) { lockLogo.setImageBitmap(b); coverLogo.setImageBitmap(b); saveBrand(b); } }); }
        List<JSONObject> list = new ArrayList<>(); JSONArray a = j.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) list.add(o); }
        String curId = idx >= 0 && idx < items.size() ? items.get(idx).optString("id") : "";
        items = list; idx = -1; for (int i = 0; i < items.size(); i++) if (items.get(i).optString("id").equals(curId)) idx = i;
        if (idx >= 0 && started) update(items.get(idx)); // new words for the item on screen show right away
        if (items.isEmpty()) card.setVisibility(View.INVISIBLE);
        lockView.setVisibility(j.optBoolean("lock") ? View.VISIBLE : View.GONE);
        verLine.setText("App " + Updater.currentName(this) + "  ·  Website " + j.optString("site", "?"));
        blackView.setVisibility(j.optBoolean("black") ? View.VISIBLE : View.GONE);
        playVideo(j.optString("video", ""));
        tidy(j);
        if (!started) prepare();
    }

    /* the first time: save every picture the screen uses, with the progress bar (like the website's LED page), then start */
    private void prepare() {
        final List<String> need = urls(feed); need.remove(feed.optString("video", ""));
        final int[] done = {0}; final int n = need.size();
        if (n == 0) { start(); return; }
        setCover("Updating", "Saving the LED screen's files on this TV…", 5);
        for (final String u : need) picture(u, 1600, null);
        ui.post(new Runnable() { final long t0 = SystemClock.elapsedRealtime(); @Override public void run() {
            int have = 0; for (String u : need) if (pics.containsKey(u) || cacheFile(u, "").exists()) have++;
            setCover("Updating", "Saving files on this screen: " + Math.min(n, have + 1) + " of " + n, 5 + 90 * have / n);
            if (have >= n || SystemClock.elapsedRealtime() - t0 > 45_000) { coverBar.setProgress(100); start(); } else ui.postDelayed(this, 400);
        } });
    }
    private void start() { started = true; hideCover(); slideEnds = 0; }

    /* every address the content uses (pictures, the logo, the video) */
    private static List<String> urls(JSONObject j) {
        List<String> o = new ArrayList<>(); if (j == null) return o;
        String[] one = {j.optString("logo"), j.optJSONObject("look") != null ? j.optJSONObject("look").optString("bgImg") : "", j.optString("video")};
        for (String s : one) if (s != null && !s.isEmpty() && !s.startsWith("data:") && !o.contains(s)) o.add(s);
        JSONArray a = j.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject it = a.optJSONObject(i); if (it == null) continue;
            String im = it.optString("img"); if (!im.isEmpty() && !im.startsWith("data:") && !o.contains(im)) o.add(im);
            JSONArray ims = it.optJSONArray("imgs"); if (ims != null) for (int x = 0; x < ims.length(); x++) { String s = ims.optString(x); if (!s.isEmpty() && !o.contains(s)) o.add(s); } }
        return o;
    }

    /* files the screen no longer uses are deleted (and dropped from memory) */
    private void tidy(JSONObject j) {
        final java.util.Set<String> keep = new java.util.HashSet<>();
        for (String u : urls(j)) { keep.add(sha1(u)); keep.add(sha1(u) + ".mp4"); }
        img.execute(new Runnable() { @Override public void run() {
            File[] all = new File(getFilesDir(), "led").listFiles(); if (all == null) return; long now = System.currentTimeMillis();
            for (File f : all) if (!keep.contains(f.getName()) && now - f.lastModified() > 10 * 60_000) f.delete();
        } });
        java.util.Set<String> live = new java.util.HashSet<>(urls(j));
        for (String u : new ArrayList<>(pics.keySet())) if (!live.contains(u)) pics.remove(u);
    }

    /* ── one item after another, each for its time ── */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                if (started && feed != null && !items.isEmpty() && now >= slideEnds) next();
                else if (started && idx >= 0 && idx < items.size()) live(items.get(idx)); // clock and countdown count every second
            } catch (Throwable ignored) { }
            ui.postDelayed(this, 1000);
        }
    };

    private void next() {
        idx = (idx + 1) % items.size();
        final JSONObject it = items.get(idx);
        slideEnds = SystemClock.elapsedRealtime() + Math.max(3, it.optInt("dur", 10)) * 1000L;
        if (card.getVisibility() != View.VISIBLE) { enter(it); return; }
        stopTweens(card);
        long ot = (long) g("out", 420); tween(card, A, card.getAlpha(), 0, 0, ot, IN, null); tween(card, S, card.getScaleX(), 0.96f, 0, ot, IN, null);
        tween(card, Y, card.getTranslationY(), px(-24), 0, ot, IN, new Runnable() { @Override public void run() { enter(it); } });
    }

    /* the glass card comes in; then its parts rise in one after another (the website's entrance) */
    private void enter(JSONObject it) {
        stopTweens(card);
        animate = true; show(it); animate = false;
        card.setVisibility(View.VISIBLE); card.setAlpha(0); card.setScaleX(0.9f); card.setScaleY(0.9f); card.setTranslationY(px(50));
        long et = (long) g("enter", 1150); tween(card, A, 0, 1, 0, et, OUT, null); tween(card, Y, px(50), 0, 0, et, OUT, null);
        tween(card, S, 0.9f, 1, 0, et, OUT, new Runnable() { @Override public void run() {
            long d = Math.max(1000, slideEnds - SystemClock.elapsedRealtime());
            tween(card, S, 1f, g("push", 1.035), 0, d, LIN, null); // a slow push in
        } });
        int n = 0;
        for (int i = 0; i < card.getChildCount(); i++) { View v = card.getChildAt(i); if (v.getVisibility() != View.VISIBLE) continue; rise(v, 250 + n * (long) g("riseGap", 90)); n++; }
        for (int i = 0; i < rows.getChildCount(); i++) rise(rows.getChildAt(i), 420 + i * 110);
    }
    private void rise(View v, long delay) { stopTweens(v); v.setAlpha(0); v.setTranslationY(px(40)); long rt = (long) g("rise", 1000); tween(v, A, 0, 1, delay, rt, OUT, null); tween(v, Y, px(40), 0, delay, rt, OUT, null); }

    /* ── motion, drawn frame by frame by the app itself: it runs even when the TV has Android's animations turned off ── */
    static final int A = 0, X = 1, Y = 2, S = 3, IN = 0, OUT = 1, LIN = 2;
    static final class Tw { View v; int prop, ease; float a, b; long t0, dur; Runnable end; }
    private final List<Tw> tws = new ArrayList<>();
    private void tween(View v, int prop, float from, float to, long delay, long dur, int ease, Runnable end) {
        Tw t = new Tw(); t.v = v; t.prop = prop; t.a = from; t.b = to; t.t0 = SystemClock.uptimeMillis() + delay; t.dur = Math.max(1, dur); t.ease = ease; t.end = end;
        for (int i = tws.size() - 1; i >= 0; i--) { Tw o = tws.get(i); if (o.v == v && o.prop == prop) tws.remove(i); }
        tws.add(t);
    }
    private void stopTweens(View v) { for (int i = tws.size() - 1; i >= 0; i--) if (tws.get(i).v == v) tws.remove(i); }
    private static void set(View v, int prop, float x) { if (prop == A) v.setAlpha(x); else if (prop == X) v.setTranslationX(x); else if (prop == Y) v.setTranslationY(x); else { v.setScaleX(x); v.setScaleY(x); } }
    private static float ease(int e, float x) { return e == IN ? x * x : e == OUT ? 1 - (float) Math.pow(1 - x, 3) : x; }

    private long lastGlass = 0;
    private final android.view.Choreographer.FrameCallback frame = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long ns) {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < 30) { android.view.Choreographer.getInstance().postFrameCallback(this); return; } // a steady 30 frames a second
            lastFrame = now;
            try {
                // the background: the glows drift and grow, the giant words sway (Studio → Sway and Speed)
                float[][] mv = {{380, -260, 26}, {-300, 220, 32}, {260, -200, 21}};
                for (int i = 0; i < 3; i++) { float e = wave(now, mv[i][2] * 1000 / motionSpeed); glows[i].setTranslationX(px(mv[i][0]) * e); glows[i].setTranslationY(px(mv[i][1]) * e); glows[i].setScaleX(1 + 0.2f * e); glows[i].setScaleY(1 + 0.2f * e); }
                big1.setTranslationX(px(motionSway) * (1 - 2 * wave(now, 30_000 / motionSpeed)));
                bgPic.setTranslationX(big1.getTranslationX());
                // the moving parts
                for (int i = 0; i < tws.size(); i++) {
                    Tw t = tws.get(i); if (now < t.t0) { if (t.prop == A) set(t.v, A, t.a); continue; }
                    float x = Math.min(1, (now - t.t0) / (float) t.dur); set(t.v, t.prop, t.a + (t.b - t.a) * ease(t.ease, x));
                    if (x >= 1) { tws.remove(i--); if (t.end != null) t.end.run(); }
                }
                if (now - lastGlass > (small ? 200 : 140) && card.getVisibility() == View.VISIBLE) { lastGlass = now; glass.snap(); } // the frosted glass behind the card
                if (lettersN > 0 && now - lettersT0 < lettersN * 38L + 700) title.invalidate(); // the letters coming in
                if (wipeEvery > 0) { long per = (long) (wipeEvery * 1000), ph = now % per; float np = ph < 1500 ? -1.3f + 2.6f * (float) (0.5 - 0.5 * Math.cos(Math.PI * ph / 1500.0)) : -2f;
                    if (np != wipe.pos) { wipe.pos = np; card.invalidate(); } }
            } catch (Throwable ignored) { }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
    /** 0 → 1 → 0, smoothly, once every 2 × period (the website's "alternate" ease-in-out). */
    private static float wave(long now, float period) { double ph = (now % (long) (2 * period)) / (double) period; return (float) ((1 - Math.cos(Math.PI * ph)) / 2); }

    private void refresh(JSONObject it) { if (started && card.getVisibility() == View.VISIBLE) update(it); }
    /** New content for the item on screen, without the entrance. */
    private void update(JSONObject it) { show(it); for (int i = 0; i < card.getChildCount(); i++) { card.getChildAt(i).setAlpha(1); card.getChildAt(i).setTranslationY(0); } }

    private static int fit(String t, int max) { // the website's sizing: big type shrinks for long names so words never break
        if (t == null) t = ""; int n = t.length(), longest = 1; for (String w : t.split("\\s+")) longest = Math.max(longest, w.length());
        return (int) Math.round(Math.min(max, Math.min(2600.0 / Math.max(n, 6) * 1.25, 1450.0 / longest * 1.7)));
    }

    private boolean animate = false;
    private void show(JSONObject it) {
        String t = it.optString("type"), e = it.optString("eye"), b = it.optString("big"), s = it.optString("sub"), u = it.optString("img");
        float kk = (float) Math.max(0.4, Math.min(2.5, it.optDouble("k", 1)));
        rows.removeAllViews(); pic.setVisibility(View.GONE); pic.setImageDrawable(null);
        title.setTypeface(anton); title.setAllCaps(false); title.setMaxLines(4); size(title, fit(b, (int) g("title", 230)) * kk);
        setText(eye, e); setText(title, b); if (b != null && !b.isEmpty() && !t.equals("clock") && !t.equals("countdown")) { if (animate) letters(b); else title.setText(b.toUpperCase(Locale.US)); } setText(sub, s); size(sub, g("sub", 40) * Math.min(1.3f, kk)); size(eye, g("eye", 24));
        if (t.equals("clock") || t.equals("countdown")) live(it);
        if (t.equals("poll") || t.equals("qr")) size(title, Math.min(g("mid", 104), fit(b, (int) g("mid", 104))) * kk); // the poll's question: the mid size
        if (t.equals("image") || t.equals("logo") || t.equals("sponsor")) { title.setVisibility(View.GONE); sub.setVisibility(View.GONE); if (!t.equals("sponsor")) eye.setVisibility(View.GONE); }
        if (t.equals("sponsor")) { JSONArray im = it.optJSONArray("imgs"); if (im != null && im.length() > 0) u = im.optString((int) ((System.currentTimeMillis() / 4000) % im.length())); }
        if (!u.isEmpty()) {
            Bitmap bm = pics.get(u);
            if (bm != null) {
                pic.setImageBitmap(bm); pic.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pic.getLayoutParams();
                if (t.equals("logo")) { int h = (int) px(it.optInt("h", 286) * kk); lp.height = h; lp.width = Math.round(h * bm.getWidth() / (float) Math.max(1, bm.getHeight())); }
                else if (t.equals("image")) { float r = Math.min(px(1500) / bm.getWidth(), px(780) / bm.getHeight()); lp.width = (int) (bm.getWidth() * r * kk); lp.height = (int) (bm.getHeight() * r * kk); }
                else if (t.equals("sponsor")) { float r = Math.min(px(900) / bm.getWidth(), px(380) / bm.getHeight()); lp.width = (int) (bm.getWidth() * r); lp.height = (int) (bm.getHeight() * r); }
                else { float r = Math.min(px(520) / bm.getWidth(), px(300) / bm.getHeight()); lp.width = (int) (bm.getWidth() * r); lp.height = (int) (bm.getHeight() * r); }
                pic.setLayoutParams(lp);
            } else picture(u, 1600, null);
        }
        if (t.equals("qr") && !it.optString("url").isEmpty()) {
            Bitmap q = qr(it.optString("url"), (int) px(440));
            if (q != null) { pic.setImageBitmap(q); pic.setVisibility(View.VISIBLE); GradientDrawable wb = new GradientDrawable(); wb.setColor(Color.WHITE); wb.setCornerRadius(px(28)); pic.setBackground(wb); int pd = (int) px(18); pic.setPadding(pd, pd, pd, pd);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pic.getLayoutParams(); lp.width = (int) px(440) + 2 * pd; lp.height = lp.width; pic.setLayoutParams(lp); }
        } else { pic.setBackground(null); pic.setPadding(0, 0, 0, 0); }
        JSONArray rs = it.optJSONArray("rows");
        if (rs != null) {
            if (t.equals("top3") || t.equals("social")) title.setVisibility(View.GONE);
            for (int i = 0; i < rs.length() && i < 6; i++) rows.addView(row(rs.optJSONObject(i), t, i, kk));
        }
    }

    private void live(JSONObject it) {
        String t = it.optString("type");
        TimeZone tz = TimeZone.getTimeZone(feed != null ? feed.optString("tz", "Asia/Manila") : "Asia/Manila");
        if (t.equals("clock")) {
            SimpleDateFormat d = new SimpleDateFormat("EEEE", Locale.US), h = new SimpleDateFormat("h:mm", Locale.US), a = new SimpleDateFormat("a", Locale.US), dt = new SimpleDateFormat("MMMM d, yyyy", Locale.US);
            d.setTimeZone(tz); h.setTimeZone(tz); a.setTimeZone(tz); dt.setTimeZone(tz); Date n = new Date();
            setText(eye, d.format(n)); title.setText(h.format(n) + " " + a.format(n)); size(title, g("title", 230)); title.setVisibility(View.VISIBLE); setText(sub, dt.format(n));
        } else if (t.equals("countdown")) {
            long ms = Math.max(0, it.optLong("at") - System.currentTimeMillis());
            long hh = ms / 3_600_000, mm = ms % 3_600_000 / 60_000, ss = ms % 60_000 / 1000;
            setText(eye, it.optString("eye", "Countdown")); title.setText((hh > 0 ? hh + ":" : "") + String.format(Locale.US, "%02d:%02d", mm, ss)); size(title, g("title", 230)); title.setVisibility(View.VISIBLE); setText(sub, "");
        }
    }

    private void setText(TextView v, String s) { v.setText(s); v.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE); }

    /* a row: Top 3 (rank, title, artist), poll option (with its share), or a social network */
    private View row(JSONObject r, String type, int i, float kk) {
        if (r == null) r = new JSONObject();
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams((int) px(type.equals("social") ? 900 : g("row", 1200)), -2); p.gravity = Gravity.CENTER_HORIZONTAL; p.topMargin = (int) px(i == 0 ? 30 : type.equals("poll") ? 16 : 22);
        l.setLayoutParams(p);
        if (type.equals("social")) { // the network's name small, the handle big
            LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView a = text(bold, 24, Color.rgb(255, 106, 90)); a.setAllCaps(true); a.setLetterSpacing(0.2f); a.setText(r.optString("sub")); c.addView(a, wrap(0));
            TextView b = text(anton, g("social", 92) * kk, ink); b.setText(r.optString("title")); c.addView(b, wrap(6));
            l.setGravity(Gravity.CENTER); l.addView(c, new LinearLayout.LayoutParams(-1, -2)); return l;
        }
        l.setPadding((int) px(34), (int) px(18), (int) px(34), (int) px(18));
        if (type.equals("poll")) {
            int pct = r.optInt("pct", 0);
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{alpha(acc, 110), alpha(acc, 110), Color.argb(18, 255, 255, 255), Color.argb(18, 255, 255, 255)});
            bg.setCornerRadius(px(26)); bg.setStroke((int) Math.max(1, px(1.5f)), Color.argb(40, 255, 255, 255));
            l.setBackground(pct > 0 ? new LayerDrawable(new Drawable[]{plain(), clip(pct)}) : plain());
            TextView a = text(semi, g("poll", 40) * kk, ink); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END); a.setText(r.optString("title")); l.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
            TextView b = text(bold, g("poll", 40) * kk, ink); b.setText(pct + "%"); l.addView(b);
            return l;
        }
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{alpha(acc, 89), Color.argb(10, 255, 255, 255)});
        bg.setCornerRadius(px(34)); bg.setStroke((int) Math.max(1, px(1.5f)), Color.argb(41, 255, 255, 255)); l.setBackground(bg);
        String n = r.optString("n");
        if (!n.isEmpty()) { TextView num = text(anton, g("rank", 120) * kk, Color.WHITE); num.setGravity(Gravity.CENTER); num.setText(n); l.addView(num, new LinearLayout.LayoutParams((int) px(110), -2)); }
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding((int) px(34), 0, 0, 0);
        TextView a = text(anton, g("rowTitle", 72) * kk, ink); a.setAllCaps(true); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END); a.setText(r.optString("title")); tx.addView(a);
        String sb = r.optString("sub"); if (!sb.isEmpty()) { TextView b = text(semi, g("rowSub", 30) * kk, alpha(ink, 205)); b.setSingleLine(true); b.setText(sb); LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = (int) px(6); tx.addView(b, bp); }
        l.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        return l;
    }
    private Drawable plain() { GradientDrawable d = new GradientDrawable(); d.setColor(Color.argb(20, 255, 255, 255)); d.setCornerRadius(px(26)); d.setStroke((int) Math.max(1, px(1.5f)), Color.argb(40, 255, 255, 255)); return d; }
    private Drawable clip(int pct) { GradientDrawable d = new GradientDrawable(); d.setColor(alpha(acc, 110)); d.setCornerRadius(px(26)); android.graphics.drawable.ClipDrawable c = new android.graphics.drawable.ClipDrawable(d, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL); c.setLevel(Math.max(0, Math.min(100, pct)) * 100); return c; }

    /* ── video loop ── */
    private void playVideo(final String url) {
        if (url.equals(shownVideo)) return;
        shownVideo = url;
        if (url.isEmpty()) { video.stopPlayback(); video.setVisibility(View.GONE); return; }
        final File f = cacheFile(url, ".mp4");
        if (f.exists()) { startVideo(Uri.fromFile(f)); return; }
        startVideo(Uri.parse(url)); // plays from the internet while it saves (if there's room)
        img.execute(new Runnable() { @Override public void run() { if (save(url, f, 400L << 20)) ui.post(new Runnable() { @Override public void run() { if (url.equals(shownVideo)) startVideo(Uri.fromFile(f)); } }); } });
    }
    private void startVideo(Uri u) { try { video.setVisibility(View.VISIBLE); video.setVideoURI(u); video.start(); } catch (Throwable e) { video.setVisibility(View.GONE); } }

    /* ── pictures: saved on the TV (while there's room), shrunk to the size they're shown at ── */
    interface Got { void got(Bitmap b); }
    private void picture(final String url, final int max, final Got done) {
        if (url == null || url.isEmpty()) return;
        Bitmap have = pics.get(url); if (have != null) { if (done != null) done.got(have); return; }
        if (!loading.add(url)) return;
        img.execute(new Runnable() { @Override public void run() {
            Bitmap b = null;
            try {
                if (url.startsWith("data:image/")) { byte[] raw = android.util.Base64.decode(url.substring(url.indexOf(",") + 1), android.util.Base64.DEFAULT); b = decodeBytes(raw, max); }
                else { File f = cacheFile(url, ""); if (f.exists() || save(url, f, 25L << 20)) b = decode(f, max); }
            } catch (Throwable ignored) { }
            final Bitmap fb = b; loading.remove(url);
            if (fb != null) ui.post(new Runnable() { @Override public void run() {
                if (pics.size() > 24) pics.clear(); // a small TV: keep only a few in memory
                pics.put(url, fb); if (done != null) done.got(fb);
                if (idx >= 0 && idx < items.size() && url.equals(items.get(idx).optString("img")) && pic.getVisibility() != View.VISIBLE) refresh(items.get(idx));
            } });
        } });
    }
    private File cacheFile(String url, String ext) { File d = new File(getFilesDir(), "led"); d.mkdirs(); return new File(d, sha1(url) + ext); }
    private boolean save(String url, File f, long maxBytes) {
        if (getFilesDir().getUsableSpace() < Store.RESERVE) return false; // never fill the TV
        HttpURLConnection c = null; File tmp = new File(f.getPath() + ".part");
        try {
            c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(15_000); c.setReadTimeout(30_000);
            if (c.getResponseCode() != 200) return false;
            long len = c.getContentLength(); if (len > maxBytes || getFilesDir().getUsableSpace() - Math.max(0, len) < Store.RESERVE) return false;
            InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp); byte[] buf = new byte[32 * 1024]; long tot = 0; int n;
            try { while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); tot += n; if (tot > maxBytes) return false; } } finally { out.close(); in.close(); }
            return tmp.renameTo(f);
        } catch (Throwable e) { return false; } finally { if (c != null) c.disconnect(); tmp.delete(); }
    }
    private static Bitmap decode(File f, int max) {
        try { BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = sample(o.outWidth, o.outHeight, max); o.inJustDecodeBounds = false; o.inPreferredConfig = Bitmap.Config.ARGB_8888; return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable e) { return null; }
    }
    private static Bitmap decodeBytes(byte[] raw, int max) {
        try { BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            o.inSampleSize = sample(o.outWidth, o.outHeight, max); o.inJustDecodeBounds = false; return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        } catch (Throwable e) { return null; }
    }
    private static int sample(int w, int h, int max) { int s = 1; while (w / (s * 2) >= max || h / (s * 2) >= max) s *= 2; return s; }
    private void saveBrand(final Bitmap b) {
        img.execute(new Runnable() { @Override public void run() { try { FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "brand-logo")); b.compress(Bitmap.CompressFormat.PNG, 100, o); o.close(); } catch (Throwable ignored) { } } });
    }
    private static String sha1(String s) {
        try { MessageDigest d = MessageDigest.getInstance("SHA-1"); byte[] h = d.digest(s.getBytes("UTF-8")); StringBuilder b = new StringBuilder(); for (byte x : h) b.append(String.format("%02x", x)); return b.toString(); }
        catch (Throwable e) { return String.valueOf(s.hashCode()); }
    }

    /* ── the developer's TV list (Studio → LED screen → TV devices) ── */
    private final Runnable report = new Runnable() {
        @Override public void run() {
            net.execute(new Runnable() { @Override public void run() {
                HttpURLConnection c = null;
                try {
                    String id = prefs.getString("tvId", "");
                    if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16); prefs.edit().putString("tvId", id).apply(); }
                    android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); if (am != null) am.getMemoryInfo(mi);
                    String name = Settings.Global.getString(getContentResolver(), "device_name"); if (name == null || name.isEmpty()) name = Build.MODEL;
                    File dir = new File(getFilesDir(), "led"); long saved = 0; JSONArray files = new JSONArray(); File[] all = dir.listFiles();
                    if (all != null) for (File f : all) { saved += f.length(); JSONObject fo = new JSONObject(); fo.put("n", f.getName().replaceAll("\\..*$", "")); fo.put("u", ""); fo.put("t", f.getName().endsWith(".mp4") ? "video/mp4" : "image"); fo.put("s", f.length()); fo.put("l", f.lastModified()); files.put(fo); }
                    JSONObject info = new JSONObject();
                    info.put("name", name); info.put("model", Build.MANUFACTURER + " " + Build.MODEL); info.put("android", Build.VERSION.RELEASE); info.put("sdk", Build.VERSION.SDK_INT);
                    info.put("app", Updater.currentName(LedActivity.this) + " (native)"); info.put("screen", getResources().getDisplayMetrics().widthPixels + "x" + getResources().getDisplayMetrics().heightPixels);
                    info.put("ram", mi.totalMem); info.put("ramFree", mi.availMem); info.put("disk", getFilesDir().getTotalSpace()); info.put("diskFree", getFilesDir().getUsableSpace()); info.put("saved", saved);
                    JSONObject body = new JSONObject(); body.put("op", "report"); body.put("id", id); body.put("info", info); body.put("files", files); body.put("done", new JSONArray(prefs.getString("done", "[]")));
                    c = (HttpURLConnection) new URL(Site.get(LedActivity.this) + "api/tvdev.php").openConnection();
                    c.setConnectTimeout(12_000); c.setReadTimeout(15_000); c.setDoOutput(true); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json");
                    OutputStream o = c.getOutputStream(); o.write(body.toString().getBytes("UTF-8")); o.close();
                    if (c.getResponseCode() == 200) {
                        JSONObject r = new JSONObject(new String(Store.readAll(c.getInputStream()), "UTF-8")); JSONArray cmds = r.optJSONArray("cmds"); JSONArray done = new JSONArray();
                        if (cmds != null) for (int i = 0; i < cmds.length(); i++) { JSONObject cm = cmds.optJSONObject(i); if (cm == null) continue;
                            if ("clear".equals(cm.optString("act"))) { if (all != null) for (File f : all) f.delete(); }
                            else if ("update".equals(cm.optString("act"))) { final int v = Updater.check(LedActivity.this);
                                ui.post(new Runnable() { @Override public void run() { if (Updater.ready > Updater.current(LedActivity.this)) { if (!Updater.install(LedActivity.this)) Toast.makeText(LedActivity.this, "Allow installs for Sweet 97.7 LED, then press Update again in Studio.", Toast.LENGTH_LONG).show(); } } }); }
                            else if ("del".equals(cm.optString("act"))) { if (all != null) for (File f : all) if (f.getName().startsWith(cm.optString("f") + ".") || f.getName().equals(cm.optString("f"))) f.delete(); }
                            done.put(cm.optString("id")); }
                        prefs.edit().putString("done", done.toString()).apply();
                    }
                } catch (Throwable ignored) { } finally { if (c != null) c.disconnect(); }
                try { if (System.currentTimeMillis() - prefs.getLong("updChk", 0) > 24L * 3600_000) { prefs.edit().putLong("updChk", System.currentTimeMillis()).apply(); Updater.check(LedActivity.this); } } catch (Throwable ignored) { } // a new app version downloads quietly; install it from the menu
            } });
            ui.postDelayed(this, REPORT_MS);
        }
    };

    /* ── the website's address (site.txt on GitHub) ── */
    private void checkSite() {
        new Thread(new Runnable() { @Override public void run() {
            final String moved = Site.checkRemote(LedActivity.this);
            if (moved != null) ui.post(new Runnable() { @Override public void run() { Site.set(LedActivity.this, moved); feed = null; feedH = ""; started = false; setCover("Updating", "Connecting to " + Site.host(LedActivity.this) + "…", 3); } });
        } }).start();
    }

    /* ── the remote: OK opens the menu, Back twice closes ── */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = SystemClock.elapsedRealtime();
            if (now - backAt < 2500) finish(); else { backAt = now; Toast.makeText(this, "Press Back again to close the LED screen", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_INFO) { menu(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void menu() {
        final boolean auto = prefs.getBoolean(AUTOSTART, true);
        final int ready = Updater.ready;
        final String[] opts = { "Reload the LED screen", "Open when the TV turns on: " + (auto ? "ON" : "OFF"),
            ready > Updater.current(this) ? "Install the new app version (1.0." + ready + ")" : "Check for an app update",
            "Clear saved pictures and video", "Website address: " + Site.host(this), "About", "Close the LED screen" };
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setItems(opts, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                switch (which) {
                    case 0: feed = null; feedH = ""; started = false; setCover("Updating", "Reloading…", 3); break;
                    case 1: prefs.edit().putBoolean(AUTOSTART, !auto).apply(); Toast.makeText(LedActivity.this, auto ? "It will not open by itself." : "It will open when the TV turns on.", Toast.LENGTH_LONG).show(); if (!auto) askAutostart(); break;
                    case 2: update(ready); break;
                    case 3: { File[] all = new File(getFilesDir(), "led").listFiles(); if (all != null) for (File f : all) f.delete(); pics.clear(); shownLogo = ""; shownVideo = ""; Toast.makeText(LedActivity.this, "Cleared.", Toast.LENGTH_LONG).show(); break; }
                    case 4: askSite(); break;
                    case 5: about(); break;
                    default: finish();
                }
            }
        }).setNegativeButton("Back", null).show();
    }

    private void askSite() {
        final EditText in = new EditText(this); in.setSingleLine(true); in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); in.setText(Site.get(this));
        new AlertDialog.Builder(this).setTitle("Website address").setMessage("Normally this changes by itself when the website moves. Type it here only if it didn't.").setView(in)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                String site = Site.clean(in.getText().toString());
                if (site == null) { Toast.makeText(LedActivity.this, "That isn't a website address. Example: sweet977.ph", Toast.LENGTH_LONG).show(); return; }
                Site.set(LedActivity.this, site); feed = null; feedH = ""; started = false; setCover("Updating", "Connecting to " + Site.host(LedActivity.this) + "…", 3);
            } }).setNegativeButton("Cancel", null).show();
    }

    private void update(int ready) {
        if (ready > Updater.current(this)) { if (!Updater.install(this)) Toast.makeText(this, "Allow installs for Sweet 97.7 LED on the screen that opened, then choose Install again.", Toast.LENGTH_LONG).show(); return; }
        Toast.makeText(this, "Checking for a new version…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            final int v = Updater.check(LedActivity.this);
            ui.post(new Runnable() { @Override public void run() {
                if (Updater.ready > Updater.current(LedActivity.this)) Updater.install(LedActivity.this);
                else Toast.makeText(LedActivity.this, v > 0 ? "You have the newest version." : "Couldn't check right now. Try again later.", Toast.LENGTH_LONG).show();
            } });
        } }).start();
    }

    private void about() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo(); if (am != null) am.getMemoryInfo(mi);
        String msg = "Version " + Updater.currentName(this) + " (drawn by the app)\nWebsite: " + Site.get(this)
            + "\nInternet: " + (lastFeedOk > 0 && SystemClock.elapsedRealtime() - lastFeedOk < 60_000 ? "connected" : "not reachable")
            + "\nMemory: " + (mi.totalMem >> 20) + " MB (" + (mi.availMem >> 20) + " MB free)\nStorage free: " + (getFilesDir().getUsableSpace() >> 20) + " MB"
            + "\nAndroid " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL;
        new AlertDialog.Builder(this).setTitle("About").setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void askAutostartOnce() { if (prefs.getBoolean("asked", false)) return; prefs.edit().putBoolean("asked", true).apply(); askAutostart(); }
    private void askAutostart() {
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) return;
        new AlertDialog.Builder(this).setTitle("Open when the TV turns on")
            .setMessage("So the LED screen opens by itself whenever the TV is switched on, allow \"Display over other apps\" for " + getString(R.string.app_name) + " on the next screen, then press Back.")
            .setPositiveButton("Open the setting", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Throwable e) { new AlertDialog.Builder(LedActivity.this).setTitle("This TV hides that setting").setMessage("You can still open the LED screen from the TV's home screen.").setPositiveButton("OK", null).show(); }
            } }).setNegativeButton("Not now", null).show();
    }

    private void hideBars() { root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @Override protected void onResume() { super.onResume(); if (video.getVisibility() == View.VISIBLE) video.start(); }
    @Override protected void onPause() { if (video.getVisibility() == View.VISIBLE) video.pause(); super.onPause(); }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); net.shutdownNow(); img.shutdownNow(); super.onDestroy(); }
}
