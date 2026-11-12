package fm.sweet977.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Which website the LED screen shows. It can move without reinstalling the app:
 * the address is read from site.txt in the app's GitHub project (at startup, once a day, and whenever the
 * website can't be reached), and can also be typed in from the TV menu.
 */
final class Site {
    private static final String KEY = "site";
    private static final String SEEN = "siteRemote";

    private Site() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** The website's address, ending in "/". */
    static String get(Context c) {
        String s = prefs(c).getString(KEY, null);
        return s != null ? s : Config.SITE;
    }

    /** The LED page: a page address (not #led), so the host's security check can't drop it. */
    static String start(Context c) {
        return get(c) + "led";
    }

    static String host(Context c) {
        String h = Uri.parse(get(c)).getHost();
        return h == null ? "" : h;
    }

    static void set(Context c, String site) {
        prefs(c).edit().putString(KEY, site).apply();
    }

    /** Turns what someone typed ("sweet977.com", "https://sweet977.com/led") into "https://sweet977.com/". Null if it isn't an address. */
    static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() == 0 || s.contains(" ")) return null;
        if (!s.toLowerCase().matches("^https?://.*")) s = "https://" + s;
        Uri u = Uri.parse(s);
        String host = u.getHost();
        if (host == null || !host.contains(".")) return null;
        String path = u.getPath() == null ? "" : u.getPath();
        path = path.replaceAll("/(led|tv)/?$", "/");
        if (!path.endsWith("/")) path = path + "/";
        if (!path.startsWith("/")) path = "/" + path;
        String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase();
        return scheme + "://" + host.toLowerCase() + (u.getPort() > 0 ? ":" + u.getPort() : "") + path;
    }

    /**
     * Reads site.txt from GitHub. Returns the new address when the file changed since it was last read
     * (and switches to it), or null. A changed file wins over an address typed on the TV; an unchanged one does not.
     */
    static String checkRemote(Context c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("https://raw.githubusercontent.com/" + Config.REPO + "/main/site.txt?t=" + System.currentTimeMillis()).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setUseCaches(false);
            h.setRequestProperty("User-Agent", "SweetTV");
            if (h.getResponseCode() != 200) return null;
            String text = new String(Store.readAll(h.getInputStream()), "UTF-8");
            String site = null;
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                site = clean(line);
                break;
            }
            if (site == null) return null;
            String seen = prefs(c).getString(SEEN, null);
            if (site.equals(seen)) return null;
            prefs(c).edit().putString(SEEN, site).apply();
            if (site.equals(get(c))) return null;
            set(c, site);
            return site;
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
