package com.metakamera.app;

import com.metakamera.core.EditSpec;
import com.metakamera.core.IccIdentity;

/**
 * Pure mapping: chosen device + options -> {@link EditSpec}. No Android dependencies.
 *
 * <pre>
 * make = device.make, model = selected variant or device.model, hostComputer = device.hostComputer ("" removes)
 * software = option ? device.software : null           ("" removes)
 * lensMake/lensModel = option ? device values : null   ("" removes)
 * removeMakerNote = clearSerials = brand-trace option; editXmp / editIcc = options
 * icc = brand ICC block (absent -> null = keep) + manufacturerText = make, modelText = model
 * advanced overrides are applied last; createExifIfMissing = true
 * </pre>
 */
public final class SpecBuilder {

    private SpecBuilder() {
    }

    /** The model code that will be written: the variant when it belongs to the device, else the main model. */
    public static String modelFor(Device d, String variant) {
        if (d == null) return "";
        return variant != null && d.hasModel(variant) ? variant : d.model;
    }

    /** Value shown in an advanced field before the user edits it. Never null. */
    public static String defaultValue(int field, Device d, BrandInfo b, String variant) {
        if (d == null) return "";
        switch (field) {
            case EditOptions.F_MAKE:
                return d.make;
            case EditOptions.F_MODEL:
                return modelFor(d, variant);
            case EditOptions.F_SOFTWARE:
                return d.software;
            case EditOptions.F_LENS_MAKE:
                return d.lensMake;
            case EditOptions.F_LENS_MODEL:
                return d.lensModel;
            case EditOptions.F_HOST:
                return d.hostComputer;
            case EditOptions.F_ICC_MFR:
                return b == null || b.iccManufacturer == null ? "" : b.iccManufacturer;
            case EditOptions.F_ICC_MODEL:
                return b == null || b.iccModel == null ? "" : b.iccModel;
            default:
                return "";
        }
    }

    /** True when the brand leaves this ICC field untouched (null in the database). */
    public static boolean iccFieldKeeps(int field, BrandInfo b) {
        if (field == EditOptions.F_ICC_MFR) return b == null || b.iccManufacturer == null;
        if (field == EditOptions.F_ICC_MODEL) return b == null || b.iccModel == null;
        return false;
    }

    /** Builds the spec, or returns null when no device is chosen. */
    public static EditSpec build(Device d, BrandInfo b, String variant, EditOptions o) {
        if (d == null || o == null) return null;
        EditSpec s = new EditSpec();
        s.make = d.make;
        s.model = modelFor(d, variant);
        s.hostComputer = d.hostComputer;
        s.software = o.software ? d.software : null;
        s.lensMake = o.lens ? d.lensMake : null;
        s.lensModel = o.lens ? d.lensModel : null;
        s.removeMakerNote = o.traces;
        s.clearSerials = o.traces;
        s.editXmp = o.xmp;
        s.editIcc = o.icc;
        s.createExifIfMissing = true;

        IccIdentity icc = new IccIdentity();
        if (b != null) {
            icc.manufacturerSig = b.iccManufacturer;
            icc.modelSig = b.iccModel;
            icc.cmmSig = b.iccCmm;
            icc.platformSig = b.iccPlatform;
            icc.creatorSig = b.iccCreator;
        }

        String[] ov = o.overrides;
        if (ov[EditOptions.F_MAKE] != null) s.make = ov[EditOptions.F_MAKE].trim();
        if (ov[EditOptions.F_MODEL] != null) s.model = ov[EditOptions.F_MODEL].trim();
        if (ov[EditOptions.F_SOFTWARE] != null) s.software = ov[EditOptions.F_SOFTWARE].trim();
        if (ov[EditOptions.F_LENS_MAKE] != null) s.lensMake = ov[EditOptions.F_LENS_MAKE].trim();
        if (ov[EditOptions.F_LENS_MODEL] != null) s.lensModel = ov[EditOptions.F_LENS_MODEL].trim();
        if (ov[EditOptions.F_HOST] != null) s.hostComputer = ov[EditOptions.F_HOST].trim();
        if (ov[EditOptions.F_ICC_MFR] != null) icc.manufacturerSig = signature(ov[EditOptions.F_ICC_MFR]);
        if (ov[EditOptions.F_ICC_MODEL] != null) icc.modelSig = signature(ov[EditOptions.F_ICC_MODEL]);

        icc.manufacturerText = s.make;
        icc.modelText = s.model;
        icc.addDescTagsIfMissing = o.descTags;
        s.icc = icc;
        return s;
    }

    /** Printable ASCII only, at most 4 characters, trailing spaces trimmed ("" = zero). */
    static String signature(String s) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < s.length() && sb.length() < 4; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) sb.append(c);
        }
        String r = sb.toString();
        int end = r.length();
        while (end > 0 && r.charAt(end - 1) == ' ') end--;
        return r.substring(0, end);
    }
}
