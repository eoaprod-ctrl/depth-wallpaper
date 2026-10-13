package com.nadav.depthwall;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executors;

/**
 * Lets the user pick exactly which object pops out: tap an object to select it (grows along
 * the depth map, stops at depth edges), then refine with an add / erase brush.
 */
public class EditorActivity extends Activity {

    static final String EXTRA_FILE = "file";

    private static final int C_BG = 0xFF0E1116, C_TEXT = 0xFFE8EDF4, C_MUTED = 0xFF8A94A6,
            C_ACCENT = 0xFF7FD1FF;
    private static final int TAP = 0, ADD = 1, ERASE = 2;

    private File img;
    private int w, h;
    private float[] depth, mask, autoMask;
    private final ArrayDeque<float[]> undo = new ArrayDeque<>();
    private int mode = TAP;
    private int brush = 18;
    private MaskView view;
    private final Button[] modeButtons = new Button[3];
    private TextView hint;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        img = new File(getIntent().getStringExtra(EXTRA_FILE));
        if (!img.exists() || ImageStore.needsLayers(img)) {
            Toast.makeText(this, "התמונה עדיין בעיבוד — נסה שוב בעוד רגע", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Bitmap dm = BitmapFactory.decodeFile(ImageStore.depthMapFor(img).getAbsolutePath());
        Bitmap aux = BitmapFactory.decodeFile(ImageStore.auxFor(img).getAbsolutePath());
        if (dm == null || aux == null) { finish(); return; }
        w = dm.getWidth(); h = dm.getHeight();
        int n = w * h;
        int[] px = new int[n];
        dm.getPixels(px, 0, w, 0, 0, w, h);
        depth = new float[n];
        for (int i = 0; i < n; i++) depth[i] = (px[i] & 0xFF) / 255f;
        dm.recycle();
        Bitmap auxS = (aux.getWidth() == w && aux.getHeight() == h) ? aux : Bitmap.createScaledBitmap(aux, w, h, true);
        auxS.getPixels(px, 0, w, 0, 0, w, h);
        if (auxS != aux) auxS.recycle();
        aux.recycle();
        autoMask = new float[n];
        for (int i = 0; i < n; i++) autoMask[i] = ((px[i] >> 8) & 0xFF) > 127 ? 1f : 0f;
        float[] um = ImageStore.loadUserMask(img);
        mask = (um != null && um.length == n) ? um : autoMask.clone();
        for (int i = 0; i < n; i++) mask[i] = mask[i] > 0.5f ? 1f : 0f;

        Bitmap photo = ImageStore.loadThumb(img, 1600);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("סימון האובייקט להבלטה");
        title.setTextColor(C_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), dp(18), dp(16), dp(2));
        root.addView(title);
        hint = new TextView(this);
        hint.setTextColor(C_MUTED);
        hint.setTextSize(13);
        hint.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(hint);

        view = new MaskView(this, photo);
        root.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // modes
        LinearLayout modes = row();
        String[] labels = {"בחירה בהקשה", "מכחול הוספה", "מכחול מחיקה"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            modeButtons[i] = chip(labels[i]);
            modeButtons[i].setOnClickListener(v -> setMode(m));
            modes.addView(modeButtons[i], weight());
        }
        root.addView(modes);

        // brush size
        LinearLayout size = row();
        TextView sl = new TextView(this);
        sl.setText("גודל מכחול");
        sl.setTextColor(C_MUTED);
        sl.setTextSize(13);
        size.addView(sl);
        SeekBar sb = new SeekBar(this);
        sb.setMax(60);
        sb.setProgress(brush - 4);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { brush = p + 4; }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        size.addView(sb, weight());
        root.addView(size);

        // actions
        LinearLayout actions = row();
        Button undoB = chip("בטל");
        undoB.setOnClickListener(v -> { if (!undo.isEmpty()) { mask = undo.pop(); view.refresh(); } });
        Button autoB = chip("אוטומטי");
        autoB.setOnClickListener(v -> { pushUndo(); mask = autoMask.clone(); view.refresh(); });
        Button clearB = chip("נקה");
        clearB.setOnClickListener(v -> { pushUndo(); Arrays.fill(mask, 0f); view.refresh(); });
        actions.addView(undoB, weight());
        actions.addView(autoB, weight());
        actions.addView(clearB, weight());
        root.addView(actions);

        Button save = new Button(this);
        save.setText("שמור והחל על הרקע");
        save.setAllCaps(false);
        save.setTextSize(16);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setTextColor(C_BG);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(C_ACCENT);
        sbg.setCornerRadius(dp(14));
        save.setBackground(sbg);
        save.setStateListAnimator(null);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.setMargins(dp(16), dp(6), dp(16), dp(18));
        root.addView(save, lp);

        setContentView(root);
        setMode(TAP);
    }

    private void setMode(int m) {
        mode = m;
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            if (i == m) { g.setColor(C_ACCENT); modeButtons[i].setTextColor(C_BG); }
            else { g.setColor(0xFF1E242D); modeButtons[i].setTextColor(C_TEXT); }
            modeButtons[i].setBackground(g);
        }
        hint.setText(m == TAP ? "הקש על האובייקט שתרצה להבליט. הקשה על חלק מסומן מבטלת אותו."
                : m == ADD ? "העבר אצבע על חלקים שחסרים בסימון"
                : "העבר אצבע על מה שסומן בטעות");
    }

    private void pushUndo() {
        undo.push(mask.clone());
        while (undo.size() > 15) undo.removeLast();
    }

    // ---------- selection ----------

    /** Grows a region from the tapped point across smooth depth; stops at depth edges. */
    private void selectAt(int sx, int sy) {
        int n = w * h, s = sy * w + sx;
        float d0 = depth[s];
        boolean[] in = new boolean[n];
        int[] q = new int[n];
        int head = 0, tail = 0;
        in[s] = true; q[tail++] = s;
        while (head < tail) {
            int i = q[head++], x = i % w, y = i / w;
            int[] nb = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int j : nb) {
                if (j < 0 || in[j]) continue;
                if (Math.abs(depth[j] - depth[i]) < 0.035f && Math.abs(depth[j] - d0) < 0.22f) {
                    in[j] = true; q[tail++] = j;
                }
            }
        }
        // close small gaps and fill holes inside the picked region
        float[] r = new float[n];
        for (int i = 0; i < n; i++) r[i] = in[i] ? 1f : 0f;
        r = extreme(extreme(r, 3, true), 3, false);
        fillHoles(r);
        pushUndo();
        boolean remove = mask[s] > 0.5f;   // tapping something already selected deselects it
        for (int i = 0; i < n; i++) if (r[i] > 0.5f) mask[i] = remove ? 0f : 1f;
    }

    private void paint(float x, float y, boolean add) {
        int r = brush, r2 = r * r;
        int cx = Math.round(x), cy = Math.round(y);
        for (int yy = Math.max(0, cy - r); yy <= Math.min(h - 1, cy + r); yy++) {
            for (int xx = Math.max(0, cx - r); xx <= Math.min(w - 1, cx + r); xx++) {
                int dx = xx - cx, dy = yy - cy;
                if (dx * dx + dy * dy <= r2) mask[yy * w + xx] = add ? 1f : 0f;
            }
        }
    }

    private float[] extreme(float[] a, int r, boolean max) {
        float[] tmp = new float[a.length], out = new float[a.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float m = a[y * w + x];
            for (int k = -r; k <= r; k++) {
                float v = a[y * w + Math.max(0, Math.min(w - 1, x + k))];
                m = max ? Math.max(m, v) : Math.min(m, v);
            }
            tmp[y * w + x] = m;
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float m = tmp[y * w + x];
            for (int k = -r; k <= r; k++) {
                float v = tmp[Math.max(0, Math.min(h - 1, y + k)) * w + x];
                m = max ? Math.max(m, v) : Math.min(m, v);
            }
            out[y * w + x] = m;
        }
        return out;
    }

    private void fillHoles(float[] m) {
        int n = w * h;
        boolean[] out = new boolean[n];
        int[] q = new int[n];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) { tail = seed(m, out, q, tail, x); tail = seed(m, out, q, tail, (h - 1) * w + x); }
        for (int y = 0; y < h; y++) { tail = seed(m, out, q, tail, y * w); tail = seed(m, out, q, tail, y * w + w - 1); }
        while (head < tail) {
            int i = q[head++], x = i % w, y = i / w;
            if (x > 0) tail = seed(m, out, q, tail, i - 1);
            if (x < w - 1) tail = seed(m, out, q, tail, i + 1);
            if (y > 0) tail = seed(m, out, q, tail, i - w);
            if (y < h - 1) tail = seed(m, out, q, tail, i + w);
        }
        for (int i = 0; i < n; i++) if (!out[i]) m[i] = 1f;
    }

    private static int seed(float[] m, boolean[] seen, int[] q, int tail, int i) {
        if (!seen[i] && m[i] < 0.5f) { seen[i] = true; q[tail++] = i; }
        return tail;
    }

    // ---------- save ----------

    @SuppressWarnings("deprecation")
    private void save() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("מפריד את האובייקט ומשלים את הרקע…");
        pd.setCancelable(false);
        pd.show();
        final float[] m = mask.clone();
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean ok = true;
            try {
                ImageStore.saveUserMask(img, m, w, h);
                ImageStore.buildLayers(this, img);
            } catch (Throwable t) {
                ok = false;
            }
            DepthModel.release();
            Inpainter.release();
            final boolean done = ok;
            main.post(() -> {
                pd.dismiss();
                Toast.makeText(this, done ? "נשמר — הרקע עודכן" : "השמירה נכשלה", Toast.LENGTH_SHORT).show();
                if (done) finish();
            });
        });
    }

    // ---------- ui helpers ----------

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(12), dp(6), dp(12), dp(0));
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private Button chip(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(C_TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0xFF1E242D);
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Shows the photo with the current selection tinted on top. */
    private class MaskView extends View {
        private final Bitmap photo;
        private final Bitmap overlay;
        private final int[] ov;
        private final RectF dst = new RectF();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float lastX = -1, lastY = -1;
        private boolean touching;

        MaskView(Context c, Bitmap photo) {
            super(c);
            this.photo = photo;
            overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            ov = new int[w * h];
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(Color.WHITE);
            refresh();
        }

        void refresh() {
            for (int i = 0; i < ov.length; i++) ov[i] = mask[i] > 0.5f ? 0x8C2FB8FF : 0;
            overlay.setPixels(ov, 0, w, 0, 0, w, h);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float vw = getWidth(), vh = getHeight();
            float s = Math.min(vw / w, vh / h);
            float dw = w * s, dh = h * s;
            dst.set((vw - dw) / 2, (vh - dh) / 2, (vw + dw) / 2, (vh + dh) / 2);
            if (photo != null) c.drawBitmap(photo, null, dst, paint);
            c.drawBitmap(overlay, null, dst, paint);
            if (touching && mode != TAP) {
                c.drawCircle(lastX, lastY, brush * s, ring);
            }
        }

        private float toX(float vx) { return (vx - dst.left) / dst.width() * w; }
        private float toY(float vy) { return (vy - dst.top) / dst.height() * h; }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = toX(e.getX()), y = toY(e.getY());
            lastX = e.getX(); lastY = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    if (mode == TAP) {
                        int ix = Math.round(x), iy = Math.round(y);
                        if (ix >= 0 && iy >= 0 && ix < w && iy < h) { selectAt(ix, iy); refresh(); }
                    } else {
                        pushUndo();
                        paint(x, y, mode == ADD);
                        refresh();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode != TAP) {
                        for (int k = 0; k < e.getHistorySize(); k++)
                            paint(toX(e.getHistoricalX(k)), toY(e.getHistoricalY(k)), mode == ADD);
                        paint(x, y, mode == ADD);
                        refresh();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    invalidate();
                    return true;
            }
            return false;
        }
    }
}
