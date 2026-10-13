package com.nadav.depthwall;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** The ML models are downloaded once on first use (kept out of the APK so it stays small). */
final class Models {

    private static final String BASE =
            "https://github.com/eoaprod-ctrl/depth-wallpaper/releases/download/models/";

    enum Model {
        DEPTH("midas.tflite", 66_338_288L),     // MiDaS v2.1 small — depth
        INPAINT("migan.tflite", 16_312_640L);   // MI-GAN 512 (Places2) — background fill

        final String file;
        final long bytes;
        Model(String file, long bytes) { this.file = file; this.bytes = bytes; }
    }

    interface Progress { void onProgress(int percent); }

    private Models() {}

    static File file(Context c, Model m) { return new File(c.getFilesDir(), m.file); }

    static boolean available(Context c, Model m) { return file(c, m).length() == m.bytes; }

    static boolean allAvailable(Context c) {
        for (Model m : Model.values()) if (!available(c, m)) return false;
        return true;
    }

    static long missingBytes(Context c) {
        long t = 0;
        for (Model m : Model.values()) if (!available(c, m)) t += m.bytes;
        return t;
    }

    /** Downloads every missing model; progress is over the total missing size. */
    static boolean downloadAll(Context c, Progress p) {
        long total = Math.max(1, missingBytes(c));
        long[] done = {0};
        for (Model m : Model.values()) {
            if (available(c, m)) continue;
            long base = done[0];
            if (!download(c, m, got -> p.onProgress((int) ((base + got) * 100 / total)))) return false;
            done[0] += m.bytes;
        }
        return true;
    }

    private interface Bytes { void onBytes(long got); }

    private static boolean download(Context c, Model m, Bytes cb) {
        File tmp = new File(c.getFilesDir(), m.file + ".part");
        HttpURLConnection con = null;
        try {
            URL u = new URL(BASE + m.file);
            for (int hop = 0; hop < 6; hop++) {   // GitHub redirects to its file host
                con = (HttpURLConnection) u.openConnection();
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                int code = con.getResponseCode();
                if (code / 100 == 3) {
                    u = new URL(u, con.getHeaderField("Location"));
                    con.disconnect();
                    continue;
                }
                if (code != 200) return false;
                break;
            }
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0, lastReport = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (got - lastReport > 256 * 1024) { lastReport = got; cb.onBytes(got); }
                }
            }
            if (tmp.length() != m.bytes) { tmp.delete(); return false; }
            return tmp.renameTo(file(c, m));
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
