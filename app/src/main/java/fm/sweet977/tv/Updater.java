package fm.sweet977.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * New versions of this app are published on GitHub. The app checks once a day and downloads a newer one quietly;
 * nothing ever pops up on the LED wall. Installing waits until someone opens the menu (OK button) and chooses it.
 */
final class Updater {
    /** The version that is downloaded and ready to install, or 0. */
    static volatile int ready = 0;

    private Updater() {}

    static int current(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String currentName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Looks for a newer version and downloads it. Runs off the main thread. Returns the newest version found. */
    static int check(Context c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("https://api.github.com/repos/" + Config.REPO + "/releases/latest").openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "SweetTV");
            if (h.getResponseCode() != 200) return 0;
            JSONObject rel = new JSONObject(new String(Store.readAll(h.getInputStream()), "UTF-8"));
            String digits = rel.optString("tag_name", "").replaceAll("[^0-9]", "");
            if (digits.length() == 0 || digits.length() > 9) return 0;
            int latest = Integer.parseInt(digits);
            if (latest <= current(c)) return latest;
            JSONArray assets = rel.optJSONArray("assets");
            String apk = null;
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url");
                }
            }
            if (apk == null) return 0;
            File out = new File(c.getCacheDir(), ApkProvider.NAME), tmp = new File(c.getCacheDir(), ApkProvider.NAME + ".part");
            if (ready == latest && out.exists()) return latest;
            if (!fetch(apk, tmp) || !tmp.renameTo(out)) return 0;
            ready = latest;
            return latest;
        } catch (Exception e) {
            return 0;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static boolean fetch(String url, File to) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(60000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", "SweetTV");
            if (h.getResponseCode() != 200) return false;
            long len = h.getContentLength();
            InputStream in = h.getInputStream();
            OutputStream out = new FileOutputStream(to);
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            } finally {
                out.close();
                in.close();
            }
            return len < 0 || total == len;
        } catch (Exception e) {
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /** Opens the TV's installer for the downloaded version. Returns false when the TV must allow installs first. */
    static boolean install(Context c) {
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(s);
            } catch (Exception ignored) {
                // this TV has no such screen
            }
            return false;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.uri(), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }
}
