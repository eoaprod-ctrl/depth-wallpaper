package com.nadav.depthwall;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends Activity {

    private static final int REQ_PICK = 7;
    private static final int C_BG = 0xFF0E1116, C_CARD = 0xFF171B22, C_TEXT = 0xFFE8EDF4,
            C_MUTED = 0xFF8A94A6, C_ACCENT = 0xFF7FD1FF;

    private SharedPreferences prefs;
    private LinearLayout thumbs;
    private TextView status;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = ImageStore.prefs(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(28), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("רקע עומק", 28, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = text("התמונות שלך בתלת-ממד, זזות לפי הטיית הטלפון", 14, C_MUTED);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        // ---- images ----
        LinearLayout imgCard = card(root, "התמונות שלי");
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        thumbs = new LinearLayout(this);
        thumbs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(thumbs);
        imgCard.addView(hs);
        status = text("", 13, C_MUTED);
        status.setPadding(0, dp(8), 0, dp(8));
        imgCard.addView(status);
        Button add = button("הוסף תמונות מהגלריה", false);
        add.setOnClickListener(v -> pickImages());
        imgCard.addView(add);
        TextView hint = text("הקשה על תמונה מציגה אותה עכשיו, לחיצה ארוכה מוחקת", 12, C_MUTED);
        hint.setPadding(0, dp(8), 0, 0);
        imgCard.addView(hint);

        // ---- effect ----
        LinearLayout fx = card(root, "אפקט");
        TextView strLabel = text("", 14, C_TEXT);
        fx.addView(strLabel);
        SeekBar str = new SeekBar(this);
        str.setMax(100);
        str.setProgress(prefs.getInt(ImageStore.K_STRENGTH, 55));
        strLabel.setText("עוצמת התלת-ממד: " + str.getProgress() + "%");
        str.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                strLabel.setText("עוצמת התלת-ממד: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(ImageStore.K_STRENGTH, s.getProgress()).apply();
            }
        });
        str.setPadding(dp(4), dp(10), dp(4), dp(14));
        fx.addView(str);
        fx.addView(toggle("עומק אמיתי — קרוב זז יותר מרחוק",
                ImageStore.K_DEPTH, true));
        fx.addView(toggle("תזוזה בהחלקה בין מסכי הבית", ImageStore.K_SCROLL, true));

        // ---- rotation ----
        LinearLayout rot = card(root, "החלפת תמונה");
        RadioGroup rg = new RadioGroup(this);
        String[] opts = {"ידני — הקשה כפולה על המסך", "בכל פתיחת נעילה", "כל שעה", "פעם ביום"};
        int cur = prefs.getInt(ImageStore.K_INTERVAL, 1);
        for (int i = 0; i < opts.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(opts[i]);
            rb.setTextColor(C_TEXT);
            rb.setTextSize(15);
            rb.setId(100 + i);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(C_ACCENT));
            rb.setPadding(dp(6), dp(8), dp(6), dp(8));
            rg.addView(rb);
        }
        rg.check(100 + cur);
        rg.setOnCheckedChangeListener((g, id) ->
                prefs.edit().putInt(ImageStore.K_INTERVAL, id - 100).apply());
        rot.addView(rg);

        // ---- battery ----
        LinearLayout bat = card(root, "סוללה");
        bat.addView(toggle("כבה תנועה כשמצב חיסכון בסוללה פעיל", ImageStore.K_SAVER, true));
        TextView batInfo = text("הרקע מצייר רק כשהטלפון זז, וכשהמסך כבוי או שאפליקציה פתוחה מעליו — הכל נעצר.", 12, C_MUTED);
        batInfo.setPadding(0, dp(6), 0, 0);
        bat.addView(batInfo);

        Button set = button("הגדר כרקע", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(10);
        set.setLayoutParams(lp);
        set.setOnClickListener(v -> applyWallpaper());
        root.addView(set);

        setContentView(scroll);
        refreshThumbs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------- actions ----------

    private void pickImages() {
        Intent i;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(g, REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        status.setText("מעבד " + uris.size() + " תמונות…");
        io.execute(() -> {
            int ok = 0;
            for (int i = 0; i < uris.size(); i++) {
                final int n = i + 1;
                main.post(() -> status.setText("מעבד תמונה " + n + " מתוך " + uris.size() + "…"));
                try { ImageStore.importImage(this, uris.get(i)); ok++; }
                catch (Throwable t) { /* skip unreadable file */ }
            }
            final int done = ok;
            main.post(() -> {
                status.setText(done == uris.size() ? "" : "חלק מהתמונות לא נטענו");
                refreshThumbs();
            });
        });
    }

    private void applyWallpaper() {
        if (ImageStore.list(this).isEmpty()) {
            Toast.makeText(this, "קודם הוסף לפחות תמונה אחת", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, DepthWallpaperService.class));
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
        }
    }

    private void refreshThumbs() {
        thumbs.removeAllViews();
        List<File> files = ImageStore.list(this);
        if (files.isEmpty()) {
            status.setText("עוד לא נבחרו תמונות");
            return;
        }
        int sel = prefs.getInt(ImageStore.K_INDEX, 0) % files.size();
        for (int i = 0; i < files.size(); i++) {
            final File f = files.get(i);
            final int idx = i;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), dp(140));
            lp.setMarginEnd(dp(10));
            iv.setLayoutParams(lp);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(dp(10));
            border.setColor(0xFF222833);
            if (idx == sel) border.setStroke(dp(3), C_ACCENT);
            iv.setBackground(border);
            iv.setPadding(dp(3), dp(3), dp(3), dp(3));
            iv.setClipToOutline(true);
            iv.setOnClickListener(v -> {
                prefs.edit().putInt(ImageStore.K_INDEX, idx)
                        .putLong(ImageStore.K_LAST, System.currentTimeMillis()).apply();
                refreshThumbs();
            });
            iv.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("למחוק את התמונה מהרקע?")
                        .setPositiveButton("מחק", (d, w) -> {
                            ImageStore.remove(this, f);
                            refreshThumbs();
                        })
                        .setNegativeButton("ביטול", null)
                        .show();
                return true;
            });
            thumbs.addView(iv);
            io.execute(() -> {
                Bitmap t = ImageStore.loadThumb(f, dp(140));
                main.post(() -> iv.setImageBitmap(t));
            });
        }
    }

    // ---------- tiny UI kit ----------

    private LinearLayout card(LinearLayout parent, String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        TextView h = text(heading, 16, C_TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(10));
        c.addView(h);
        parent.addView(c);
        return c;
    }

    private Switch toggle(String label, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(C_TEXT);
        s.setTextSize(15);
        s.setChecked(prefs.getBoolean(key, def));
        s.setPadding(0, dp(8), 0, dp(8));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(C_ACCENT));
        s.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        return s;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? C_BG : C_ACCENT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (primary) bg.setColor(C_ACCENT);
        else { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(2), C_ACCENT); }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
