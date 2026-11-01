package com.metakamera.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.TextView;

import com.metakamera.core.MediaFormat;
import com.metakamera.core.UnsupportedFormatException;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Small UI helpers (formatting, cheap view updates, edge-to-edge). */
final class UiUtil {

    private static final Locale TR = new Locale("tr", "TR");

    private UiUtil() {
    }

    static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** "3,4 MB" style sizes with Turkish decimal separator. */
    static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(TR, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    static String formatName(MediaFormat f) {
        if (f == null) return null;
        switch (f) {
            case JPEG:
                return "JPEG";
            case HEIF:
                return "HEIC";
            case AVIF:
                return "AVIF";
            case PNG:
                return "PNG";
            case WEBP:
                return "WebP";
            case MP4:
                return "MP4";
            case MOV:
                return "MOV";
            default:
                return null;
        }
    }

    /** Sets text only when it changed (avoids needless relayouts during progress updates). */
    static void setText(TextView v, CharSequence text) {
        CharSequence cur = v.getText();
        if (text == null) text = "";
        if (!TextUtils.equals(cur, text)) v.setText(text);
    }

    static void setVisible(View v, boolean visible) {
        int want = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != want) v.setVisibility(want);
    }

    static void setEnabled(View v, boolean enabled) {
        if (v.isEnabled() != enabled) v.setEnabled(enabled);
    }

    /** Turkish, user-facing description of a failure. */
    static String errorMessage(Throwable t) {
        if (t == null) return "Bilinmeyen hata";
        String m = t.getMessage();
        if (t instanceof UnsupportedFormatException || t instanceof UserException) {
            return m != null && m.length() > 0 ? m : "Desteklenmeyen veya bozuk dosya";
        }
        if (t instanceof FileNotFoundException) return "Dosya açılamadı (silinmiş olabilir ya da erişim izni yok)";
        if (t instanceof EOFException) return "Dosya beklenenden kısa; bozuk olabilir";
        if (t instanceof SecurityException) return "Dosyaya erişim izni yok";
        if (t instanceof OutOfMemoryError) return "Bellek yetersiz";
        if (t instanceof IOException) {
            if (m != null && (m.contains("ENOSPC") || m.toLowerCase(Locale.ROOT).contains("no space"))) {
                return "Yetersiz depolama alanı";
            }
            return m != null && m.length() > 0 ? "Okuma/yazma hatası: " + m : "Okuma/yazma hatası";
        }
        return "Beklenmeyen hata (" + t.getClass().getSimpleName() + ")";
    }

    // ------------------------------------------------------------------ edge-to-edge

    /**
     * Draws behind the system bars on every API level (Android 15+ enforces it anyway) so a single
     * inset-padding code path works everywhere; bar icons follow the light/dark theme.
     */
    @SuppressWarnings("deprecation")
    static void enableEdgeToEdge(Activity a) {
        Window w = a.getWindow();
        if (w == null) return;
        boolean light = !isNight(a);
        try {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                Api30.edgeToEdge(w, light);
            } else {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                w.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (RuntimeException ignored) {
            // cosmetic only
        }
    }

    /**
     * Keeps {@code column} at most {@code maxWidthPx} wide (centred) by growing its side padding when its
     * parent is wider; never less than {@code minSidePx}.
     */
    static void limitColumnWidth(final View column, final int maxWidthPx, final int minSidePx) {
        final View parent = (View) column.getParent();
        if (parent == null) return;
        parent.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left - v.getPaddingLeft() - v.getPaddingRight();
                final int side = Math.max(minSidePx, (width - maxWidthPx) / 2);
                if (column.getPaddingLeft() == side && column.getPaddingRight() == side) return;
                column.post(new Runnable() {
                    @Override
                    public void run() {
                        column.setPadding(side, column.getPaddingTop(), side, column.getPaddingBottom());
                    }
                });
            }
        });
    }

    /** Pads {@code root} by the system bars, display cutout and keyboard. */
    static void applyInsetsPadding(View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int[] p = new int[4];
                if (Build.VERSION.SDK_INT >= 30) {
                    Api30.insets(in, p);
                } else {
                    p[0] = in.getSystemWindowInsetLeft();
                    p[1] = in.getSystemWindowInsetTop();
                    p[2] = in.getSystemWindowInsetRight();
                    p[3] = in.getSystemWindowInsetBottom();
                    if (Build.VERSION.SDK_INT >= 28) Api28.maxWithCutout(in, p);
                }
                v.setPadding(p[0], p[1], p[2], p[3]);
                return in;
            }
        });
        root.requestApplyInsets();
    }

    // Classes newer than minSdk are only referenced from these holders, so older ART versions do not
    // soft-fail verification of UiUtil itself.

    /** API 28+ helpers. */
    private static final class Api28 {
        static void maxWithCutout(WindowInsets in, int[] p) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc == null) return;
            p[0] = Math.max(p[0], dc.getSafeInsetLeft());
            p[1] = Math.max(p[1], dc.getSafeInsetTop());
            p[2] = Math.max(p[2], dc.getSafeInsetRight());
            p[3] = Math.max(p[3], dc.getSafeInsetBottom());
        }
    }

    /** API 30+ helpers. */
    private static final class Api30 {
        static void edgeToEdge(Window w, boolean light) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        }

        static void insets(WindowInsets in, int[] p) {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            p[0] = i.left;
            p[1] = i.top;
            p[2] = i.right;
            p[3] = i.bottom;
        }
    }
}
