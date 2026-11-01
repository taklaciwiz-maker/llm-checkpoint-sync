package com.metakamera.app;

import java.util.ArrayList;
import java.util.List;

/** One phone of the device database (assets/devices.json). Immutable. */
public final class Device {

    public final String brand;
    public final String name;
    public final int year;
    /** EXIF Make, e.g. "Apple", "samsung". */
    public final String make;
    /** Main EXIF Model, e.g. "iPhone 18 Pro Max", "SM-S938B". */
    public final String model;
    /** Other regional EXIF model codes of the same phone (never contains {@link #model}). */
    public final String[] variants;
    /** EXIF Software for this phone; "" = remove. */
    public final String software;
    public final String lensMake;
    public final String lensModel;
    /** EXIF HostComputer ("" removes it, which matters when the source was an iPhone). */
    public final String hostComputer;

    /** Stable identifier for preferences: brand + '|' + name. */
    public final String key;
    /** Normalized text used by the picker search. */
    final String searchText;

    public Device(String brand, String name, int year, String make, String model, String[] variants,
                  String software, String lensMake, String lensModel, String hostComputer) {
        this.brand = nn(brand);
        this.name = nn(name);
        this.year = year;
        this.make = nn(make);
        this.model = nn(model);
        this.software = nn(software);
        this.lensMake = nn(lensMake);
        this.lensModel = nn(lensModel);
        this.hostComputer = nn(hostComputer);
        List<String> v = new ArrayList<String>();
        if (variants != null) {
            for (String s : variants) {
                if (s == null) continue;
                String t = s.trim();
                if (t.length() == 0 || t.equals(this.model) || v.contains(t)) continue;
                v.add(t);
            }
        }
        this.variants = v.toArray(new String[v.size()]);
        this.key = this.brand + "|" + this.name;
        StringBuilder sb = new StringBuilder();
        sb.append(this.brand).append(' ').append(this.name).append(' ').append(this.make).append(' ')
                .append(this.model).append(' ').append(year);
        for (String s : this.variants) sb.append(' ').append(s);
        this.searchText = TextNorm.normalize(sb.toString());
    }

    /** Model codes to offer: main model first, then the variants. */
    public String[] modelChoices() {
        String[] r = new String[variants.length + 1];
        r[0] = model;
        System.arraycopy(variants, 0, r, 1, variants.length);
        return r;
    }

    /** True when {@code m} is the main model or one of the variants. */
    public boolean hasModel(String m) {
        if (m == null) return false;
        if (m.equals(model)) return true;
        for (String v : variants) if (v.equals(m)) return true;
        return false;
    }

    /** "Apple iPhone 18 Pro Max" style label (brand omitted when the name already starts with it). */
    public String displayName() {
        if (name.toLowerCase(java.util.Locale.ROOT).startsWith(brand.toLowerCase(java.util.Locale.ROOT))) return name;
        return brand + " " + name;
    }

    private static String nn(String s) {
        return s == null ? "" : s.trim();
    }

    @Override
    public String toString() {
        return key;
    }
}
