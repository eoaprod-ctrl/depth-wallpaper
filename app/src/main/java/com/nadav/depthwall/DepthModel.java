package com.nadav.depthwall;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** MiDaS small (256x256) monocular depth model. Output: relative inverse depth, higher = nearer. */
final class DepthModel {

    static final int SIZE = 256;
    private static Interpreter interpreter;

    private DepthModel() {}

    private static synchronized Interpreter get(Context c) throws Exception {
        if (interpreter == null) {
            if (!Models.available(c, Models.Model.DEPTH)) throw new IllegalStateException("model not downloaded");
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
            interpreter = new Interpreter(Models.file(c, Models.Model.DEPTH), o);
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
