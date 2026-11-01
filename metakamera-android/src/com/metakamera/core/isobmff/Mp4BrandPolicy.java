package com.metakamera.core.isobmff;

import com.metakamera.core.EditSpec;

import java.util.Locale;

/**
 * Video brand policy: decides, for every QuickTime Keys entry (and the Samsung user-data atoms),
 * whether it is kept, removed or rewritten for a given {@link EditSpec}.
 *
 * <p>Background (exiftool docs and real files): iPhones write moov/meta 'mdta' keys
 * com.apple.quicktime.make / model / software and a TRACK-level key
 * com.apple.quicktime.camera.lens_model. AOSP writes com.android.version and some OEMs
 * com.android.manufacturer / com.android.model; vendors add keys such as com.oplus.product.model,
 * com.honor.lens.model, com.xiaomi.*, com.huawei.*. Huawei / OpenHarmony moving-photo keys are
 * functional (the gallery needs them) and are never touched.
 *
 * <p>"Target is Apple" = {@code spec.make} is non-null and equals "Apple" (ignoring case, trimmed).
 *
 * <pre>
 * com.apple.quicktime.make/model/software  spec value (null keep, "" remove, else set; created in moov/meta)
 * com.android.manufacturer / .model        Apple target: removeMakerNote ? remove : set
 *                                          other target: spec value; created when spec.make is set
 * com.android.version                      removed only for an Apple target with removeMakerNote
 * vendor make/model keys (com.oplus. ...)  removeMakerNote ? remove : spec value (never created)
 * vendor lens keys (com.honor.lens.model)  removeMakerNote or lensModel "" ? remove : keep
 * other *.make / *.manufacturer / *.model  spec value (never created; lens keys are excluded)
 * com.apple.quicktime.camera.lens_model    spec.lensModel; null + removeMakerNote + non-Apple target: remove;
 *                                          created in the first video track only for an Apple target
 * Samsung udta/smta/'mdln'                 removeMakerNote ? remove : spec.model
 * 3GPP udta/'auth' naming the old device   removeMakerNote ? remove : spec.model
 * Keys containing movingphoto / com.openharmony.*: never touched
 * </pre>
 */
final class Mp4BrandPolicy {

    static final String KEY_APPLE_MAKE = "com.apple.quicktime.make";
    static final String KEY_APPLE_MODEL = "com.apple.quicktime.model";
    static final String KEY_APPLE_SOFTWARE = "com.apple.quicktime.software";
    static final String KEY_APPLE_LENS = "com.apple.quicktime.camera.lens_model";
    static final String KEY_ANDROID_MAKE = "com.android.manufacturer";
    static final String KEY_ANDROID_MODEL = "com.android.model";
    static final String KEY_ANDROID_VERSION = "com.android.version";

    /** Keys owned by phone vendors (brand traces). */
    private static final String[] VENDOR_PREFIXES = {
            "com.oplus.", "com.honor.", "com.hihonor.", "com.xiaomi.", "com.miui.", "com.samsung.", "com.vivo.",
            "com.oneplus.", "com.oppo.", "com.coloros.", "com.realme.", "com.motorola.", "com.asus.",
            "com.sony.", "com.nothing.", "com.huawei.", "com.zte.", "com.nubia.", "com.meizu.", "com.lenovo.",
            "com.transsion.", "com.tecno.", "com.infinix.", "com.lge.", "com.hmd.",
    };

    // key kinds
    static final int OTHER = 0;
    static final int STD_MAKE = 1;
    static final int STD_MODEL = 2;
    static final int STD_SOFTWARE = 3;
    static final int ANDROID_MAKE = 4;
    static final int ANDROID_MODEL = 5;
    static final int ANDROID_VERSION = 6;
    static final int VENDOR_MAKE = 7;
    static final int VENDOR_MODEL = 8;
    static final int VENDOR_LENS = 9;
    static final int GENERIC_MAKE = 10;
    static final int GENERIC_MODEL = 11;
    static final int APPLE_LENS = 12;
    static final int PROTECTED = 13;

    final String make;
    final String model;
    final String software;
    final String lensModel;
    /** The user's "remove original-brand traces" option ({@link EditSpec#removeMakerNote}). */
    final boolean removeTraces;
    final boolean appleTarget;

    Mp4BrandPolicy(EditSpec spec) {
        make = spec.make;
        model = spec.model;
        software = spec.software;
        lensModel = spec.lensModel;
        removeTraces = spec.removeMakerNote;
        appleTarget = isAppleTarget(spec);
    }

    static boolean isAppleTarget(EditSpec spec) {
        return spec.make != null && "apple".equals(spec.make.trim().toLowerCase(Locale.US));
    }

    // ------------------------------------------------------------------ classification

    /** Kind of a Keys entry by its name (case-insensitive). */
    static int classify(String name) {
        if (name == null) return OTHER;
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("com.openharmony.") || n.contains("movingphoto") || n.contains("moving_photo")
                || n.contains("motionphoto") || n.contains("motion_photo")) {
            return PROTECTED;
        }
        if (n.equals(KEY_APPLE_MAKE)) return STD_MAKE;
        if (n.equals(KEY_APPLE_MODEL)) return STD_MODEL;
        if (n.equals(KEY_APPLE_SOFTWARE)) return STD_SOFTWARE;
        if (n.equals(KEY_APPLE_LENS)) return APPLE_LENS;
        if (n.equals(KEY_ANDROID_MAKE)) return ANDROID_MAKE;
        if (n.equals(KEY_ANDROID_MODEL)) return ANDROID_MODEL;
        if (n.equals(KEY_ANDROID_VERSION)) return ANDROID_VERSION;
        if (isVendor(n)) {
            // lens first: "com.honor.lens.model" is a lens, never the phone model
            if (n.contains("lens")) return VENDOR_LENS;
            if (n.contains("manufacturer") || n.contains(".make")) return VENDOR_MAKE;
            if (n.contains("model")) return VENDOR_MODEL;
            return OTHER;
        }
        if (n.contains("lens")) return OTHER;
        if (n.endsWith(".make") || n.endsWith(".manufacturer")) return GENERIC_MAKE;
        if (n.endsWith(".model")) return GENERIC_MODEL;
        return OTHER;
    }

    private static boolean isVendor(String lowerName) {
        for (String p : VENDOR_PREFIXES) {
            if (lowerName.startsWith(p)) return true;
        }
        return false;
    }

    /** True for kinds that carry a make (brand) value. */
    static boolean isMakeKind(int kind) {
        return kind == STD_MAKE || kind == ANDROID_MAKE || kind == VENDOR_MAKE || kind == GENERIC_MAKE;
    }

    /** True for kinds that carry a phone model value. */
    static boolean isModelKind(int kind) {
        return kind == STD_MODEL || kind == ANDROID_MODEL || kind == VENDOR_MODEL || kind == GENERIC_MODEL;
    }

    /** Report label of the field a kind belongs to. */
    static String field(int kind) {
        if (isMakeKind(kind)) return "Make";
        if (isModelKind(kind)) return "Model";
        if (kind == STD_SOFTWARE) return "Software";
        if (kind == APPLE_LENS || kind == VENDOR_LENS) return "LensModel";
        return "Anahtar";
    }

    // ------------------------------------------------------------------ decisions

    /**
     * What to do with an existing key of this kind: null = keep, "" = remove, else the new value.
     */
    String target(int kind) {
        switch (kind) {
            case STD_MAKE:
            case GENERIC_MAKE:
                return make;
            case STD_MODEL:
            case GENERIC_MODEL:
                return model;
            case STD_SOFTWARE:
                return software;
            case ANDROID_MAKE:
                if (appleTarget) return removeTraces ? "" : make;
                return make;
            case ANDROID_MODEL:
                if (appleTarget) return removeTraces ? "" : model;
                return model;
            case ANDROID_VERSION:
                return appleTarget && removeTraces ? "" : null;
            case VENDOR_MAKE:
                return removeTraces ? "" : make;
            case VENDOR_MODEL:
                return removeTraces ? "" : model;
            case VENDOR_LENS:
                return removeTraces || "".equals(lensModel) ? "" : null;
            case APPLE_LENS:
                if (lensModel != null) return lensModel;
                return removeTraces && !appleTarget ? "" : null;
            default:
                return null;
        }
    }

    /**
     * True when a removal of this kind comes from the "remove original-brand traces" option rather
     * than from an explicit "" value (only changes the report wording).
     */
    boolean isTraceRemoval(int kind) {
        switch (kind) {
            case ANDROID_MAKE:
            case ANDROID_MODEL:
                return appleTarget && removeTraces;
            case ANDROID_VERSION:
                return true;
            case VENDOR_MAKE:
                return removeTraces && !"".equals(make);
            case VENDOR_MODEL:
                return removeTraces && !"".equals(model);
            case VENDOR_LENS:
                return removeTraces && !"".equals(lensModel);
            case APPLE_LENS:
                return lensModel == null;
            default:
                return false;
        }
    }

    /** Value to create in the primary key table (moov/meta) when no key of this kind exists, or null. */
    String create(int kind) {
        switch (kind) {
            case STD_MAKE:
                return nonEmpty(make);
            case STD_MODEL:
                return nonEmpty(model);
            case STD_SOFTWARE:
                return nonEmpty(software);
            case ANDROID_MAKE:
                // Android galleries (e.g. Huawei Gallery) read com.android.*: show the non-Apple target there
                return !appleTarget && nonEmpty(make) != null ? make : null;
            case ANDROID_MODEL:
                return !appleTarget && nonEmpty(make) != null ? nonEmpty(model) : null;
            default:
                return null;
        }
    }

    /** Lens model to create at track level (first video track) when no lens key exists, or null. */
    String createLens() {
        return appleTarget ? nonEmpty(lensModel) : null;
    }

    /** Key name created for a kind. */
    static String keyName(int kind) {
        switch (kind) {
            case STD_MAKE:
                return KEY_APPLE_MAKE;
            case STD_MODEL:
                return KEY_APPLE_MODEL;
            case STD_SOFTWARE:
                return KEY_APPLE_SOFTWARE;
            case ANDROID_MAKE:
                return KEY_ANDROID_MAKE;
            case ANDROID_MODEL:
                return KEY_ANDROID_MODEL;
            case APPLE_LENS:
                return KEY_APPLE_LENS;
            default:
                return null;
        }
    }

    /**
     * Samsung udta/smta/'mdln' (model code): null = keep, "" = remove, else the new value.
     */
    String samsungModelTarget() {
        return removeTraces ? "" : model;
    }

    /** 3GPP udta 'auth' whose text names the original device: null = keep, "" = remove, else new text. */
    String authorTarget() {
        return removeTraces ? "" : model;
    }

    static String nonEmpty(String s) {
        return s == null || s.length() == 0 ? null : s;
    }
}
