package fm.sweet977.tv;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the website's files on the TV.
 * The LED page, its code, styles, fonts, pictures and videos are saved the first time the screen uses them
 * and loaded from the TV after that, so a slow or lost connection never stalls or blanks the screen.
 * Only the live data (what to show right now) still comes over the internet, and it is small.
 */
final class Store {
    private static final long LIMIT = 1500L * 1024 * 1024;      // keep up to 1.5 GB
    private static final long BIG = 400L * 1024 * 1024;         // files bigger than this are not kept
    private static final long UNUSED = 30L * 24 * 3600 * 1000;  // forget files not used for 30 days
    private static final Set<String> CDN = new HashSet<>(Arrays.asList(
            "fonts.googleapis.com", "fonts.gstatic.com", "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com"));
    private static final List<String> MEDIA = Arrays.asList(
            ".mp4", ".webm", ".mov", ".m4v", ".mkv", ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".wav", ".flac");

    private final File dir;
    private final File page;
    private volatile String host;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Set<String> queued = Collections.synchronizedSet(new HashSet<String>());
    volatile String userAgent = "";
    volatile boolean online = true;

    Store(Context c) {
        dir = new File(c.getFilesDir(), "keep");
        dir.mkdirs();
        page = new File(dir, "page.html");
        host = Site.host(c);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                tidy();
            }
        });
    }

    /** The website moved: save files under its new address from now on. */
    void setHost(String h) {
        host = h == null ? "" : h;
        if (page.exists()) page.delete();
    }

    boolean hasPage() {
        return page.exists() && page.length() > 0;
    }

    /** Called by the web view for every request it makes. Null means "let the web view load it itself". */
    WebResourceResponse intercept(WebResourceRequest r) {
        try {
            if (!"GET".equalsIgnoreCase(r.getMethod())) return null;
            Uri u = r.getUrl();
            String h = u.getHost();
            if (h == null) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            boolean own = h.equalsIgnoreCase(host);
            if (own && r.isForMainFrame()) return page(u.toString());
            if (!keepable(own, h, path)) return null;
            String url = u.toString();
            File f = fileFor(url);
            if (!f.exists()) {
                if (isMedia(path)) { // videos and sound play straight from the internet the first time, and are saved in the background
                    later(url);
                    return null;
                }
                if (!download(url, f)) return null;
            }
            return serve(f, header(r.getRequestHeaders(), "Range"), !own);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean keepable(boolean own, String h, String path) {
        if (own) {
            return path.startsWith("/assets/") || path.startsWith("/uploads/") || path.startsWith("/icons/")
                    || path.equals("/sweet-php.js") || path.equals("/manifest.webmanifest");
        }
        return CDN.contains(h.toLowerCase());
    }

    private static boolean isMedia(String path) {
        String p = path.toLowerCase();
        for (String m : MEDIA) if (p.endsWith(m)) return true;
        return false;
    }

    /** The LED page itself: the newest copy when the internet works, the saved copy when it does not. */
    private WebResourceResponse page(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url, 8000, 20000);
            int code = c.getResponseCode();
            if (code == 200) {
                byte[] body = readAll(c.getInputStream());
                String text = new String(body, "UTF-8");
                passCookies(url, c);
                online = true;
                if (text.contains("sweet-php.js")) { // the real page, not the host's security check
                    write(page, body);
                    return html(body);
                }
                return null; // the host's security check page: the web view runs it, then loads the page again
            }
            online = false;
            return hasPage() ? html(readAll(new FileInputStream(page))) : null;
        } catch (IOException e) {
            online = false;
            try {
                return hasPage() ? html(readAll(new FileInputStream(page))) : null;
            } catch (IOException e2) {
                return null;
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static WebResourceResponse html(byte[] body) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", h, new ByteArrayInputStream(body));
    }

    /** Cookies the website sets on the page (its session) go to the web view too. */
    private static void passCookies(String url, HttpURLConnection c) {
        Map<String, List<String>> headers = c.getHeaderFields();
        if (headers == null) return;
        CookieManager cm = CookieManager.getInstance();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie") || e.getValue() == null) continue;
            for (String v : e.getValue()) cm.setCookie(url, v);
        }
        cm.flush();
    }

    private HttpURLConnection open(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        if (userAgent.length() > 0) c.setRequestProperty("User-Agent", userAgent);
        String ck = CookieManager.getInstance().getCookie(url);
        if (ck != null) c.setRequestProperty("Cookie", ck);
        return c;
    }

    /** Saves one file. Fails (and lets the web view try) when the host answers with its check page instead of the file. */
    private boolean download(String url, File f) {
        HttpURLConnection c = null;
        File tmp = new File(dir, f.getName() + ".part" + Thread.currentThread().getId());
        try {
            c = open(url, 15000, 45000);
            if (c.getResponseCode() != 200) return false;
            long len = c.getContentLength();
            if (len > BIG) return false;
            String type = c.getContentType() == null ? "application/octet-stream" : c.getContentType();
            String mime = type.split(";")[0].trim().toLowerCase();
            String path = Uri.parse(url).getPath() == null ? "" : Uri.parse(url).getPath().toLowerCase();
            if (mime.equals("text/html") && !path.endsWith(".html")) return false;
            String charset = "";
            int i = type.toLowerCase().indexOf("charset=");
            if (i >= 0) charset = type.substring(i + 8).replace("\"", "").trim();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > BIG) return false;
                }
            } finally {
                out.close();
                in.close();
            }
            if (len >= 0 && total != len) return false; // cut off part way
            write(new File(f.getPath() + ".m"), (mime + "\n" + charset).getBytes("UTF-8"));
            if (!tmp.renameTo(f)) return false;
            online = true;
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (tmp.exists()) tmp.delete();
            if (c != null) c.disconnect();
        }
    }

    /** Saves a file in the background, one at a time, so playback is never held up. */
    private void later(final String url) {
        if (!queued.add(url)) return;
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = fileFor(url);
                    if (!f.exists()) download(url, f);
                } finally {
                    queued.remove(url);
                }
            }
        });
    }

    /** Sends a saved file to the web view; videos get the part they ask for (seeking works). */
    private WebResourceResponse serve(File f, String range, boolean cors) throws IOException {
        String mime = "application/octet-stream", charset = null;
        File meta = new File(f.getPath() + ".m");
        if (meta.exists()) {
            String[] m = new String(readAll(new FileInputStream(meta)), "UTF-8").split("\n", -1);
            if (m.length > 0 && m[0].length() > 0) mime = m[0];
            if (m.length > 1 && m[1].length() > 0) charset = m[1];
        }
        long now = System.currentTimeMillis();
        if (now - f.lastModified() > 24L * 3600 * 1000) f.setLastModified(now); // remember it is still in use
        long size = f.length();
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "max-age=31536000");
        if (cors) h.put("Access-Control-Allow-Origin", "*");
        if (range != null && range.startsWith("bytes=")) {
            String[] p = range.substring(6).split(",")[0].split("-", -1);
            long start = p[0].trim().length() > 0 ? Long.parseLong(p[0].trim()) : 0;
            long end = p.length > 1 && p[1].trim().length() > 0 ? Long.parseLong(p[1].trim()) : size - 1;
            if (p[0].trim().length() == 0 && p.length > 1 && p[1].trim().length() > 0) { // "bytes=-500": the last 500 bytes
                start = Math.max(0, size - Long.parseLong(p[1].trim()));
                end = size - 1;
            }
            end = Math.min(end, size - 1);
            if (start <= end && start < size) {
                FileInputStream in = new FileInputStream(f);
                long skipped = 0;
                while (skipped < start) {
                    long s = in.skip(start - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
                h.put("Content-Length", String.valueOf(end - start + 1));
                return new WebResourceResponse(mime, charset, 206, "Partial Content", h, new Limited(in, end - start + 1));
            }
        }
        h.put("Content-Length", String.valueOf(size));
        return new WebResourceResponse(mime, charset, 200, "OK", h, new FileInputStream(f));
    }

    /** Clears everything saved (the LED page downloads it again). */
    void clear() {
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) f.delete();
    }

    long size() {
        long t = 0;
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) t += f.length();
        return t;
    }

    /** Removes files not used for a month, then the least recently used ones while over the limit. */
    private void tidy() {
        File[] all = dir.listFiles();
        if (all == null) return;
        long now = System.currentTimeMillis(), total = 0;
        for (File f : all) {
            if (f.getName().contains(".part")) { f.delete(); continue; }
            if (f.equals(page) || f.getName().endsWith(".m")) continue;
            if (now - f.lastModified() > UNUSED) { f.delete(); new File(f.getPath() + ".m").delete(); continue; }
            total += f.length();
        }
        if (total <= LIMIT) return;
        all = dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : all) {
            if (total <= LIMIT) break;
            if (f.equals(page) || f.getName().endsWith(".m")) continue;
            total -= f.length();
            f.delete();
            new File(f.getPath() + ".m").delete();
        }
    }

    private File fileFor(String url) {
        return new File(dir, sha1(url));
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void write(File f, byte[] data) throws IOException {
        File tmp = new File(dir, f.getName() + ".part" + Thread.currentThread().getId());
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("could not save " + f.getName());
        }
    }

    /** Reads only the requested part of a file. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long length) {
            super(in);
            left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
