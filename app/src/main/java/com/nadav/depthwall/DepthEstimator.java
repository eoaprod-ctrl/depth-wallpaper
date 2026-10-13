package com.nadav.depthwall;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Fast heuristic depth map (1 = near, 0 = far), computed once at import time.
 * Combines cues that hold for most photos: lower part of the frame is nearer,
 * detailed/saturated regions are usually the subject, smooth bright regions at the
 * top (sky, walls) are far. Result is heavily smoothed so the parallax warp stays soft.
 */
final class DepthEstimator {

    private DepthEstimator() {}

    static Bitmap estimate(Bitmap src, int maxSide) {
        float s = maxSide / (float) Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(8, Math.round(src.getWidth() * s));
        int h = Math.max(8, Math.round(src.getHeight() * s));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();

        int n = w * h;
        float[] lum = new float[n], sat = new float[n];
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            lum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
            Color.RGBToHSV(r, g, b, hsv);
            sat[i] = hsv[1];
        }

        // detail energy: gradient magnitude
        float[] edge = new float[n];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = lum[i + 1] - lum[i - 1];
                float gy = lum[i + w] - lum[i - w];
                edge[i] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        blur(edge, w, h, Math.max(2, w / 24), 2);
        normalize(edge);
        blur(sat, w, h, Math.max(2, w / 24), 2);
        normalize(sat);

        float[] d = new float[n];
        for (int y = 0; y < h; y++) {
            float vy = y / (float) (h - 1);                 // 0 top .. 1 bottom
            float vertical = smooth(0.05f, 1.0f, vy);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float dx = (x / (float) (w - 1)) - 0.5f;
                float dy = vy - 0.55f;
                float center = 1f - Math.min(1f, (float) Math.sqrt(dx * dx * 1.6f + dy * dy) * 1.6f);
                // sky / flat bright background near the top -> push far
                float skyish = (1f - edge[i]) * lum[i] * (1f - sat[i]) * (1f - vy);
                d[i] = 0.42f * vertical + 0.28f * edge[i] + 0.18f * center + 0.12f * sat[i]
                        - 0.35f * skyish;
            }
        }
        blur(d, w, h, Math.max(3, w / 10), 3);
        normalize(d);

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int v = Math.round(d[i] * 255f);
            out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static void normalize(float[] a) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : a) { if (v < lo) lo = v; if (v > hi) hi = v; }
        float r = hi - lo;
        if (r < 1e-6f) { java.util.Arrays.fill(a, 0.5f); return; }
        for (int i = 0; i < a.length; i++) a[i] = (a[i] - lo) / r;
    }

    /** Separable box blur, repeated passes approximate a gaussian. */
    private static void blur(float[] a, int w, int h, int r, int passes) {
        float[] tmp = new float[a.length];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < h; y++) {
                float sum = 0; int row = y * w;
                for (int x = -r; x <= r; x++) sum += a[row + clamp(x, w)];
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = sum / (2 * r + 1);
                    sum += a[row + clamp(x + r + 1, w)] - a[row + clamp(x - r, w)];
                }
            }
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int y = -r; y <= r; y++) sum += tmp[clamp(y, h) * w + x];
                for (int y = 0; y < h; y++) {
                    a[y * w + x] = sum / (2 * r + 1);
                    sum += tmp[clamp(y + r + 1, h) * w + x] - tmp[clamp(y - r, h) * w + x];
                }
            }
        }
    }

    private static int clamp(int v, int n) { return v < 0 ? 0 : (v >= n ? n - 1 : v); }
}
