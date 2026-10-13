package com.nadav.depthwall;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stores the user's chosen images (pre-scaled to the screen) plus a small depth map for each. */
public final class ImageStore {

    public static final String PREFS = "depthwall";
    public static final String K_STRENGTH = "strength";     // 0..100
    public static final String K_BLUR = "blur";             // portrait-style background blur
    public static final String K_SHADOW = "shadow";         // subject casts a soft shadow
    public static final String K_INTERVAL = "interval";     // 0 manual, 1 unlock, 2 hourly, 3 daily
    public static final String K_SAVER = "saver";           // follow battery saver
    public static final String K_SCROLL = "scroll";         // home-screen page parallax
    public static final String K_INDEX = "index";
    public static final String K_LAST = "lastChange";
    public static final String K_VERSION = "version";       // bumped whenever the image list changes

    private ImageStore() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "images");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Image files in stable (name) order. */
    public static List<File> list(Context c) {
        File[] fs = dir(c).listFiles((f, n) -> n.matches("img_\\d+\\.jpg"));
        List<File> out = new ArrayList<>();
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        out.addAll(Arrays.asList(fs));
        return out;
    }

    public static File auxFor(File img) {
        // bump the suffix whenever the layer format changes: old layers are then rebuilt
        return new File(img.getParentFile(), img.getName().replace(".jpg", ".aux3.png"));
    }

    public static File bgFor(File img) {
        return new File(img.getParentFile(), img.getName().replace(".jpg", ".bg.jpg"));
    }

    private static File legacyDepthFor(File img) {
        return new File(img.getParentFile(), img.getName().replace(".jpg", ".depth.png"));
    }

    /** True when the layers were built without the depth model (e.g. download failed). */
    public static boolean builtWithoutModel(File img) {
        return new File(auxFor(img).getPath() + ".simple").exists();
    }

    public static boolean needsLayers(File img) {
        return !auxFor(img).exists() || !bgFor(img).exists();
    }

    /** (Re)builds the subject/background layers for an already imported image. */
    public static void buildLayers(Context c, File img) throws IOException {
        Bitmap bmp = loadFull(img);
        if (bmp == null) throw new IOException("decode failed");
        try {
            LayerBuilder.build(c, bmp, auxFor(img), bgFor(img));
        } finally {
            bmp.recycle();
        }
        cleanupLegacy(img);
        bump(c);
    }

    private static void cleanupLegacy(File img) {
        legacyDepthFor(img).delete();
        File old = new File(img.getParentFile(), img.getName().replace(".jpg", ".aux.png"));
        old.delete();
        new File(old.getPath() + ".simple").delete();
    }

    public static void remove(Context c, File img) {
        img.delete();
        auxFor(img).delete();
        new File(auxFor(img).getPath() + ".simple").delete();
        bgFor(img).delete();
        cleanupLegacy(img);
        bump(c);
    }

    public static void bump(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(K_VERSION, System.nanoTime()).apply();
    }

    /** Longest screen side; images are stored a bit larger so parallax never shows soft pixels. */
    private static int targetLongSide(Context c) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = c.getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        return Math.min(4096, Math.round(longSide * 1.35f));
    }

    /** Decode (with EXIF orientation), downscale to screen quality, save JPEG + depth map. */
    public static File importImage(Context c, Uri uri) throws IOException {
        final int maxSide = targetLongSide(c);
        ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
        Bitmap bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float scale = Math.min(1f, maxSide / (float) Math.max(w, h));
            // never downscale below the screen's short side when cropping
            decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);
        });
        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap conv = bmp.copy(Bitmap.Config.ARGB_8888, false);
            bmp.recycle();
            bmp = conv;
        }

        File out = new File(dir(c), "img_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fo = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fo);
        }
        try {
            LayerBuilder.build(c, bmp, auxFor(out), bgFor(out));
        } finally {
            bmp.recycle();
        }
        bump(c);
        return out;
    }

    public static Bitmap loadFull(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    public static Bitmap loadThumb(File f, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }
}
