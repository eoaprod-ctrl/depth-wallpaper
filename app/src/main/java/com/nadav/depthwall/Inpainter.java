package com.nadav.depthwall;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * MI-GAN (ICCV 2023, Picsart AI Research, MIT) — a mobile GAN that paints in a believable
 * background where the subject was. Input [1,4,512,512] = (keep-0.5, rgb*keep), rgb in [-1,1];
 * output [1,3,512,512] in [-1,1].
 */
final class Inpainter {

    private static final int S = 512;
    private static Interpreter interpreter;

    private Inpainter() {}

    private static synchronized Interpreter get(Context c) {
        if (interpreter == null) {
            Interpreter.Options o = new Interpreter.Options();
            o.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())));
            interpreter = new Interpreter(Models.file(c, Models.Model.INPAINT), o);
        }
        return interpreter;
    }

    static synchronized void release() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
    }

    /**
     * Fills the hole inside {@code px} (full-resolution ARGB, fw x fh). The hole is given at work
     * resolution (hw x hh, values 0..1). Only a square crop around the hole is sent to the model,
     * so small subjects are filled at high detail. Returns false if the model is unavailable.
     */
    static synchronized boolean fill(Context c, Bitmap full, int[] px, int fw, int fh,
                                     float[] hole, int hw, int hh) {
        if (!Models.available(c, Models.Model.INPAINT)) return false;
        try {
            // bounding box of the hole, in work coordinates
            int minX = hw, minY = hh, maxX = -1, maxY = -1;
            for (int y = 0; y < hh; y++) for (int x = 0; x < hw; x++) {
                if (hole[y * hw + x] > 0.01f) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
            if (maxX < 0) return true; // nothing to fill

            // square crop with context around the hole, in full-res coordinates
            float sx = fw / (float) hw, sy = fh / (float) hh;
            float bx0 = minX * sx, by0 = minY * sy, bx1 = (maxX + 1) * sx, by1 = (maxY + 1) * sy;
            float side = Math.max(bx1 - bx0, by1 - by0) * 1.5f + 64;
            side = Math.min(side, Math.max(fw, fh));
            float cx = (bx0 + bx1) / 2, cy = (by0 + by1) / 2;
            int cw = Math.round(Math.min(side, fw)), ch = Math.round(Math.min(side, fh));
            int x0 = Math.round(Math.max(0, Math.min(fw - cw, cx - cw / 2f)));
            int y0 = Math.round(Math.max(0, Math.min(fh - ch, cy - ch / 2f)));

            Bitmap crop = Bitmap.createBitmap(full, x0, y0, cw, ch);
            Bitmap in512 = Bitmap.createScaledBitmap(crop, S, S, true);
            if (crop != full) crop.recycle();
            int[] ip = new int[S * S];
            in512.getPixels(ip, 0, S, 0, 0, S, S);
            in512.recycle();

            // keep-mask at 512 (binary, conservative: anything touched by the hole is repainted)
            float[] keep = new float[S * S];
            for (int y = 0; y < S; y++) {
                float fyy = y0 + (y + 0.5f) * ch / S;
                for (int x = 0; x < S; x++) {
                    float fxx = x0 + (x + 0.5f) * cw / S;
                    float hv = sample(hole, hw, hh, fxx / sx - 0.5f, fyy / sy - 0.5f);
                    keep[y * S + x] = hv > 0.01f ? 0f : 1f;
                }
            }

            int plane = S * S;
            ByteBuffer inBuf = ByteBuffer.allocateDirect(4 * plane * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fin = inBuf.asFloatBuffer();
            for (int i = 0; i < plane; i++) fin.put(i, keep[i] - 0.5f);
            for (int i = 0; i < plane; i++) {
                int p = ip[i];
                float k = keep[i];
                fin.put(plane + i, (((p >> 16) & 0xFF) / 127.5f - 1f) * k);
                fin.put(2 * plane + i, (((p >> 8) & 0xFF) / 127.5f - 1f) * k);
                fin.put(3 * plane + i, ((p & 0xFF) / 127.5f - 1f) * k);
            }
            ByteBuffer outBuf = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder());
            get(c).run(inBuf, outBuf);
            outBuf.rewind();
            FloatBuffer fo = outBuf.asFloatBuffer();
            int[] op = new int[plane];
            for (int i = 0; i < plane; i++) {
                op[i] = 0xFF000000 | (to8(fo.get(i)) << 16) | (to8(fo.get(plane + i)) << 8) | to8(fo.get(2 * plane + i));
            }
            Bitmap out512 = Bitmap.createBitmap(op, S, S, Bitmap.Config.ARGB_8888);
            Bitmap outCrop = Bitmap.createScaledBitmap(out512, cw, ch, true);
            out512.recycle();
            int[] oc = new int[cw * ch];
            outCrop.getPixels(oc, 0, cw, 0, 0, cw, ch);
            outCrop.recycle();

            // paste into the hole with a soft edge
            for (int y = 0; y < ch; y++) {
                int fyI = y0 + y;
                for (int x = 0; x < cw; x++) {
                    int fxI = x0 + x;
                    float k = sample(hole, hw, hh, (fxI + 0.5f) / sx - 0.5f, (fyI + 0.5f) / sy - 0.5f);
                    if (k <= 0.002f) continue;
                    k = Math.min(1f, k);
                    int j = fyI * fw + fxI;
                    px[j] = lerp(px[j], oc[y * cw + x], k);
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int to8(float v) {
        return Math.max(0, Math.min(255, Math.round((v + 1f) * 127.5f)));
    }

    private static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static float sample(float[] a, int w, int h, float x, float y) {
        x = Math.max(0f, Math.min(w - 1f, x));
        y = Math.max(0f, Math.min(h - 1f, y));
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        float tx = x - x0, ty = y - y0;
        return (a[y0 * w + x0] * (1 - tx) + a[y0 * w + x1] * tx) * (1 - ty)
                + (a[y1 * w + x0] * (1 - tx) + a[y1 * w + x1] * tx) * ty;
    }
}
