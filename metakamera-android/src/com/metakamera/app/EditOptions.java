package com.metakamera.app;

/** User options of the "Seçenekler" card plus the "Gelişmiş" field overrides. Main-thread owned. */
public final class EditOptions {

    public static final int F_MAKE = 0;
    public static final int F_MODEL = 1;
    public static final int F_SOFTWARE = 2;
    public static final int F_LENS_MAKE = 3;
    public static final int F_LENS_MODEL = 4;
    public static final int F_HOST = 5;
    public static final int F_ICC_MFR = 6;
    public static final int F_ICC_MODEL = 7;
    public static final int FIELD_COUNT = 8;

    /** Rewrite the device identity inside embedded ICC profiles. */
    public boolean icc = true;
    /** Set LensMake/LensModel from the target device. */
    public boolean lens = true;
    /** Set Software from the target device. */
    public boolean software = true;
    /** Remove MakerNote and serial numbers that reveal the original brand. */
    public boolean traces = true;
    /** Update device properties inside XMP. */
    public boolean xmp = true;
    /** Add ICC dmnd/dmdd tags when the profile has none. */
    public boolean descTags = false;

    /**
     * User edits of the advanced fields, indexed by F_*: null = not edited (use the device value),
     * otherwise the typed text ("" = remove). Applied last, independent of the checkboxes.
     */
    public final String[] overrides = new String[FIELD_COUNT];

    public EditOptions copy() {
        EditOptions o = new EditOptions();
        o.icc = icc;
        o.lens = lens;
        o.software = software;
        o.traces = traces;
        o.xmp = xmp;
        o.descTags = descTags;
        System.arraycopy(overrides, 0, o.overrides, 0, FIELD_COUNT);
        return o;
    }

    public void clearOverrides() {
        for (int i = 0; i < FIELD_COUNT; i++) overrides[i] = null;
    }

    public boolean hasOverrides() {
        for (String s : overrides) if (s != null) return true;
        return false;
    }
}
