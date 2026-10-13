package com.nadav.depthwall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DepthWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() { return new DepthEngine(); }

    private class DepthEngine extends Engine implements SensorEventListener,
            SharedPreferences.OnSharedPreferenceChangeListener, DepthRenderer.Host {

        private static final long HOUR = 3_600_000L;

        private WallpaperGLView glView;
        private DepthRenderer renderer;
        private SensorManager sm;
        private Sensor sensor;
        private boolean usingRotationVector;
        private boolean sensorOn;
        private boolean visible;
        private SharedPreferences prefs;
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private GestureDetector gestures;
        private File current;
        private long listVersion;

        // orientation tracking
        private final float[] rot = new float[9];
        private final float[] ori = new float[3];
        private float refPitch = Float.NaN, refRoll;
        private float gx, gy, gz;

        private final BroadcastReceiver screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_USER_PRESENT.equals(a) && interval() == 1) next(true);
                else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(a)) updateSensor();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            setOffsetNotificationsEnabled(true);
            prefs = ImageStore.prefs(DepthWallpaperService.this);
            prefs.registerOnSharedPreferenceChangeListener(this);
            listVersion = prefs.getLong(ImageStore.K_VERSION, 0);

            sm = getSystemService(SensorManager.class);
            sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            usingRotationVector = sensor != null;
            if (sensor == null) sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

            renderer = new DepthRenderer(this);
            applyParams();
            glView = new WallpaperGLView(DepthWallpaperService.this);
            glView.setEGLContextClientVersion(3);
            glView.setEGLConfigChooser(8, 8, 8, 0, 0, 0);
            glView.setPreserveEGLContextOnPause(true);
            glView.setRenderer(renderer);
            glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

            gestures = new GestureDetector(DepthWallpaperService.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            next(true);
                            return true;
                        }
                    });

            IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(screenRx, f);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            stopSensor();
            try { unregisterReceiver(screenRx); } catch (Exception ignored) {}
            prefs.unregisterOnSharedPreferenceChangeListener(this);
            glView.destroy();
            io.shutdownNow();
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) {
                glView.onResume();
                maybeAdvanceByTime();
                updateSensor();
                glView.requestRender();
            } else {
                stopSensor();
                glView.onPause();
            }
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            gestures.onTouchEvent(event);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                     int xPx, int yPx) {
            if (xStep <= 0f || xStep >= 1f) { renderer.setScroll(0f); return; } // single-page launcher
            renderer.setScroll(xOffset - 0.5f);
            glView.requestRender();
        }

        // ---------- DepthRenderer.Host ----------

        @Override public void requestRender() { glView.requestRender(); }

        @Override public void onSurfaceReady() {
            main.post(() -> {
                if (current == null || !current.exists()) current = pick(prefs.getInt(ImageStore.K_INDEX, 0));
                load(current, false);
            });
        }

        // ---------- images ----------

        private int interval() { return prefs.getInt(ImageStore.K_INTERVAL, 1); }

        private File pick(int index) {
            List<File> l = ImageStore.list(DepthWallpaperService.this);
            if (l.isEmpty()) return null;
            int i = ((index % l.size()) + l.size()) % l.size();
            return l.get(i);
        }

        private void next(boolean fade) {
            List<File> l = ImageStore.list(DepthWallpaperService.this);
            if (l.size() < 2) return;
            int i = (prefs.getInt(ImageStore.K_INDEX, 0) + 1) % l.size();
            prefs.edit().putInt(ImageStore.K_INDEX, i)
                    .putLong(ImageStore.K_LAST, System.currentTimeMillis()).apply();
            current = l.get(i);
            load(current, fade);
        }

        private void maybeAdvanceByTime() {
            int iv = interval();
            if (iv != 2 && iv != 3) return;
            long period = iv == 2 ? HOUR : 24 * HOUR;
            long last = prefs.getLong(ImageStore.K_LAST, 0);
            if (System.currentTimeMillis() - last >= period) next(false);
        }

        private void load(File f, boolean fade) {
            if (f == null) return;
            io.execute(() -> {
                Bitmap img = ImageStore.loadFull(f);
                Bitmap dep = ImageStore.loadFull(ImageStore.depthFor(f));
                if (img == null || dep == null) return;
                glView.queueEvent(() -> {
                    renderer.setImage(img, dep, fade);
                    glView.requestRender();
                });
            });
        }

        // ---------- prefs ----------

        private void applyParams() {
            renderer.setParams(prefs.getInt(ImageStore.K_STRENGTH, 55) / 100f,
                    prefs.getBoolean(ImageStore.K_DEPTH, true),
                    prefs.getBoolean(ImageStore.K_SCROLL, true));
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            if (key == null) return;
            switch (key) {
                case ImageStore.K_VERSION: {
                    long v = p.getLong(ImageStore.K_VERSION, 0);
                    if (v == listVersion) break;
                    listVersion = v;
                    File f = pick(p.getInt(ImageStore.K_INDEX, 0));
                    if (f != null && !f.equals(current)) { current = f; load(f, true); }
                    else if (f == null) current = null;
                    break;
                }
                case ImageStore.K_INDEX: {
                    File f = pick(p.getInt(ImageStore.K_INDEX, 0));
                    if (f != null && !f.equals(current)) { current = f; load(f, true); }
                    break;
                }
                case ImageStore.K_SAVER:
                    updateSensor();
                    break;
                default:
                    applyParams();
                    glView.requestRender();
            }
        }

        // ---------- sensor (only while visible, off in battery saver) ----------

        private void updateSensor() {
            PowerManager pm = getSystemService(PowerManager.class);
            boolean saver = prefs.getBoolean(ImageStore.K_SAVER, true) && pm.isPowerSaveMode();
            if (visible && !saver && sensor != null) startSensor();
            else {
                stopSensor();
                renderer.setTilt(0f, 0f);
                glView.requestRender();
            }
        }

        private void startSensor() {
            if (sensorOn) return;
            refPitch = Float.NaN;
            // ~30 Hz is plenty for a smoothed parallax and keeps the sensor hub cheap
            sm.registerListener(this, sensor, 33_333);
            sensorOn = true;
        }

        private void stopSensor() {
            if (!sensorOn) return;
            sm.unregisterListener(this);
            sensorOn = false;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            float pitch, roll;
            if (usingRotationVector) {
                SensorManager.getRotationMatrixFromVector(rot, e.values);
                SensorManager.getOrientation(rot, ori);
                pitch = ori[1];
                roll = ori[2];
            } else {
                gx += (e.values[0] - gx) * 0.2f;
                gy += (e.values[1] - gy) * 0.2f;
                gz += (e.values[2] - gz) * 0.2f;
                pitch = (float) Math.atan2(-gy, Math.sqrt(gx * gx + gz * gz));
                roll = (float) Math.atan2(gx, gz);
            }
            if (Float.isNaN(refPitch)) { refPitch = pitch; refRoll = roll; }
            // the neutral pose slowly follows how the phone is being held
            refPitch += (pitch - refPitch) * 0.015f;
            refRoll += (wrap(roll - refRoll)) * 0.015f;

            final float range = 0.35f; // ~20 degrees for full effect
            float x = clamp(wrap(roll - refRoll) / range);
            float y = clamp((pitch - refPitch) / range);
            // only wake the GPU when the change is visible; a still phone draws nothing
            if (Math.abs(x - sentX) > 0.004f || Math.abs(y - sentY) > 0.004f) {
                sentX = x; sentY = y;
                renderer.setTilt(x, y);
                glView.requestRender();
            }
        }

        private float sentX, sentY;

        @Override public void onAccuracyChanged(Sensor s, int a) {}

        private float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }

        private float wrap(float a) {
            while (a > Math.PI) a -= 2 * Math.PI;
            while (a < -Math.PI) a += 2 * Math.PI;
            return a;
        }

        /** GLSurfaceView that draws into the wallpaper surface instead of a window. */
        private class WallpaperGLView extends GLSurfaceView {
            WallpaperGLView(Context c) { super(c); }
            @Override public SurfaceHolder getHolder() { return getSurfaceHolder(); }
            void destroy() { super.onDetachedFromWindow(); }
        }
    }
}
