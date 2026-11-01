package com.metakamera.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Case- and diacritics-insensitive text matching for the device search (Turkish-safe). */
final class TextNorm {

    private TextNorm() {
    }

    /** Lower-case, accent-free form: "İPhone Pro" -> "iphone pro", "Huawéi" -> "huawei". */
    static String normalize(String s) {
        if (s == null) return "";
        // Turkish dotless/dotted i are mapped explicitly before decomposition.
        String t = s.replace('ı', 'i').replace('İ', 'I');
        t = Normalizer.normalize(t, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) {
                continue;
            }
            if (c == 'ß') {
                sb.append("ss");
                continue;
            }
            sb.append(Character.isWhitespace(c) ? ' ' : c);
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }

    /** Splits a normalized query into non-empty tokens. */
    static String[] tokens(String query) {
        String n = normalize(query);
        List<String> out = new ArrayList<String>();
        for (String p : n.split(" ")) {
            if (p.length() > 0) out.add(p);
        }
        return out.toArray(new String[out.size()]);
    }

    /** True when every token occurs in the (already normalized) haystack. */
    static boolean matchesAll(String normalizedHaystack, String[] tokens) {
        for (String t : tokens) {
            if (normalizedHaystack.indexOf(t) < 0) return false;
        }
        return true;
    }
}
