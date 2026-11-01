package com.metakamera.app;

/**
 * Brand-level data from the device database: the ICC header identity written for this brand.
 * Every signature follows {@link com.metakamera.core.IccIdentity}: null = keep the existing header
 * field, "" = zero it, otherwise the 4-character signature.
 */
public final class BrandInfo {

    public final String name;
    public final String iccManufacturer;
    public final String iccModel;
    public final String iccCmm;
    public final String iccPlatform;
    public final String iccCreator;

    public BrandInfo(String name, String iccManufacturer, String iccModel, String iccCmm, String iccPlatform,
                     String iccCreator) {
        this.name = name == null ? "" : name.trim();
        this.iccManufacturer = iccManufacturer;
        this.iccModel = iccModel;
        this.iccCmm = iccCmm;
        this.iccPlatform = iccPlatform;
        this.iccCreator = iccCreator;
    }
}
