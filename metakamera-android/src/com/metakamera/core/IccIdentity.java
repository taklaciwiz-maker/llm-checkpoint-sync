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
        return c;
    }
}
