package com.metakamera.core.xmp;

import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XMP packet device-property editor.
 *
 * <p>Works on the packet text with exact splicing (no DOM round trip), so everything that is not
 * edited stays character-for-character identical. Prefixes are resolved through the xmlns
 * declarations in scope, so custom prefixes (e.g. {@code xmlns:t="http://ns.adobe.com/tiff/1.0/"})
 * work and a conventional prefix bound to a different namespace is ignored.
 */
public final class XmpEditor {

    static final String NS_TIFF = "http://ns.adobe.com/tiff/1.0/";
    static final String NS_EXIFEX = "http://cipa.jp/exif/1.0/";
    static final String NS_AUX = "http://ns.adobe.com/exif/1.0/aux/";
    static final String NS_XMP = "http://ns.adobe.com/xap/1.0/";
    static final String NS_EXIF = "http://ns.adobe.com/exif/1.0/";
    static final String NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    /** Warning added when an unexpected internal error happened (the packet is then left untouched). */
    public static final String INTERNAL_ERROR_WARNING = "XMP işlenemedi (iç hata), değiştirilmedi";

    private XmpEditor() {
    }

    /** A property to change: value "" = remove. */
    private static final class Target {
        final String uri;
        final String local;
        final String label;
        final String value;

        Target(String uri, String local, String label, String value) {
            this.uri = uri;
            this.local = local;
            this.label = label;
            this.value = value;
        }
    }

    /** A splice of the packet text. */
    private static final class Edit {
        final int start;
        final int end;
        final String replacement;
        final Target target;
        final String oldValue;

        Edit(int start, int end, String replacement, Target target, String oldValue) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
            this.target = target;
            this.oldValue = oldValue;
        }
    }

    /**
     * Updates device properties that ALREADY exist in the packet (attribute or element form):
     * tiff:Make, tiff:Model, exifEX:LensMake, exifEX:LensModel, aux:Lens, xmp:CreatorTool (software).
     * Returns the same String instance when nothing changed. Keeps the packet byte length identical
     * by adjusting trailing padding whitespace when possible.
     *
     * <p>Also (only if present): tiff:Software &lt;- software, and with {@link EditSpec#clearSerials}
     * the serial/owner identifiers exifEX:BodySerialNumber, exifEX:LensSerialNumber,
     * exifEX:CameraOwnerName, aux:SerialNumber, aux:LensSerialNumber, aux:OwnerName and
     * exif:ImageUniqueID are removed. Nothing is ever created. Respects {@link EditSpec#editXmp}.
     * A packet that is not well-formed XML is returned unchanged with a warning.
     */
    public static String edit(String xmp, EditSpec spec, EditReport report) {
        if (xmp == null || spec == null || !spec.editXmp) return xmp;
        List<Target> targets = targets(spec);
        if (targets.isEmpty()) return xmp;
        EditReport rep = report != null ? report : new EditReport();
        EditReport tmp = new EditReport();
        String result;
        try {
            result = doEdit(xmp, targets, tmp);
        } catch (RuntimeException e) {
            // Safety net: the scanner and splicer are bounds-checked, this should be unreachable.
            rep.warn(INTERNAL_ERROR_WARNING);
            return xmp;
        }
        rep.changes.addAll(tmp.changes);
        rep.warnings.addAll(tmp.warnings);
        return result;
    }

    /** Sets hasXmp; fills make/model/lens/software only where the MediaInfo field is still null. */
    public static void inspect(String xmp, MediaInfo info) {
        if (info == null || xmp == null) return;
        info.hasXmp = true;
        try {
            List<XmlScanner.Token> toks = XmlScanner.parse(xmp);
            if (info.make == null) info.make = firstValue(xmp, toks, NS_TIFF, "Make");
            if (info.model == null) info.model = firstValue(xmp, toks, NS_TIFF, "Model");
            if (info.lensMake == null) info.lensMake = firstValue(xmp, toks, NS_EXIFEX, "LensMake");
            if (info.lensModel == null) {
                String v = firstValue(xmp, toks, NS_EXIFEX, "LensModel");
                info.lensModel = v != null ? v : firstValue(xmp, toks, NS_AUX, "Lens");
            }
            if (info.software == null) {
                String v = firstValue(xmp, toks, NS_XMP, "CreatorTool");
                info.software = v != null ? v : firstValue(xmp, toks, NS_TIFF, "Software");
            }
        } catch (XmlScanner.XmlException e) {
            // not well-formed: nothing more to report
        } catch (RuntimeException e) {
            // best effort
        }
    }

    // ================================================================== editing

    private static List<Target> targets(EditSpec spec) {
        List<Target> t = new ArrayList<Target>();
        add(t, NS_TIFF, "Make", "tiff:Make", spec.make);
        add(t, NS_TIFF, "Model", "tiff:Model", spec.model);
        add(t, NS_EXIFEX, "LensMake", "exifEX:LensMake", spec.lensMake);
        add(t, NS_EXIFEX, "LensModel", "exifEX:LensModel", spec.lensModel);
        add(t, NS_AUX, "Lens", "aux:Lens", spec.lensModel);
        add(t, NS_XMP, "CreatorTool", "xmp:CreatorTool", spec.software);
        add(t, NS_TIFF, "Software", "tiff:Software", spec.software);
        if (spec.clearSerials) {
            add(t, NS_EXIFEX, "BodySerialNumber", "exifEX:BodySerialNumber", "");
            add(t, NS_EXIFEX, "LensSerialNumber", "exifEX:LensSerialNumber", "");
            add(t, NS_EXIFEX, "CameraOwnerName", "exifEX:CameraOwnerName", "");
            add(t, NS_AUX, "SerialNumber", "aux:SerialNumber", "");
            add(t, NS_AUX, "LensSerialNumber", "aux:LensSerialNumber", "");
            add(t, NS_AUX, "OwnerName", "aux:OwnerName", "");
            add(t, NS_EXIF, "ImageUniqueID", "exif:ImageUniqueID", "");
        }
        return t;
    }

    private static void add(List<Target> list, String uri, String local, String label, String value) {
        if (value == null) return;
        String clean = cleanValue(value);
        // a value made only of characters XML cannot carry must not turn into a removal
        if (clean.length() == 0 && value.length() > 0) return;
        list.add(new Target(uri, local, label, clean));
    }

    private static Target find(List<Target> targets, String uri, String local) {
        if (uri == null || local == null) return null;
        for (Target t : targets) {
            if (t.uri.equals(uri) && t.local.equals(local)) return t;
        }
        return null;
    }

    private static String doEdit(String xmp, List<Target> targets, EditReport rep) {
        List<XmlScanner.Token> toks;
        try {
            toks = XmlScanner.parse(xmp);
        } catch (XmlScanner.XmlException e) {
            rep.warn("XMP paketi bozuk (XML okunamadı), değiştirilmedi");
            return xmp;
        }
        List<Edit> edits = new ArrayList<Edit>();
        List<String> unsupported = new ArrayList<String>();
        for (int i = 0; i < toks.size(); i++) {
            XmlScanner.Token t = toks.get(i);
            if (t.type != XmlScanner.START) continue;
            for (XmlScanner.Attr a : t.attrs) {
                Target tg = find(targets, a.uri, a.local);
                if (tg == null) continue;
                String old = unescape(xmp.substring(a.valueStart, a.valueEnd));
                if (tg.value.length() == 0) {
                    edits.add(new Edit(a.wsStart, a.end, "", tg, old));
                } else if (!old.equals(tg.value)) {
                    edits.add(new Edit(a.valueStart, a.valueEnd, escape(tg.value, true), tg, old));
                }
            }
            Target tg = find(targets, t.uri, t.local);
            if (tg != null && !elementEdit(xmp, toks, i, tg, edits) && !unsupported.contains(tg.label)) {
                unsupported.add(tg.label);
            }
        }
        for (String label : unsupported) {
            rep.warn("XMP " + label + " yapısı desteklenmiyor, değiştirilmedi");
        }
        if (edits.isEmpty()) return xmp;

        // Apply non-overlapping splices (an overlap can only come from malformed nesting: first one wins).
        Collections.sort(edits, new Comparator<Edit>() {
            @Override
            public int compare(Edit a, Edit b) {
                return a.start < b.start ? -1 : (a.start == b.start ? 0 : 1);
            }
        });
        List<Edit> kept = new ArrayList<Edit>(edits.size());
        int lastEnd = -1;
        for (Edit e : edits) {
            if (e.start < lastEnd) continue;
            kept.add(e);
            lastEnd = e.end;
        }
        StringBuilder sb = new StringBuilder(xmp.length() + 64);
        int pos = 0;
        for (Edit e : kept) {
            sb.append(xmp, pos, e.start).append(e.replacement);
            pos = e.end;
        }
        sb.append(xmp, pos, xmp.length());
        String result = keepLength(xmp, sb.toString());
        if (result.equals(xmp)) return xmp;

        Map<String, Edit> firstPerLabel = new LinkedHashMap<String, Edit>();
        for (Edit e : kept) {
            if (!firstPerLabel.containsKey(e.target.label)) firstPerLabel.put(e.target.label, e);
        }
        for (Edit e : firstPerLabel.values()) {
            if (e.target.value.length() == 0) {
                rep.change("XMP " + e.target.label + " kaldırıldı");
            } else {
                String old = e.oldValue == null || e.oldValue.length() == 0 ? "(boş)" : e.oldValue;
                rep.change("XMP " + e.target.label + ": " + old + " → " + e.target.value);
            }
        }
        return result;
    }

    /**
     * Adds the edits for property element {@code toks[i]}. Returns false when its structure is not
     * supported (nothing is changed then).
     */
    private static boolean elementEdit(String xmp, List<XmlScanner.Token> toks, int i, Target tg, List<Edit> edits) {
        XmlScanner.Token t = toks.get(i);
        int elemEnd = toks.get(t.match).end;
        if (tg.value.length() == 0) {
            int start = t.start;
            if (i > 0) {
                XmlScanner.Token prev = toks.get(i - 1);
                if (prev.type == XmlScanner.TEXT && prev.end == t.start && isAllWs(xmp, prev.start, prev.end)) {
                    start = prev.start;
                }
            }
            edits.add(new Edit(start, elemEnd, "", tg, elementValue(xmp, toks, i)));
            return true;
        }
        if (hasAttr(t, NS_RDF, "resource") || hasAttr(t, NS_RDF, "parseType")) return false;
        if (t.selfClosing) {
            edits.add(fillEmpty(xmp, t, tg, ""));
            return true;
        }
        if (isSimple(toks, i)) {
            String old = textContent(xmp, toks, i + 1, t.match);
            if (!old.equals(tg.value)) {
                edits.add(new Edit(t.end, toks.get(t.match).start, escape(tg.value, false), tg, old));
            }
            return true;
        }
        int container = containerOf(xmp, toks, i);
        if (container < 0) return false;
        List<Integer> items = listItems(xmp, toks, container);
        if (items == null || items.isEmpty()) return false;
        String old = elementValue(xmp, toks, i);
        for (int li : items) {
            XmlScanner.Token l = toks.get(li);
            if (l.selfClosing) {
                edits.add(fillEmpty(xmp, l, tg, old));
                continue;
            }
            String cur = textContent(xmp, toks, li + 1, l.match);
            if (!cur.equals(tg.value)) {
                edits.add(new Edit(l.end, toks.get(l.match).start, escape(tg.value, false), tg, old));
            }
        }
        return true;
    }

    /** Turns {@code <p:Name attrs/>} into {@code <p:Name attrs>value</p:Name>}. */
    private static Edit fillEmpty(String xmp, XmlScanner.Token t, Target tg, String old) {
        int k = t.end - 2;  // position of "/>"
        while (k > t.start && XmlScanner.isWs(xmp.charAt(k - 1))) k--;
        String repl = xmp.substring(t.start, k) + ">" + escape(tg.value, false) + "</" + t.qname + ">";
        return new Edit(t.start, t.end, repl, tg, old);
    }

    // ================================================================== reading values

    /** Value of the property element at {@code i}: simple text, or x-default/first rdf:li; null if complex. */
    private static String elementValue(String xmp, List<XmlScanner.Token> toks, int i) {
        XmlScanner.Token t = toks.get(i);
        if (hasAttr(t, NS_RDF, "resource") || hasAttr(t, NS_RDF, "parseType")) return null;
        if (t.selfClosing) return "";
        if (isSimple(toks, i)) return textContent(xmp, toks, i + 1, t.match);
        int container = containerOf(xmp, toks, i);
        if (container < 0) return null;
        List<Integer> items = listItems(xmp, toks, container);
        if (items == null || items.isEmpty()) return null;
        int best = items.get(0);
        for (int li : items) {
            String lang = attrValue(xmp, toks.get(li), XmlScanner.NS_XML, "lang");
            if (lang != null && lang.equalsIgnoreCase("x-default")) {
                best = li;
                break;
            }
        }
        XmlScanner.Token l = toks.get(best);
        return l.selfClosing ? "" : textContent(xmp, toks, best + 1, l.match);
    }

    private static String firstValue(String xmp, List<XmlScanner.Token> toks, String uri, String local) {
        for (int i = 0; i < toks.size(); i++) {
            XmlScanner.Token t = toks.get(i);
            if (t.type != XmlScanner.START) continue;
            for (XmlScanner.Attr a : t.attrs) {
                if (uri.equals(a.uri) && local.equals(a.local)) {
                    String v = unescape(xmp.substring(a.valueStart, a.valueEnd));
                    if (v.length() > 0) return v;
                }
            }
            if (uri.equals(t.uri) && local.equals(t.local)) {
                String v = elementValue(xmp, toks, i);
                if (v != null && v.length() > 0) return v;
            }
        }
        return null;
    }

    /** True when the element at {@code i} has no child elements (text / CDATA / comments only). */
    private static boolean isSimple(List<XmlScanner.Token> toks, int i) {
        XmlScanner.Token t = toks.get(i);
        if (t.selfClosing) return true;
        for (int j = i + 1; j < t.match; j++) {
            if (toks.get(j).type == XmlScanner.START) return false;
        }
        return true;
    }

    /** Index of the single rdf:Alt/Seq/Bag child of element {@code i} (whitespace/comments around it), or -1. */
    private static int containerOf(String xmp, List<XmlScanner.Token> toks, int i) {
        XmlScanner.Token t = toks.get(i);
        int container = -1;
        for (int j = i + 1; j < t.match; j++) {
            XmlScanner.Token c = toks.get(j);
            if (c.parent != i) continue;
            if (c.type == XmlScanner.START) {
                boolean isContainer = NS_RDF.equals(c.uri)
                        && ("Alt".equals(c.local) || "Seq".equals(c.local) || "Bag".equals(c.local));
                if (!isContainer || container >= 0) return -1;
                container = j;
            } else if (c.type == XmlScanner.TEXT) {
                if (!isAllWs(xmp, c.start, c.end)) return -1;
            } else if (c.type == XmlScanner.CDATA) {
                return -1;
            }
        }
        return container;
    }

    /** rdf:li children of a container with simple content, or null when the structure is unexpected. */
    private static List<Integer> listItems(String xmp, List<XmlScanner.Token> toks, int container) {
        XmlScanner.Token c = toks.get(container);
        List<Integer> items = new ArrayList<Integer>();
        if (c.selfClosing) return items;
        for (int j = container + 1; j < c.match; j++) {
            XmlScanner.Token l = toks.get(j);
            if (l.parent != container) continue;
            if (l.type == XmlScanner.START) {
                if (!NS_RDF.equals(l.uri) || !"li".equals(l.local)) return null;
                if (hasAttr(l, NS_RDF, "resource") || hasAttr(l, NS_RDF, "parseType")) return null;
                if (!isSimple(toks, j)) return null;
                items.add(j);
            } else if (l.type == XmlScanner.TEXT) {
                if (!isAllWs(xmp, l.start, l.end)) return null;
            } else if (l.type == XmlScanner.CDATA) {
                return null;
            }
        }
        return items;
    }

    /** Unescaped text + CDATA content of tokens in (from .. to), comments/PIs ignored. */
    private static String textContent(String xmp, List<XmlScanner.Token> toks, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int j = from; j < to; j++) {
            XmlScanner.Token c = toks.get(j);
            if (c.type == XmlScanner.TEXT) {
                sb.append(unescape(xmp.substring(c.start, c.end)));
            } else if (c.type == XmlScanner.CDATA) {
                sb.append(xmp, c.start + 9, c.end - 3);
            }
        }
        return sb.toString();
    }

    private static boolean hasAttr(XmlScanner.Token t, String uri, String local) {
        if (t.attrs == null) return false;
        for (XmlScanner.Attr a : t.attrs) {
            if (uri.equals(a.uri) && local.equals(a.local)) return true;
        }
        return false;
    }

    private static String attrValue(String xmp, XmlScanner.Token t, String uri, String local) {
        if (t.attrs == null) return null;
        for (XmlScanner.Attr a : t.attrs) {
            if (uri.equals(a.uri) && local.equals(a.local)) return unescape(xmp.substring(a.valueStart, a.valueEnd));
        }
        return null;
    }

    private static boolean isAllWs(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!XmlScanner.isWs(s.charAt(i))) return false;
        }
        return true;
    }

    // ================================================================== packet length

    /**
     * Re-balances the whitespace padding before {@code <?xpacket end=...?>} so that the UTF-8 length of
     * {@code edited} equals that of {@code original}, when the padding allows it.
     */
    static String keepLength(String original, String edited) {
        long delta = utf8Length(edited) - utf8Length(original);
        if (delta == 0) return edited;
        int pe = xpacketEnd(edited);
        if (pe < 0) return edited;
        int ps = pe;
        while (ps > 0 && XmlScanner.isWs(edited.charAt(ps - 1))) ps--;
        if (delta > 0) {
            if (pe - ps < delta) return edited;  // not enough padding: the packet grows
            StringBuilder pad = new StringBuilder(edited.substring(ps, pe));
            long remove = delta;
            for (int k = pad.length() - 1; k >= 0 && remove > 0; k--) {
                if (pad.charAt(k) == ' ') {
                    pad.deleteCharAt(k);
                    remove--;
                }
            }
            for (int k = pad.length() - 1; k >= 0 && remove > 0; k--) {
                pad.deleteCharAt(k);
                remove--;
            }
            return edited.substring(0, ps) + pad + edited.substring(pe);
        }
        int insertAt = pe;
        int nl = edited.lastIndexOf('\n', pe - 1);
        if (nl >= ps) insertAt = nl;
        StringBuilder sb = new StringBuilder(edited.length() + (int) -delta);
        sb.append(edited, 0, insertAt);
        for (long k = 0; k < -delta; k++) sb.append(' ');
        sb.append(edited, insertAt, edited.length());
        return sb.toString();
    }

    /** Index of the trailing {@code <?xpacket end=...?>} processing instruction, or -1. */
    private static int xpacketEnd(String s) {
        int pe = s.lastIndexOf("<?xpacket");
        if (pe <= 0) return -1;
        int k = pe + 9;
        while (k < s.length() && XmlScanner.isWs(s.charAt(k))) k++;
        if (k == pe + 9 || !s.startsWith("end", k)) return -1;
        return s.indexOf("?>", k) < 0 ? -1 : pe;
    }

    /** Length of {@code s} encoded as UTF-8 (unpaired surrogates count as the 1-byte '?'). */
    static long utf8Length(String s) {
        long n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                n += 1;
            } else {
                n += 3;
            }
        }
        return n;
    }

    // ================================================================== escaping

    /** Drops characters that XML 1.0 does not allow (C0 controls except TAB/LF/CR, U+FFFE/F, lone surrogates). */
    static String cleanValue(String v) {
        StringBuilder sb = null;
        int len = v.length();
        for (int i = 0; i < len; i++) {
            char c = v.charAt(i);
            boolean ok;
            if (Character.isHighSurrogate(c)) {
                ok = i + 1 < len && Character.isLowSurrogate(v.charAt(i + 1));
                if (ok) {
                    if (sb != null) sb.append(c).append(v.charAt(i + 1));
                    i++;
                    continue;
                }
            } else if (Character.isLowSurrogate(c)) {
                ok = false;
            } else {
                ok = c >= 0x20 ? c != 0xFFFE && c != 0xFFFF : c == '\t' || c == '\n' || c == '\r';
            }
            if (!ok) {
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(v, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? v : sb.toString();
    }

    /** Escapes &amp; &lt; &gt; &quot; &apos; (and TAB/LF/CR in attributes, CR in text). */
    static String escape(String v, boolean attribute) {
        StringBuilder sb = new StringBuilder(v.length() + 16);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                case '\r':
                    sb.append("&#13;");
                    break;
                case '\n':
                    if (attribute) sb.append("&#10;");
                    else sb.append(c);
                    break;
                case '\t':
                    if (attribute) sb.append("&#9;");
                    else sb.append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Resolves the predefined entities and numeric character references; unknown ones stay literal. */
    static String unescape(String v) {
        int amp = v.indexOf('&');
        if (amp < 0) return v;
        StringBuilder sb = new StringBuilder(v.length());
        int i = 0;
        while (amp >= 0) {
            sb.append(v, i, amp);
            int semi = v.indexOf(';', amp + 1);
            String rep = null;
            if (semi > amp + 1 && semi - amp <= 12) {
                String ent = v.substring(amp + 1, semi);
                if (ent.equals("amp")) rep = "&";
                else if (ent.equals("lt")) rep = "<";
                else if (ent.equals("gt")) rep = ">";
                else if (ent.equals("quot")) rep = "\"";
                else if (ent.equals("apos")) rep = "'";
                else if (ent.charAt(0) == '#') rep = charRef(ent);
            }
            if (rep != null) {
                sb.append(rep);
                i = semi + 1;
            } else {
                sb.append('&');
                i = amp + 1;
            }
            amp = v.indexOf('&', i);
        }
        sb.append(v, i, v.length());
        return sb.toString();
    }

    private static String charRef(String ent) {
        int cp = 0;
        boolean hex = ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X');
        int from = hex ? 2 : 1;
        if (from >= ent.length()) return null;
        for (int k = from; k < ent.length(); k++) {
            int d = Character.digit(ent.charAt(k), hex ? 16 : 10);
            if (d < 0) return null;
            cp = cp * (hex ? 16 : 10) + d;
            if (cp > 0x10FFFF) return null;
        }
        if (cp == 0 || (cp >= 0xD800 && cp <= 0xDFFF)) return null;
        return new String(Character.toChars(cp));
    }
}
