package com.metakamera.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembered choices (device, options, gallery settings). */
final class Prefs {

    static final int ALBUM_CAMERA = 0;
    static final int ALBUM_APP = 1;

    private static final String FILE = "metakamera";
    private static final String K_DEVICE = "device";
    private static final String K_VARIANT = "variant";
    private static final String K_ICC = "opt_icc";
    private static final String K_LENS = "opt_lens";
    private static final String K_SOFTWARE = "opt_software";
    private static final String K_TRACES = "opt_traces";
    private static final String K_XMP = "opt_xmp";
    private static final String K_DESC = "opt_desc_tags";
    private static final String K_ALBUM = "album";
    private static final String K_KEEP_NAME = "keep_name";
    private static final String K_LOCATION_ASKED = "media_location_asked";

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String deviceKey() {
        return sp.getString(K_DEVICE, null);
    }

    String variant() {
        return sp.getString(K_VARIANT, null);
    }

    void setDevice(String key, String variant) {
        sp.edit().putString(K_DEVICE, key).putString(K_VARIANT, variant).apply();
    }

    void loadOptions(EditOptions o) {
        o.icc = sp.getBoolean(K_ICC, true);
        o.lens = sp.getBoolean(K_LENS, true);
        o.software = sp.getBoolean(K_SOFTWARE, true);
        o.traces = sp.getBoolean(K_TRACES, true);
        o.xmp = sp.getBoolean(K_XMP, true);
        o.descTags = sp.getBoolean(K_DESC, false);
    }

    void saveOptions(EditOptions o) {
        sp.edit().putBoolean(K_ICC, o.icc).putBoolean(K_LENS, o.lens).putBoolean(K_SOFTWARE, o.software)
                .putBoolean(K_TRACES, o.traces).putBoolean(K_XMP, o.xmp).putBoolean(K_DESC, o.descTags).apply();
    }

    int album() {
        int a = sp.getInt(K_ALBUM, ALBUM_CAMERA);
        return a == ALBUM_APP ? ALBUM_APP : ALBUM_CAMERA;
    }

    void setAlbum(int album) {
        sp.edit().putInt(K_ALBUM, album).apply();
    }

    boolean keepName() {
        return sp.getBoolean(K_KEEP_NAME, true);
    }

    void setKeepName(boolean keep) {
        sp.edit().putBoolean(K_KEEP_NAME, keep).apply();
    }

    boolean mediaLocationAsked() {
        return sp.getBoolean(K_LOCATION_ASKED, false);
    }

    void setMediaLocationAsked() {
        sp.edit().putBoolean(K_LOCATION_ASKED, true).apply();
    }
}
