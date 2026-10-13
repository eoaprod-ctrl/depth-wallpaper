package com.nadav.depthwall;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Splits a photo into two depth layers, once, at import time:
 *  - subject layer: the original photo + a soft cut-out mask of the nearest object
 *  - background layer: the photo with the subject removed and the hole filled in
 * The aux texture packs R = subject depth, G = subject mask, B = background depth.
 * Depths are remapped so the subject/background boundary sits at 0.5 (the focus plane):
 * the subject moves one way, the background the other — that gap is the 3D effect.
 */
final class LayerBuilder {

    private static final int WORK = 768;

    private LayerBuilder() {}

    static void build(Context c, Bitmap full, File auxOut, File bgOut) throws IOException {
        float s = WORK / (float) Math.max(full.getWidth(), full.getHeight());
        int w = Math.max(8, Math.round(full.getWidth() * s));
        int h = Math.max(8, Math.round(full.getHeight() * s));
        int n = w * h;
        Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
        int[] px = new int[n];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();

        float[] r = new float[n], g = new float[n], b = new float[n], gray = new float[n];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            r[i] = ((p >> 16) & 0xFF) / 255f;
            g[i] = ((p >> 8) & 0xFF) / 255f;
            b[i] = (p & 0xFF) / 255f;
            gray[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i];
        }

        // ---- depth: ML model, heuristic fallback ----
        float[] d;
        float[] raw = DepthModel.run(c, full);
        if (raw != null) {
            normalizePercentile(raw, 0.02f, 0.98f);
            d = resize(raw, DepthModel.SIZE, DepthModel.SIZE, w, h);
        } else {
            Bitmap est = DepthEstimator.estimate(full, 192);
            int ew = est.getWidth(), eh = est.getHeight();
            int[] ep = new int[ew * eh];
            est.getPixels(ep, 0, ew, 0, 0, ew, eh);
            est.recycle();
            float[] ed = new float[ew * eh];
            for (int i = 0; i < ed.length; i++) ed[i] = ((ep[i] >> 16) & 0xFF) / 255f;
            d = resize(ed, ew, eh, w, h);
        }
        // snap depth edges to the photo's real edges
        d = guided(gray, d, w, h, 8, 2e-3f);
        clamp01(d);

        // ---- subject mask ----
        float t = otsu(d);
        float[] hard = new float[n];
        for (int i = 0; i < n; i++) hard[i] = d[i] > t ? 1f : 0f;
        hard = minFilter(hard, w, h, 2);           // opening removes specks
        hard = maxFilter(hard, w, h, 2);
        float[] mask = guided(gray, hard, w, h, 3, 1e-4f);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            mask[i] = Math.max(0f, Math.min(1f, (mask[i] - 0.15f) / 0.7f));
            sum += mask[i];
        }
        float frac = (float) (sum / n);
        boolean hasSubject = frac > 0.03f && frac < 0.75f;
        if (!hasSubject) Arrays.fill(mask, 0f);

        // ---- separated depth: exaggerate subject/background gap, focus plane at 0.5 ----
        float focus = 0.35f * t + 0.65f * 0.5f;
        float[] dsep = new float[n];
        for (int i = 0; i < n; i++) {
            float v = 0.35f * d[i] + 0.65f * smooth(t - 0.12f, t + 0.12f, d[i]);
            dsep[i] = Math.max(0f, Math.min(1f, v - focus + 0.5f));
        }
        float[] fgDepth = maxFilter(dsep, w, h, 6); // subject edge pixels carry the subject's depth

        // ---- background: remove the subject and fill the hole ----
        float[] hole = new float[n];
        for (int i = 0; i < n; i++) hole[i] = mask[i] > 0.2f ? 1f : 0f;
        hole = maxFilter(hole, w, h, 10);
        hole = box(hole, w, h, 3);
        float[] known = new float[n];
        for (int i = 0; i < n; i++) {
            hole[i] = Math.min(1f, hole[i] * 1.5f);
            known[i] = hole[i] > 0.01f ? 0f : 1f;
        }
        float[][] filled = pushPull(new float[][]{r, g, b, dsep}, known, w, h);
        float[] bgDepth = new float[n];
        int[] bgPx = new int[n];
        int[] auxPx = new int[n];
        for (int i = 0; i < n; i++) {
            float k = hole[i];
            float rr = r[i] * (1 - k) + filled[0][i] * k;
            float gg = g[i] * (1 - k) + filled[1][i] * k;
            float bb = b[i] * (1 - k) + filled[2][i] * k;
            bgDepth[i] = known[i] > 0 ? dsep[i] : filled[3][i];
            bgPx[i] = 0xFF000000 | (to8(rr) << 16) | (to8(gg) << 8) | to8(bb);
            auxPx[i] = 0xFF000000 | (to8(fgDepth[i]) << 16) | (to8(mask[i]) << 8) | to8(bgDepth[i]);
        }

        Bitmap bg = Bitmap.createBitmap(bgPx, w, h, Bitmap.Config.ARGB_8888);
        try (FileOutputStream fo = new FileOutputStream(bgOut)) {
            bg.compress(Bitmap.CompressFormat.JPEG, 92, fo);
        }
        bg.recycle();
        Bitmap aux = Bitmap.createBitmap(auxPx, w, h, Bitmap.Config.ARGB_8888);
        try (FileOutputStream fo = new FileOutputStream(auxOut)) {
            aux.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
        aux.recycle();
    }

    // ---------- helpers ----------

    private static int to8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    private static void clamp01(float[] a) {
        for (int i = 0; i < a.length; i++) a[i] = Math.max(0f, Math.min(1f, a[i]));
    }

    private static void normalizePercentile(float[] a, float lo, float hi) {
        float[] s = a.clone();
        Arrays.sort(s);
        float l = s[(int) (lo * (s.length - 1))], h = s[(int) (hi * (s.length - 1))];
        float range = Math.max(1e-6f, h - l);
        for (int i = 0; i < a.length; i++) a[i] = Math.max(0f, Math.min(1f, (a[i] - l) / range));
    }

    /** Bilinear resize with pixel-center alignment. */
    static float[] resize(float[] src, int sw, int sh, int dw, int dh) {
        float[] out = new float[dw * dh];
        for (int y = 0; y < dh; y++) {
            float fy = Math.max(0f, Math.min(sh - 1f, (y + 0.5f) * sh / dh - 0.5f));
            int y0 = (int) fy, y1 = Math.min(y0 + 1, sh - 1);
            float ty = fy - y0;
            for (int x = 0; x < dw; x++) {
                float fx = Math.max(0f, Math.min(sw - 1f, (x + 0.5f) * sw / dw - 0.5f));
                int x0 = (int) fx, x1 = Math.min(x0 + 1, sw - 1);
                float tx = fx - x0;
                float top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx;
                float bot = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx;
                out[y * dw + x] = top * (1 - ty) + bot * ty;
            }
        }
        return out;
    }

    private static float otsu(float[] d) {
        int[] hist = new int[256];
        for (float v : d) hist[Math.max(0, Math.min(255, (int) (v * 255f)))]++;
        long total = d.length;
        double sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += i * (double) hist[i];
        double sumB = 0, best = -1;
        long wB = 0;
        int th = 128;
        for (int i = 0; i < 256; i++) {
            wB += hist[i];
            if (wB == 0 || wB == total) continue;
            sumB += i * (double) hist[i];
            double mB = sumB / wB, mF = (sumAll - sumB) / (total - wB);
            double between = (double) wB * (total - wB) * (mB - mF) * (mB - mF);
            if (between > best) { best = between; th = i; }
        }
        return th / 255f;
    }

    /** Guided filter (He et al.) with a grayscale guide: edge-preserving smoothing of p along I's edges. */
    private static float[] guided(float[] I, float[] p, int w, int h, int r, float eps) {
        int n = w * h;
        float[] ip = new float[n], ii = new float[n];
        for (int i = 0; i < n; i++) { ip[i] = I[i] * p[i]; ii[i] = I[i] * I[i]; }
        float[] mI = box(I, w, h, r), mP = box(p, w, h, r), mIP = box(ip, w, h, r), mII = box(ii, w, h, r);
        float[] a = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            float cov = mIP[i] - mI[i] * mP[i];
            float var = mII[i] - mI[i] * mI[i];
            a[i] = cov / (var + eps);
            b[i] = mP[i] - a[i] * mI[i];
        }
        float[] mA = box(a, w, h, r), mB = box(b, w, h, r);
        float[] q = new float[n];
        for (int i = 0; i < n; i++) q[i] = mA[i] * I[i] + mB[i];
        return q;
    }

    /** Separable box filter, clamped edges. Returns a new array. */
    private static float[] box(float[] a, int w, int h, int r) {
        float[] tmp = new float[a.length], out = new float[a.length];
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            float sum = 0;
            for (int x = -r; x <= r; x++) sum += a[row + cl(x, w)];
            for (int x = 0; x < w; x++) {
                tmp[row + x] = sum * inv;
                sum += a[row + cl(x + r + 1, w)] - a[row + cl(x - r, w)];
            }
        }
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int y = -r; y <= r; y++) sum += tmp[cl(y, h) * w + x];
            for (int y = 0; y < h; y++) {
                out[y * w + x] = sum * inv;
                sum += tmp[cl(y + r + 1, h) * w + x] - tmp[cl(y - r, h) * w + x];
            }
        }
        return out;
    }

    private static float[] maxFilter(float[] a, int w, int h, int r) { return extreme(a, w, h, r, true); }
    private static float[] minFilter(float[] a, int w, int h, int r) { return extreme(a, w, h, r, false); }

    private static float[] extreme(float[] a, int w, int h, int r, boolean max) {
        float[] tmp = new float[a.length], out = new float[a.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float m = a[row + x];
                for (int k = -r; k <= r; k++) {
                    float v = a[row + cl(x + k, w)];
                    m = max ? Math.max(m, v) : Math.min(m, v);
                }
                tmp[row + x] = m;
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float m = tmp[y * w + x];
                for (int k = -r; k <= r; k++) {
                    float v = tmp[cl(y + k, h) * w + x];
                    m = max ? Math.max(m, v) : Math.min(m, v);
                }
                out[y * w + x] = m;
            }
        }
        return out;
    }

    private static int cl(int v, int n) { return v < 0 ? 0 : (v >= n ? n - 1 : v); }

    /** Push-pull hole filling: unknown pixels (weight 0) get smoothly interpolated from known ones. */
    private static float[][] pushPull(float[][] ch, float[] wgt, int w, int h) {
        int c = ch.length;
        if (w <= 2 || h <= 2) {
            float[][] out = new float[c][w * h];
            float tw = 0;
            float[] acc = new float[c];
            for (int i = 0; i < w * h; i++) {
                tw += wgt[i];
                for (int k = 0; k < c; k++) acc[k] += ch[k][i] * wgt[i];
            }
            for (int k = 0; k < c; k++) Arrays.fill(out[k], tw > 1e-6f ? acc[k] / tw : 0.5f);
            return out;
        }
        int w2 = (w + 1) / 2, h2 = (h + 1) / 2;
        float[][] cc = new float[c][w2 * h2];
        float[] cw = new float[w2 * h2];
        for (int y = 0; y < h2; y++) {
            for (int x = 0; x < w2; x++) {
                float sw = 0;
                float[] sv = new float[c];
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int i = cl(2 * y + dy, h) * w + cl(2 * x + dx, w);
                        sw += wgt[i];
                        for (int k = 0; k < c; k++) sv[k] += ch[k][i] * wgt[i];
                    }
                }
                int j = y * w2 + x;
                for (int k = 0; k < c; k++) cc[k][j] = sw > 1e-6f ? sv[k] / sw : 0f;
                cw[j] = Math.min(1f, sw);
            }
        }
        float[][] coarse = pushPull(cc, cw, w2, h2);
        float[][] out = new float[c][w * h];
        for (int k = 0; k < c; k++) {
            float[] up = resize(coarse[k], w2, h2, w, h);
            for (int i = 0; i < w * h; i++) out[k][i] = wgt[i] * ch[k][i] + (1 - wgt[i]) * up[i];
        }
        return out;
    }
}
