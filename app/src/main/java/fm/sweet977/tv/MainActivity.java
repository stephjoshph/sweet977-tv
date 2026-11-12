package fm.sweet977.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

/**
 * The LED screen: the website's LED page (yoursite/#led) full screen, kept running day and night.
 * OK on the remote opens a small menu; Back twice leaves.
 */
public class MainActivity extends Activity {
    static final String PREFS = "tv";
    static final String AUTOSTART = "autostart";
    private static final long WATCH_MS = 30_000;
    private static final long FROZEN_MS = 120_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web;
    private TextView cover;
    private Store store;
    private SharedPreferences prefs;
    private volatile long lastBeat = 0;
    private boolean loaded = false;
    private boolean failed = false;
    private long backAt = 0;
    private long lastDaily = 0;
    private long lastUpdateCheck = 0;
    private final long startedAt = System.currentTimeMillis();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cover = new TextView(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setTextColor(Color.WHITE);
        cover.setTextSize(28);
        cover.setTypeface(Typeface.DEFAULT_BOLD);
        cover.setGravity(Gravity.CENTER);
        cover.setLineSpacing(0, 1.3f);
        cover.setText(getString(R.string.app_name) + "\n\nStarting…");
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideBars();
        store = new Store(this);
        makeWeb();
        web.loadUrl(Site.start(MainActivity.this));
        ui.postDelayed(watch, WATCH_MS);
        checkSite();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                askAutostartOnce();
            }
        }, 6000);
    }

    private void makeWeb() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " SweetTV/" + Updater.currentName(this));
        store.userAgent = s.getUserAgentString();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "SweetTVApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());
        root.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lastBeat = SystemClock.elapsedRealtime();
    }

    /** The page says it is alive every 10 s; a page that stops saying so is reloaded. */
    final class Bridge {
        @JavascriptInterface
        public void beat() {
            lastBeat = SystemClock.elapsedRealtime();
        }
    }

    private static final String BEAT_JS = "(function(){if(window.__sweetTV)return;window.__sweetTV=1;"
            + "var b=function(){try{SweetTVApp.beat()}catch(e){}};b();setInterval(b,10000);"
            + "document.documentElement.style.cursor='none';})();";

    final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return store.intercept(request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String h = request.getUrl().getHost();
            return h == null || !h.equalsIgnoreCase(Site.host(MainActivity.this)); // stay on the website
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            failed = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!failed && strayed(url)) return;
            view.evaluateJavascript(BEAT_JS, null);
            if (!failed) {
                loaded = true;
                lastBeat = SystemClock.elapsedRealtime();
                cover.setVisibility(View.GONE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) offline();
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (Build.VERSION.SDK_INT < 23) offline();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // the page's engine crashed (usually out of memory): start a fresh one
            root.removeView(web);
            web.destroy();
            makeWeb();
            web.loadUrl(Site.start(MainActivity.this));
            return true;
        }
    }

    private long strayAt = 0;
    private int strays = 0;

    /** The host's security check (or a link) left the LED page: go back to it, a few times at most. */
    private boolean strayed(String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost(), path = u.getPath() == null ? "/" : u.getPath();
        if (host == null || !host.equalsIgnoreCase(Site.host(this)) || path.matches(".*/led/?$")) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - strayAt > 120_000) strays = 0;
        strayAt = now;
        if (++strays > 3) return false;
        web.loadUrl(Site.start(MainActivity.this));
        return true;
    }

    private long siteCheckedAt = 0;

    /** Looks at site.txt on GitHub; if the website moved, goes there. */
    private void checkSite() {
        siteCheckedAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String moved = Site.checkRemote(MainActivity.this);
                if (moved != null) ui.post(new Runnable() {
                    @Override
                    public void run() {
                        moveTo(moved);
                    }
                });
            }
        }).start();
    }

    private void moveTo(String site) {
        Site.set(this, site);
        store.setHost(Uri.parse(site).getHost());
        loaded = false;
        cover.setText(getString(R.string.app_name) + "\n\nConnecting to " + Uri.parse(site).getHost() + "…");
        cover.setVisibility(View.VISIBLE);
        web.loadUrl(Site.start(this));
    }

    /** No internet and no saved copy yet: say so, and try again shortly. */
    private void offline() {
        failed = true;
        if (System.currentTimeMillis() - siteCheckedAt > 10 * 60_000) checkSite(); // maybe the website moved
        cover.setText(getString(R.string.app_name) + "\n\nWaiting for the internet…\nThe LED screen starts as soon as the website can be reached.");
        cover.setVisibility(View.VISIBLE);
        ui.removeCallbacks(retry);
        ui.postDelayed(retry, 20_000);
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            web.loadUrl(Site.start(MainActivity.this));
        }
    };

    /** Every 30 s: reload a frozen page, refresh once a night at 4 AM, and look for app updates once a day. */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (loaded && !failed && now - lastBeat > FROZEN_MS) {
                lastBeat = now;
                web.reload();
            }
            Calendar cal = Calendar.getInstance();
            if (cal.get(Calendar.HOUR_OF_DAY) == 4 && System.currentTimeMillis() - lastDaily > 20L * 3600 * 1000) {
                lastDaily = System.currentTimeMillis();
                if (lastDaily - startedAt > 3600_000) web.loadUrl(Site.start(MainActivity.this)); // not right after starting
            }
            if (System.currentTimeMillis() - lastUpdateCheck > 24L * 3600 * 1000) {
                lastUpdateCheck = System.currentTimeMillis();
                checkSite();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Updater.check(MainActivity.this);
                    }
                }).start();
            }
            ui.postDelayed(this, WATCH_MS);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = SystemClock.elapsedRealtime();
            if (now - backAt < 2500) {
                finish();
            } else {
                backAt = now;
                Toast.makeText(this, "Press Back again to close the LED screen", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_INFO) {
            menu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void menu() {
        final boolean auto = prefs.getBoolean(AUTOSTART, true);
        final int ready = Updater.ready;
        final String[] items = {
                "Reload the LED screen",
                "Open when the TV turns on: " + (auto ? "ON" : "OFF"),
                ready > Updater.current(this) ? "Install the new app version (1.0." + ready + ")" : "Check for an app update",
                "Clear saved files (" + (store.size() / (1024 * 1024)) + " MB)",
                "Website address: " + Site.host(this),
                "About",
                "Close the LED screen"};
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                web.loadUrl(Site.start(MainActivity.this));
                                break;
                            case 1:
                                prefs.edit().putBoolean(AUTOSTART, !auto).apply();
                                Toast.makeText(MainActivity.this, auto ? "It will not open by itself." : "It will open when the TV turns on.", Toast.LENGTH_LONG).show();
                                if (!auto) askAutostart();
                                break;
                            case 2:
                                update(ready);
                                break;
                            case 3:
                                store.clear();
                                web.clearCache(true);
                                web.loadUrl(Site.start(MainActivity.this));
                                Toast.makeText(MainActivity.this, "Cleared. The LED screen downloads its files again.", Toast.LENGTH_LONG).show();
                                break;
                            case 4:
                                askSite();
                                break;
                            case 5:
                                about();
                                break;
                            default:
                                finish();
                        }
                    }
                })
                .setNegativeButton("Back", null)
                .show();
    }

    /** Type the website address with the remote (only needed if site.txt can't be reached). */
    private void askSite() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setText(Site.get(this));
        new AlertDialog.Builder(this)
                .setTitle("Website address")
                .setMessage("Normally this changes by itself when the website moves. Type it here only if it didn't.")
                .setView(in)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String site = Site.clean(in.getText().toString());
                        if (site == null) {
                            Toast.makeText(MainActivity.this, "That isn't a website address. Example: sweet977.com", Toast.LENGTH_LONG).show();
                            return;
                        }
                        moveTo(site);
                    }
                })
                .setNeutralButton("Use the original", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        moveTo(Config.SITE);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void update(int ready) {
        if (ready > Updater.current(this)) {
            if (!Updater.install(this)) {
                Toast.makeText(this, "Allow installs for Sweet 97.7 LED on the screen that opened, then choose Install again.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Toast.makeText(this, "Checking for a new version…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int v = Updater.check(MainActivity.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (Updater.ready > Updater.current(MainActivity.this)) {
                            Updater.install(MainActivity.this);
                        } else {
                            Toast.makeText(MainActivity.this, v > 0 ? "You have the newest version." : "Couldn't check right now. Try again later.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void about() {
        String msg = "Version " + Updater.currentName(this)
                + "\nWebsite: " + Site.get(this)
                + "\nInternet: " + (store.online ? "connected" : "not reachable (showing the saved screen)")
                + "\nSaved on this TV: " + (store.size() / (1024 * 1024)) + " MB"
                + "\nOpens when the TV turns on: " + (prefs.getBoolean(AUTOSTART, true) ? "yes" : "no")
                + (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this) ? "\n\nTo open by itself at power-on, allow \"Display over other apps\" for this app." : "");
        new AlertDialog.Builder(this).setTitle("About").setMessage(msg).setPositiveButton("OK", null).show();
    }

    /** Android 10 and later only let an app open itself at power-on when "Display over other apps" is allowed. */
    private void askAutostartOnce() {
        if (prefs.getBoolean("asked", false)) return;
        prefs.edit().putBoolean("asked", true).apply();
        askAutostart();
    }

    private void askAutostart() {
        if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Open when the TV turns on")
                .setMessage("So the LED screen opens by itself whenever the TV is switched on, allow \"Display over other apps\" for "
                        + getString(R.string.app_name) + " on the next screen, then press Back.")
                .setPositiveButton("Open the setting", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            new AlertDialog.Builder(MainActivity.this).setTitle("This TV hides that setting")
                                    .setMessage("You can still open the LED screen from the TV's home screen.\n\nA technician can allow it with:\nadb shell appops set "
                                            + getPackageName() + " SYSTEM_ALERT_WINDOW allow")
                                    .setPositiveButton("OK", null).show();
                        }
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void hideBars() {
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause(); // another app is on screen: rest
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
