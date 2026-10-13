package com.nadav.depthwall;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** MiDaS small (256x256) monocular depth model. Output: relative inverse depth, higher = nearer. */
final class DepthModel {

    static final int SIZE = 256;
    private static Interpreter interpreter;

    private DepthModel() {}

    static final String MODEL_URL = "https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite";
    private static final long EXPECTED_BYTES = 66_338_288L;

    interface Progress { void onProgress(int percent); }

    static File file(Context c) { return new File(c.getFilesDir(), "midas.tflite"); }

    static boolean available(Context c) { return file(c).length() == EXPECTED_BYTES; }

    /** Downloads the model once (kept out of the APK so the installer stays small). */
    static boolean download(Context c, Progress p) {
        if (available(c)) return true;
        File tmp = new File(c.getFilesDir(), "midas.tflite.part");
        HttpURLConnection con = null;
        try {
            URL u = new URL(MODEL_URL);
            for (int hop = 0; hop < 5; hop++) {   // follow GitHub's redirect to its file host
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
            long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : EXPECTED_BYTES;
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    int pct = (int) (got * 100 / total);
                    if (pct != last) { last = pct; p.onProgress(pct); }
                }
            }
            if (tmp.length() != EXPECTED_BYTES) { tmp.delete(); return false; }
            return tmp.renameTo(file(c));
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static synchronized Interpreter get(Context c) throws Exception {
        if (interpreter == null) {
            if (!available(c)) throw new IllegalStateException("model not downloaded");
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
            interpreter = new Interpreter(file(c), o);
        }
        return interpreter;
    }

    /** Frees the model memory (~70 MB) once imports are done. */
    static synchronized void release() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
    }

    /** Returns SIZE*SIZE depth values (row-major, stretched to the image), or null on failure. */
    static synchronized float[] run(Context c, Bitmap src) {
        try {
            Interpreter it = get(c);
            Bitmap s = Bitmap.createScaledBitmap(src, SIZE, SIZE, true);
            int[] px = new int[SIZE * SIZE];
            s.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
            if (s != src) s.recycle();
            ByteBuffer in = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder());
            for (int p : px) {
                in.putFloat((((p >> 16) & 0xFF) - 123.675f) / 58.395f);
                in.putFloat((((p >> 8) & 0xFF) - 116.28f) / 57.12f);
                in.putFloat(((p & 0xFF) - 103.53f) / 57.375f);
            }
            in.rewind();
            ByteBuffer out = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder());
            it.run(in, out);
            out.rewind();
            FloatBuffer fb = out.asFloatBuffer();
            float[] d = new float[SIZE * SIZE];
            fb.get(d);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }
}
