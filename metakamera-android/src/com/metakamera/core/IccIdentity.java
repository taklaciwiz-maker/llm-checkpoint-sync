package com.metakamera.core;

/**
 * Device identity written into an ICC profile.
 *
 * Signature fields are 4-character ASCII codes (shorter values are right-padded with spaces,
 * longer ones truncated).  Semantics: {@code null} = keep existing header field,
 * {@code ""} = write 0x00000000, otherwise write the signature.
 */
public final class IccIdentity {

    /** Header bytes 48..51 deviceManufacturer, e.g. "APPL". */
    public String manufacturerSig;
    /** Header bytes 52..55 deviceModel (real phone profiles are usually 0 -> ""). */
    public String modelSig;
    /** Header bytes 4..7 preferred CMM type, e.g. "appl". */
    public String cmmSig;
    /** Header bytes 40..43 primary platform, e.g. "APPL" (Android brands: ""). */
    public String platformSig;
    /** Header bytes 80..83 profile creator, e.g. "appl". */
    public String creatorSig;

    /** Text for the 'dmnd' (deviceMfgDesc) tag, e.g. "Apple". null = keep. */
    public String manufacturerText;
    /** Text for the 'dmdd' (deviceModelDesc) tag, e.g. "iPhone 17 Pro Max". null = keep. */
    public String modelText;
    /** Add dmnd/dmdd tags when the profile has none (default: only replace existing ones). */
    public boolean addDescTagsIfMissing;

    /**
     * Text for the profile copyright ('cprt') tag, e.g. "Copyright Apple Inc., 2022". Phone vendors
     * name themselves here (Huawei: "Huawei Device Co., Ltd. 2020", Samsung: "Copyright (c) 2022
     * Samsung Electronics Co., Ltd.", Xiaomi: "Copyright Xiaomi Communications Co., Ltd. 2022"), even
     * when every header device field is zero. Replaces the text of an existing 'cprt' tag keeping its
     * tag type (desc/text/mluc). The tag is never created and never removed: null and "" both keep it.
     */
    public String copyrightText;
    /**
     * Profile description ('desc') for P3-gamut display profiles, e.g. "Display P3" (Apple) or
     * "DCI-P3 D65 Gamut with sRGB Transfer" (Samsung). Applied only when the current description
     * mentions "P3" (case-insensitive), e.g. Huawei/Xiaomi "sRGB EOTF with DCI-P3 Color Gamut";
     * other descriptions (sRGB IEC61966-2.1, ...) are never touched. The tag type is kept; the tag
     * is never created or removed: null and "" both keep it.
     */
    public String p3DescriptionText;

    public IccIdentity copy() {
        IccIdentity c = new IccIdentity();
        c.manufacturerSig = manufacturerSig;
        c.modelSig = modelSig;
        c.cmmSig = cmmSig;
        c.platformSig = platformSig;
        c.creatorSig = creatorSig;
        c.manufacturerText = manufacturerText;
        c.modelText = modelText;
        c.addDescTagsIfMissing = addDescTagsIfMissing;
        c.copyrightText = copyrightText;
        c.p3DescriptionText = p3DescriptionText;
        return c;
    }
}
