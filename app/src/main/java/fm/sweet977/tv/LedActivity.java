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
    private static final long FEED_MS = 15_000, REPORT_MS = 5 * 60_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor(), img = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private FrameLayout root, stage;
    private LinearLayout cover, card, rows;
    private TextView coverTitle, coverSub, big1, big2, clock, eye, title, sub;
    private ImageView coverLogo, logo, pic, lockLogo;
    private ProgressBar coverBar;
    private View[] glows = new View[3];
    private View lockView, blackView;
    private VideoView video;
    private Typeface anton, bold, semi;
    private float k = 1; // the screen's size against a 1920-wide design

    private JSONObject feed;            // the newest content from the website
    private List<JSONObject> items = new ArrayList<>();
    private int idx = -1;
    private long slideEnds = 0;
    private String shownLogo = "", shownVideo = "";
    private long backAt = 0, lastFeedOk = 0;
    private final Map<String, Bitmap> pics = new HashMap<>();
    private final java.util.Set<String> loading = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try { anton = Typeface.createFromAsset(getAssets(), "fonts/Anton.ttf"); } catch (Throwable e) { anton = Typeface.DEFAULT_BOLD; }
        try { bold = Typeface.createFromAsset(getAssets(), "fonts/Inter-Bold.ttf"); } catch (Throwable e) { bold = Typeface.DEFAULT_BOLD; }
        try { semi = Typeface.createFromAsset(getAssets(), "fonts/Inter-SemiBold.ttf"); } catch (Throwable e) { semi = Typeface.DEFAULT; }
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        k = w / 1920f;
        build();
        setContentView(root);
        hideBars();
        setCover("Updating", "Connecting to " + Site.host(this) + "…", 3);
        ui.post(tick);
        ui.post(poll);
        ui.postDelayed(report, 20_000);
        checkSite();
        ui.postDelayed(new Runnable() { @Override public void run() { askAutostartOnce(); } }, 8000);
    }

    private float px(float v) { return v * k; }
    private void size(TextView t, float v) { t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(v)); }
    private static int alpha(int c, int a) { return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)); }
    private static int col(JSONObject o, String key, int def) { try { return Color.parseColor(o.optString(key)); } catch (Throwable e) { return def; } }

    /* ── the screen's parts ── */
    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        for (int i = 0; i < 3; i++) { // soft colour glows, drifting slowly (a gradient: cheap for small TVs)
            View g = new View(this);
            int s = (int) px(i == 1 ? 1100 : 1300);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
            lp.leftMargin = (int) px(new int[]{-380, 1100, 300}[i]); lp.topMargin = (int) px(new int[]{-420, -300, 420}[i]);
            stage.addView(g, lp);
            glows[i] = g;
            g.animate().translationX(px(i == 1 ? -160 : 160)).translationY(px(i == 2 ? -120 : 120)).setDuration(18_000 + i * 6000).withEndAction(drift(g, i)).start();
        }

        big1 = text(anton, 760, Color.WHITE); // the giant "97.7" behind everything
        big1.setIncludeFontPadding(false); big1.setSingleLine(true);
        FrameLayout.LayoutParams b1 = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START); b1.leftMargin = (int) px(60);
        stage.addView(big1, b1);
        big2 = text(anton, 300, Color.WHITE);
        FrameLayout.LayoutParams b2 = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); b2.bottomMargin = (int) px(-60);
        stage.addView(big2, b2);

        logo = new ImageView(this); logo.setScaleType(ImageView.ScaleType.FIT_START); logo.setAdjustViewBounds(true);
        FrameLayout.LayoutParams lg = new FrameLayout.LayoutParams((int) px(420), (int) px(130), Gravity.TOP | Gravity.START); lg.leftMargin = (int) px(64); lg.topMargin = (int) px(52);
        stage.addView(logo, lg);
        clock = text(bold, 54, Color.WHITE);
        FrameLayout.LayoutParams ck = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); ck.rightMargin = (int) px(70); ck.topMargin = (int) px(62);
        stage.addView(clock, ck);

        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding((int) px(80), (int) px(56), (int) px(80), (int) px(64));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams((int) px(1340), -2, Gravity.CENTER); cl.topMargin = (int) px(40);
        stage.addView(card, cl);
        eye = text(bold, 34, Color.WHITE); eye.setAllCaps(true); eye.setLetterSpacing(0.14f); eye.setPadding((int) px(34), (int) px(12), (int) px(34), (int) px(12));
        card.addView(eye, wrap(0));
        title = text(bold, 150, Color.WHITE); title.setGravity(Gravity.CENTER); title.setMaxLines(3); title.setEllipsize(TextUtils.TruncateAt.END); title.setLineSpacing(0, 0.92f);
        card.addView(title, wrap(28));
        pic = new ImageView(this); pic.setScaleType(ImageView.ScaleType.FIT_CENTER); pic.setAdjustViewBounds(true);
        card.addView(pic, new LinearLayout.LayoutParams((int) px(1100), (int) px(560)));
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        sub = text(semi, 44, Color.argb(220, 255, 255, 255)); sub.setGravity(Gravity.CENTER); sub.setMaxLines(2);
        card.addView(sub, wrap(26));

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setVisibility(View.GONE);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { @Override public void onPrepared(MediaPlayer mp) { mp.setLooping(true); mp.setVolume(0, 0); } });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() { @Override public boolean onError(MediaPlayer mp, int a, int b) { shownVideo = ""; video.setVisibility(View.GONE); return true; } });

        LinearLayout lock = new LinearLayout(this); lock.setGravity(Gravity.CENTER); lock.setBackgroundColor(Color.rgb(5, 3, 4));
        lockLogo = new ImageView(this); lockLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lock.addView(lockLogo, new LinearLayout.LayoutParams((int) px(900), (int) px(500)));
        lockView = lock; lockView.setVisibility(View.GONE);
        root.addView(lockView, new FrameLayout.LayoutParams(-1, -1));
        blackView = new View(this); blackView.setBackgroundColor(Color.BLACK); blackView.setVisibility(View.GONE);
        root.addView(blackView, new FrameLayout.LayoutParams(-1, -1));

        cover = new LinearLayout(this); cover.setOrientation(LinearLayout.VERTICAL); cover.setGravity(Gravity.CENTER); cover.setBackgroundColor(Color.rgb(7, 4, 5));
        coverLogo = new ImageView(this); coverLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverLogo.setImageResource(R.drawable.logo);
        File bf = new File(getFilesDir(), "brand-logo");
        if (bf.exists()) { Bitmap b = decode(bf, 600); if (b != null) coverLogo.setImageBitmap(b); }
        cover.addView(coverLogo, new LinearLayout.LayoutParams((int) px(420), (int) px(300)));
        coverTitle = text(bold, 56, Color.WHITE); cover.addView(coverTitle, wrap(30));
        coverBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); coverBar.setMax(100);
        if (Build.VERSION.SDK_INT >= 21) coverBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 22, 34)));
        cover.addView(coverBar, new LinearLayout.LayoutParams((int) px(640), (int) px(14)));
        coverSub = text(semi, 30, Color.argb(190, 255, 255, 255)); coverSub.setGravity(Gravity.CENTER);
        cover.addView(coverSub, wrap(24));
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        applyColors(Color.rgb(5, 3, 4), Color.rgb(232, 16, 28), Color.rgb(26, 18, 20));
    }

    private Runnable drift(final View g, final int i) {
        return new Runnable() { @Override public void run() {
            float x = g.getTranslationX() == 0 ? px(i == 1 ? -160 : 160) : 0, y = g.getTranslationY() == 0 ? px(i == 2 ? -120 : 120) : 0;
            g.animate().translationX(x).translationY(y).setDuration(18_000 + i * 6000).withEndAction(this).start();
        } };
    }

    private TextView text(Typeface tf, float sizePx, int color) {
        TextView t = new TextView(this); t.setTypeface(tf); size(t, sizePx); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }
    private LinearLayout.LayoutParams wrap(float top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.gravity = Gravity.CENTER_HORIZONTAL; p.topMargin = (int) px(top); return p; }

    private int acc = Color.RED;
    private void applyColors(int bg, int accent, int cardC) {
        acc = accent;
        stage.setBackgroundColor(bg);
        int[] cs = {accent, alpha(darker(accent), 255), accent};
        for (int i = 0; i < 3; i++) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{alpha(cs[i], i == 1 ? 120 : 150), alpha(cs[i], 0)});
            d.setGradientType(GradientDrawable.RADIAL_GRADIENT); d.setGradientRadius(px(i == 1 ? 550 : 650)); d.setShape(GradientDrawable.OVAL);
            glows[i].setBackground(d);
        }
        big1.setTextColor(alpha(accent, 150)); big2.setTextColor(Color.argb(28, 255, 255, 255));
        GradientDrawable cd = new GradientDrawable(); cd.setColor(alpha(cardC, 205)); cd.setCornerRadius(px(46)); cd.setStroke((int) Math.max(1, px(2)), Color.argb(40, 255, 255, 255));
        card.setBackground(cd);
        GradientDrawable pill = new GradientDrawable(); pill.setColor(accent); pill.setCornerRadius(px(60));
        eye.setBackground(pill);
    }
    private static int darker(int c) { return Color.rgb(Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3); }

    /* ── the Updating screen ── */
    private void setCover(String t, String s, int pct) {
        coverTitle.setText(t); coverSub.setText(s == null ? "" : s);
        coverBar.setVisibility(pct < 0 ? View.GONE : View.VISIBLE); if (pct >= 0) coverBar.setProgress(pct);
        cover.setVisibility(View.VISIBLE);
    }
    private void hideCover() { if (cover.getVisibility() == View.VISIBLE) cover.animate().alpha(0).setDuration(600).withEndAction(new Runnable() { @Override public void run() { cover.setVisibility(View.GONE); cover.setAlpha(1); } }).start(); }

    /* ── reading the website every 15 s ── */
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            net.execute(new Runnable() { @Override public void run() {
                final JSONObject j = get(Site.get(LedActivity.this) + "api/led.php");
                ui.post(new Runnable() { @Override public void run() { onFeed(j); } });
            } });
            ui.postDelayed(this, FEED_MS);
        }
    };

    private JSONObject get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12_000); c.setReadTimeout(15_000); c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "SweetTV/" + Updater.currentName(this) + " (Android " + Build.VERSION.RELEASE + ")");
            if (c.getResponseCode() != 200) return null;
            String s = new String(Store.readAll(c.getInputStream()), "UTF-8");
            JSONObject j = new JSONObject(s);
            return j.optBoolean("ok") ? j : null;
        } catch (Throwable e) { return null; } finally { if (c != null) c.disconnect(); }
    }

    private void onFeed(JSONObject j) {
        if (j == null) {
            if (feed == null) setCover("Waiting for the internet", "The LED screen starts as soon as " + Site.host(this) + " can be reached.", -1);
            return;
        }
        lastFeedOk = SystemClock.elapsedRealtime();
        boolean first = feed == null; feed = j;
        JSONObject cs = j.optJSONObject("colors"); if (cs == null) cs = new JSONObject();
        applyColors(col(cs, "bg", Color.rgb(5, 3, 4)), col(cs, "acc", Color.rgb(232, 16, 28)), col(cs, "card", Color.rgb(26, 18, 20)));
        big1.setText(j.optString("big", "97.7")); big2.setText(j.optString("big2", "FM"));
        String lg = j.optString("logo", "");
        if (!lg.equals(shownLogo)) { shownLogo = lg; picture(lg, 900, new Got() { @Override public void got(Bitmap b) { logo.setImageBitmap(b); lockLogo.setImageBitmap(b); coverLogo.setImageBitmap(b); saveBrand(b); } }); }
        List<JSONObject> list = new ArrayList<>(); JSONArray a = j.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) list.add(o); }
        // keep the item on screen if it's still there; its new content shows on the next turn
        String curId = idx >= 0 && idx < items.size() ? items.get(idx).optString("id") : "";
        items = list; idx = -1; for (int i = 0; i < items.size(); i++) if (items.get(i).optString("id").equals(curId)) idx = i;
        if (idx >= 0) refresh(items.get(idx));
        card.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        for (JSONObject o : items) { String u = o.optString("img"); if (!u.isEmpty()) picture(u, 1100, null); }
        lockView.setVisibility(j.optBoolean("lock") ? View.VISIBLE : View.GONE);
        blackView.setVisibility(j.optBoolean("black") ? View.VISIBLE : View.GONE);
        playVideo(j.optString("video", ""));
        if (first) { hideCover(); slideEnds = 0; }
    }

    /* ── one item after another ── */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                TimeZone tz = TimeZone.getTimeZone(feed != null ? feed.optString("tz", "Asia/Manila") : "Asia/Manila");
                SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US); f.setTimeZone(tz);
                clock.setText(f.format(new Date()));
                long now = SystemClock.elapsedRealtime();
                if (feed != null && !items.isEmpty() && now >= slideEnds) next();
                else if (idx >= 0 && idx < items.size()) live(items.get(idx)); // clock and countdown items count every second
            } catch (Throwable ignored) { }
            ui.postDelayed(this, 1000);
        }
    };

    private void next() {
        idx = (idx + 1) % items.size();
        final JSONObject it = items.get(idx);
        slideEnds = SystemClock.elapsedRealtime() + it.optInt("dur", 10) * 1000L;
        card.animate().alpha(0).translationY(px(-30)).setDuration(260).withEndAction(new Runnable() { @Override public void run() {
            show(it);
            card.setTranslationY(px(40));
            card.animate().alpha(1).translationY(0).setDuration(520).start();
        } }).start();
    }

    private void refresh(JSONObject it) { if (card.getAlpha() > 0.9f) show(it); }

    private void show(JSONObject it) {
        String t = it.optString("type");
        String e = it.optString("eye"), b = it.optString("big"), s = it.optString("sub"), u = it.optString("img");
        rows.removeAllViews(); pic.setVisibility(View.GONE); pic.setImageDrawable(null); title.setTypeface(bold);
        if (t.equals("clock") || t.equals("countdown")) { live(it); }
        else { setText(eye, e); setBig(b); setText(sub, s); }
        if (t.equals("image") || t.equals("logo")) { eye.setVisibility(View.GONE); title.setVisibility(View.GONE); sub.setVisibility(View.GONE); }
        if (t.equals("sponsor")) { JSONArray im = it.optJSONArray("imgs"); if (im != null && im.length() > 0) u = im.optString((int) ((System.currentTimeMillis() / 4000) % im.length())); }
        if (!u.isEmpty()) {
            Bitmap bm = pics.get(u);
            if (bm != null) { pic.setImageBitmap(bm); pic.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pic.getLayoutParams(); boolean only = t.equals("image") || t.equals("logo") || t.equals("sponsor");
                lp.width = (int) px(only ? 1180 : 360); lp.height = (int) px(only ? 620 : 360); lp.topMargin = (int) px(only ? 0 : 24); pic.setLayoutParams(lp); }
            else picture(u, 1100, null);
        }
        JSONArray rs = it.optJSONArray("rows");
        if (rs != null) {
            if (t.equals("top3") || t.equals("social")) title.setVisibility(View.GONE);
            if (t.equals("poll")) size(title, 74);
            for (int i = 0; i < rs.length() && i < 5; i++) rows.addView(row(rs.optJSONObject(i), t, i));
        }
    }

    private void live(JSONObject it) {
        String t = it.optString("type");
        TimeZone tz = TimeZone.getTimeZone(feed != null ? feed.optString("tz", "Asia/Manila") : "Asia/Manila");
        if (t.equals("clock")) {
            SimpleDateFormat d = new SimpleDateFormat("EEEE", Locale.US), h = new SimpleDateFormat("h:mm", Locale.US), a = new SimpleDateFormat("a · MMMM d", Locale.US);
            d.setTimeZone(tz); h.setTimeZone(tz); a.setTimeZone(tz); Date n = new Date();
            setText(eye, d.format(n)); title.setTypeface(anton); setBig(h.format(n)); setText(sub, a.format(n));
        } else if (t.equals("countdown")) {
            long ms = Math.max(0, it.optLong("at") - (System.currentTimeMillis() + skew()));
            long hh = ms / 3_600_000, mm = ms % 3_600_000 / 60_000, ss = ms % 60_000 / 1000;
            setText(eye, it.optString("eye", "Countdown")); title.setTypeface(anton);
            setBig((hh > 0 ? hh + ":" : "") + String.format(Locale.US, "%02d:%02d", mm, ss)); setText(sub, "");
        }
    }
    private long skew() { return 0; }

    private void setText(TextView v, String s) { v.setText(s); v.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE); }
    private void setBig(String s) {
        int n = s == null ? 0 : s.length();
        size(title, n <= 8 ? 190 : n <= 14 ? 150 : n <= 24 ? 118 : n <= 40 ? 92 : 72);
        setText(title, s);
    }

    private View row(JSONObject r, String type, int i) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding((int) px(26), (int) px(14), (int) px(26), (int) px(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.argb(22, 255, 255, 255)); bg.setCornerRadius(px(26)); l.setBackground(bg);
        String n = r == null ? "" : r.optString("n");
        if (!n.isEmpty()) {
            TextView num = text(anton, 70, Color.WHITE); num.setGravity(Gravity.CENTER); num.setText(n);
            GradientDrawable nb = new GradientDrawable(); nb.setColor(acc); nb.setCornerRadius(px(22)); num.setBackground(nb);
            l.addView(num, new LinearLayout.LayoutParams((int) px(104), (int) px(104)));
        }
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding((int) px(28), 0, 0, 0);
        TextView a = text(bold, type.equals("poll") ? 50 : 56, Color.WHITE); a.setSingleLine(true); a.setEllipsize(TextUtils.TruncateAt.END); a.setText(r == null ? "" : r.optString("title"));
        tx.addView(a);
        String sb = r == null ? "" : r.optString("sub");
        if (!sb.isEmpty()) { TextView b = text(semi, 36, Color.argb(200, 255, 255, 255)); b.setSingleLine(true); b.setText(sb); tx.addView(b); }
        l.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = (int) px(i == 0 ? 30 : 16);
        l.setLayoutParams(p);
        l.setAlpha(0); l.setTranslationX(px(-40)); l.animate().alpha(1).translationX(0).setStartDelay(180 + i * 120L).setDuration(420).start();
        return l;
    }

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
            if (moved != null) ui.post(new Runnable() { @Override public void run() { Site.set(LedActivity.this, moved); feed = null; setCover("Updating", "Connecting to " + Site.host(LedActivity.this) + "…", 3); } });
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
                    case 0: feed = null; setCover("Updating", "Reloading…", 3); ui.removeCallbacks(poll); ui.post(poll); break;
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
                Site.set(LedActivity.this, site); feed = null; setCover("Updating", "Connecting to " + Site.host(LedActivity.this) + "…", 3); ui.removeCallbacks(poll); ui.post(poll);
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
