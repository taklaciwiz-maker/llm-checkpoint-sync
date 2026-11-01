package com.metakamera.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The phone database (assets/devices.json):
 * <pre>
 * { "version": 1,
 *   "brands":  [ {"name": "Apple", "icc": {"manufacturer": "APPL", "model": "", "cmm": "appl",
 *                                          "platform": "APPL", "creator": "appl"}}, ... ],
 *   "devices": [ {"brand": "Apple", "name": "iPhone 18 Pro Max", "year": 2026, "make": "Apple",
 *                 "model": "iPhone 18 Pro Max", "variants": [], "software": "26.0", "lensMake": "Apple",
 *                 "lensModel": "...", "hostComputer": "iPhone 18 Pro Max"}, ... ] }
 * </pre>
 * Invalid entries are skipped instead of failing the whole database.
 */
public final class DeviceDatabase {

    static final String ASSET = "devices.json";
    private static final int MAX_ASSET_BYTES = 16 * 1024 * 1024;

    public final int version;
    private final List<Device> devices;
    private final Map<String, BrandInfo> brands;
    private final List<String> brandNames;
    private final Map<String, Device> byKey;

    private DeviceDatabase(int version, List<Device> devices, Map<String, BrandInfo> brands) {
        this.version = version;
        this.devices = Collections.unmodifiableList(devices);
        this.brands = brands;
        this.byKey = new HashMap<String, Device>();
        Set<String> seen = new HashSet<String>();
        List<String> names = new ArrayList<String>();
        for (Device d : devices) {
            byKey.put(d.key, d);
            String k = d.brand.toLowerCase(Locale.ROOT);
            if (seen.add(k)) names.add(d.brand);
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        this.brandNames = Collections.unmodifiableList(names);
    }

    /** Reads and parses the bundled database. Call off the main thread. */
    public static DeviceDatabase load(Context context) throws IOException, JSONException {
        InputStream in = context.getAssets().open(ASSET);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_ASSET_BYTES) throw new IOException("devices.json çok büyük");
            }
            return parse(new String(bo.toByteArray(), "UTF-8"));
        } finally {
            in.close();
        }
    }

    public static DeviceDatabase parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int version = root.optInt("version", 1);

        Map<String, BrandInfo> brands = new HashMap<String, BrandInfo>();
        JSONArray ba = root.optJSONArray("brands");
        if (ba != null) {
            for (int i = 0; i < ba.length(); i++) {
                JSONObject b = ba.optJSONObject(i);
                if (b == null) continue;
                String name = str(b, "name");
                if (name == null || name.trim().length() == 0) continue;
                JSONObject icc = b.optJSONObject("icc");
                BrandInfo info = new BrandInfo(name, str(icc, "manufacturer"), str(icc, "model"), str(icc, "cmm"),
                        str(icc, "platform"), str(icc, "creator"));
                brands.put(info.name.toLowerCase(Locale.ROOT), info);
            }
        }

        List<Device> list = new ArrayList<Device>();
        Set<String> keys = new HashSet<String>();
        JSONArray da = root.optJSONArray("devices");
        if (da != null) {
            for (int i = 0; i < da.length(); i++) {
                JSONObject o = da.optJSONObject(i);
                if (o == null) continue;
                String brand = str(o, "brand");
                String name = str(o, "name");
                String make = str(o, "make");
                String model = str(o, "model");
                if (isBlank(brand) || isBlank(name) || isBlank(make) || isBlank(model)) continue;
                String[] variants = new String[0];
                JSONArray va = o.optJSONArray("variants");
                if (va != null) {
                    List<String> vs = new ArrayList<String>();
                    for (int j = 0; j < va.length(); j++) {
                        if (va.isNull(j)) continue;
                        String s = va.optString(j, null);
                        if (s != null) vs.add(s);
                    }
                    variants = vs.toArray(new String[vs.size()]);
                }
                Device d = new Device(brand, name, o.optInt("year", 0), make, model, variants,
                        str(o, "software"), str(o, "lensMake"), str(o, "lensModel"), str(o, "hostComputer"));
                if (!keys.add(d.key)) continue;
                list.add(d);
            }
        }
        Collections.sort(list, ORDER);
        return new DeviceDatabase(version, list, brands);
    }

    /** All devices, sorted by brand, then year (newest first), then name. */
    public List<Device> devices() {
        return devices;
    }

    /** Brand names present in the device list, alphabetical. */
    public List<String> brandNames() {
        return brandNames;
    }

    /** Brand block for ICC data, or null when the database has none for this brand. */
    public BrandInfo brand(String name) {
        if (name == null) return null;
        return brands.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public Device find(String key) {
        return key == null ? null : byKey.get(key);
    }

    /** Initial choice when nothing was remembered: the newest Apple phone, else the newest phone. */
    public Device defaultDevice() {
        Device best = null;
        for (Device d : devices) {
            if (!"apple".equalsIgnoreCase(d.brand)) continue;
            if (best == null || d.year > best.year) best = d;
        }
        if (best != null) return best;
        for (Device d : devices) {
            if (best == null || d.year > best.year) best = d;
        }
        return best;
    }

    /**
     * Devices matching every word of {@code query} (brand, name, make, model, variants, year;
     * case/diacritics-insensitive) and, when {@code brand} is non-null, that brand. Keeps the sort order.
     */
    public List<Device> filter(String query, String brand) {
        String[] tokens = TextNorm.tokens(query);
        List<Device> out = new ArrayList<Device>();
        for (Device d : devices) {
            if (brand != null && !brand.equalsIgnoreCase(d.brand)) continue;
            if (tokens.length > 0 && !TextNorm.matchesAll(d.searchText, tokens)) continue;
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** null when the object is null, the key is absent or JSON null; otherwise the string value. */
    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    static final Comparator<Device> ORDER = new Comparator<Device>() {
        @Override
        public int compare(Device a, Device b) {
            int c = a.brand.compareToIgnoreCase(b.brand);
            if (c != 0) return c;
            if (a.year != b.year) return a.year > b.year ? -1 : 1;
            return naturalCompare(a.name, b.name);
        }
    };

    /** Case-insensitive comparison where digit runs compare numerically ("Pixel 9" < "Pixel 10"). */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = stripZeros(a.substring(si, i));
                String nb = stripZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() < nb.length() ? -1 : 1;
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String stripZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }
}
