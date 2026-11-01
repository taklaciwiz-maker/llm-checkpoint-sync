package com.metakamera.core.icc;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.IccIdentity;
import com.metakamera.core.MediaInfo;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * ICC profile device-identity editor (ICC.1:2010 v4 and ICC v2 profiles).
 *
 * <p>Header-only edits patch the 128-byte header in place and keep every other byte. Edits of the
 * 'dmnd'/'dmdd' (device names), 'cprt' (copyright: where Huawei/Xiaomi/Samsung profiles name the
 * vendor) and 'desc' (only P3 display profile names) text tags rebuild the profile (header + tag
 * table + 4-byte aligned tag data) while
 * keeping the tag order, the bytes of every other tag and shared tag data (several entries pointing
 * at the same data still share one copy). Malformed profiles are never "repaired": they are
 * returned untouched (or header-only edited) with a Turkish warning in the report.
 */
public final class IccProfileEditor {

    static final int HEADER_SIZE = 128;
    static final int TABLE_START = 132;

    static final String SIG_DESC = "desc";
    static final String SIG_DMND = "dmnd";
    static final String SIG_DMDD = "dmdd";
    static final String SIG_CPRT = "cprt";

    /** Warning added when an unexpected internal error happened (the profile is then left untouched). */
    public static final String INTERNAL_ERROR_WARNING = "ICC profili işlenemedi (iç hata), değiştirilmedi";

    private static final int OFF_CMM = 4;
    private static final int OFF_FLAGS = 44;
    private static final int OFF_PLATFORM = 40;
    private static final int OFF_MANUFACTURER = 48;
    private static final int OFF_MODEL = 52;
    private static final int OFF_INTENT = 64;
    private static final int OFF_CREATOR = 80;
    private static final int OFF_ID = 84;

    private IccProfileEditor() {
    }

    /**
     * Returns the edited profile (never null; may be the same array if nothing changed).
     * Updates header signatures (CMM 4..7, platform 40..43, manufacturer 48..51, model 52..55,
     * creator 80..83), rewrites existing 'dmnd'/'dmdd' text tags (and adds them if
     * {@link IccIdentity#addDescTagsIfMissing}), rewrites an existing 'cprt' text
     * ({@link IccIdentity#copyrightText}) and a P3 'desc' text ({@link IccIdentity#p3DescriptionText}),
     * keeping each tag's type, fixes the profile size field, and recomputes the MD5 profile ID
     * (bytes 84..99) when it was non-zero.
     *
     * <p>Malformed profiles never throw: they are returned unchanged (or with only the header
     * patched) and a Turkish warning is added to {@code report}.
     */
    public static byte[] edit(byte[] profile, IccIdentity id, EditReport report) throws IOException {
        EditReport rep = report != null ? report : new EditReport();
        if (profile == null) {
            rep.warn("ICC profili boş, değiştirilmedi");
            return new byte[0];
        }
        if (id == null) return profile;
        EditReport tmp = new EditReport();
        byte[] result;
        try {
            result = doEdit(profile, id, tmp);
        } catch (RuntimeException e) {
            // Safety net: every access is bounds-checked, this should be unreachable.
            rep.warn(INTERNAL_ERROR_WARNING);
            return profile;
        }
        rep.changes.addAll(tmp.changes);
        rep.warnings.addAll(tmp.warnings);
        return result;
    }

    /** Fills hasIcc, iccManufacturer, iccModel, iccDescription, iccManufacturerText, iccModelText. Never throws. */
    public static void inspect(byte[] profile, MediaInfo info) {
        if (info == null || profile == null || profile.length == 0) return;
        info.hasIcc = true;
        try {
            if (profile.length < HEADER_SIZE || !isAcsp(profile)) return;
            info.iccManufacturer = sigText(profile, OFF_MANUFACTURER);
            info.iccModel = sigText(profile, OFF_MODEL);
            long declared = ByteUtils.u32be(profile, 0);
            int limit = declared >= TABLE_START && declared <= profile.length ? (int) declared : profile.length;
            if (limit < TABLE_START) return;
            long count = ByteUtils.u32be(profile, 128);
            int n = (int) Math.min(count, (limit - TABLE_START) / 12);
            String desc = null;
            String dmnd = null;
            String dmdd = null;
            for (int i = 0; i < n; i++) {
                int e = TABLE_START + 12 * i;
                String sig = ByteUtils.fourcc(profile, e);
                boolean isDesc = SIG_DESC.equals(sig) && desc == null;
                boolean isDmnd = SIG_DMND.equals(sig) && dmnd == null;
                boolean isDmdd = SIG_DMDD.equals(sig) && dmdd == null;
                if (!isDesc && !isDmnd && !isDmdd) continue;
                long off = ByteUtils.u32be(profile, e + 4);
                long sz = ByteUtils.u32be(profile, e + 8);
                if (off > limit || sz > limit - off) continue;
                String text = IccText.decode(profile, (int) off, (int) sz).text();
                if (text == null) continue;
                if (isDesc) desc = text;
                if (isDmnd) dmnd = text;
                if (isDmdd) dmdd = text;
            }
            if (desc != null) info.iccDescription = desc;
            if (dmnd != null) info.iccManufacturerText = dmnd;
            if (dmdd != null) info.iccModelText = dmdd;
        } catch (RuntimeException e) {
            // best effort: keep what could be read
        }
    }

    // ================================================================== editing

    /** One tag table entry (existing or added). */
    private static final class TagEntry {
        final byte[] sig;
        final String sigText;
        final int off;
        final int size;
        boolean removed;
        boolean added;
        /** New tag data (replaced or added tags); null = keep original bytes. */
        byte[] newData;
        /** Data item this entry's bytes live in after the rebuild. */
        Item item;

        TagEntry(byte[] sig, int off, int size) {
            this.sig = sig;
            this.sigText = new String(sig, 0, 4, ByteUtils.ISO_8859_1);
            this.off = off;
            this.size = size;
        }
    }

    /** A contiguous run of output tag data: either original bytes [start, end) or new data. */
    private static final class Item {
        final long orderKey;
        final int start;
        int end;
        final byte[] data;
        int newStart;

        Item(long orderKey, int start, int end, byte[] data) {
            this.orderKey = orderKey;
            this.start = start;
            this.end = end;
            this.data = data;
        }

        int length() {
            return data != null ? data.length : end - start;
        }
    }

    private static byte[] doEdit(byte[] p, IccIdentity id, EditReport rep) {
        int len = p.length;
        if (len < HEADER_SIZE) {
            rep.warn("ICC profili çok kısa (" + len + " bayt), değiştirilmedi");
            return p;
        }
        if (!isAcsp(p)) {
            rep.warn("ICC profili geçersiz ('acsp' imzası yok), değiştirilmedi");
            return p;
        }
        long declared = ByteUtils.u32be(p, 0);
        boolean sizeOk = declared >= TABLE_START && declared <= len;
        int size = sizeOk ? (int) declared : len;
        int major = p[8] & 0xFF;

        // ---- header signatures
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(p, 0, header, 0, HEADER_SIZE);
        List<String> headerChanges = new ArrayList<String>();
        patchSig(header, OFF_CMM, id.cmmSig, "ICC CMM", headerChanges);
        patchSig(header, OFF_PLATFORM, id.platformSig, "ICC platform", headerChanges);
        patchSig(header, OFF_MANUFACTURER, id.manufacturerSig, "ICC üretici", headerChanges);
        patchSig(header, OFF_MODEL, id.modelSig, "ICC model", headerChanges);
        patchSig(header, OFF_CREATOR, id.creatorSig, "ICC oluşturan", headerChanges);

        // ---- text tags: dmnd / dmdd (device names), cprt (copyright), desc (P3 profile name)
        List<String> tagChanges = new ArrayList<String>();
        List<TagEntry> tags = null;
        boolean tagsChanged = false;
        String cprt = nonEmpty(id.copyrightText);
        String p3Desc = nonEmpty(id.p3DescriptionText);
        String wanted = textTagList(id, cprt, p3Desc);
        if (wanted.length() > 0) {
            if (!sizeOk) {
                rep.warn("ICC profil boyutu tutarsız (" + declared + " / " + len
                        + " bayt); " + wanted + " etiketleri değiştirilmedi");
            } else {
                tags = parseTable(p, size);
                if (tags == null) {
                    rep.warn("ICC etiket tablosu düzensiz; " + wanted + " etiketleri değiştirilmedi, "
                            + "yalnızca başlık alanları güncellendi");
                } else {
                    tagsChanged |= planText(p, tags, SIG_DMND, id.manufacturerText, id.addDescTagsIfMissing,
                            major, "ICC üretici açıklaması (dmnd)", tagChanges);
                    tagsChanged |= planText(p, tags, SIG_DMDD, id.modelText, id.addDescTagsIfMissing,
                            major, "ICC model açıklaması (dmdd)", tagChanges);
                    tagsChanged |= planReplace(p, tags, SIG_CPRT, cprt, false, "ICC telif metni", tagChanges, rep);
                    tagsChanged |= planReplace(p, tags, SIG_DESC, p3Desc, true, "ICC profil açıklaması",
                            tagChanges, rep);
                }
            }
        }

        if (!tagsChanged && headerChanges.isEmpty()) return p;

        byte[] out;
        int outSize;
        if (tagsChanged) {
            out = rebuild(p, size, header, tags);
            outSize = (int) ByteUtils.u32be(out, 0);
        } else {
            out = p.clone();
            System.arraycopy(header, 0, out, 0, HEADER_SIZE);
            outSize = size;
        }
        for (String c : headerChanges) rep.change(c);
        for (String c : tagChanges) rep.change(c);
        updateProfileId(p, size, sizeOk, major, out, outSize, rep);
        return out;
    }

    /** Parses the tag table; null when it is not safe to rebuild (out of bounds, overlaps the table...). */
    private static List<TagEntry> parseTable(byte[] p, int size) {
        if (size < TABLE_START) return null;
        long count = ByteUtils.u32be(p, 128);
        if (count > (size - TABLE_START) / 12) return null;
        int n = (int) count;
        int tableEnd = TABLE_START + 12 * n;
        List<TagEntry> list = new ArrayList<TagEntry>(n + 2);
        for (int i = 0; i < n; i++) {
            int e = TABLE_START + 12 * i;
            long off = ByteUtils.u32be(p, e + 4);
            long sz = ByteUtils.u32be(p, e + 8);
            if (sz == 0 || off < tableEnd || off > size || sz > size - off) return null;
            byte[] sig = new byte[4];
            System.arraycopy(p, e, sig, 0, 4);
            list.add(new TagEntry(sig, (int) off, (int) sz));
        }
        return list;
    }

    /** Marks dmnd/dmdd entries for replacement/removal or adds a new one. Returns true if anything changes. */
    private static boolean planText(byte[] p, List<TagEntry> tags, String sig, String target, boolean add,
                                    int major, String label, List<String> changes) {
        if (target == null) return false;
        boolean found = false;
        boolean any = false;
        boolean reported = false;
        for (TagEntry t : tags) {
            if (t.added || !sig.equals(t.sigText)) continue;
            found = true;
            if (target.length() == 0) {
                t.removed = true;
                any = true;
                continue;
            }
            IccText cur = IccText.decode(p, t.off, t.size);
            if (cur.matches(target)) continue;
            // Keep the existing tag type; unknown types get the type the profile version expects.
            String type;
            if (IccText.DESC.equals(cur.type) || IccText.MLUC.equals(cur.type) || IccText.TEXT.equals(cur.type)) {
                type = cur.type;
            } else {
                type = major >= 4 ? IccText.MLUC : IccText.DESC;
            }
            t.newData = IccText.encode(type, target);
            any = true;
            if (!reported) {
                reported = true;
                String old = cur.text();
                changes.add(label + ": " + (old == null ? "(okunamadı)" : display(old)) + " → " + target);
            }
        }
        if (found && target.length() == 0) changes.add(label + " kaldırıldı");
        if (!found && target.length() > 0 && add) {
            TagEntry t = new TagEntry(ByteUtils.fourccBytes(sig), -1, 0);
            t.added = true;
            t.newData = IccText.encode(major >= 4 ? IccText.MLUC : IccText.DESC, target);
            tags.add(t);
            changes.add(label + " eklendi: " + target);
            any = true;
        }
        return any;
    }

    /**
     * Replaces the text of every existing {@code sig} tag (never adds or removes one), keeping its
     * tag type. With {@code onlyP3} only tags whose current text mentions "P3" are touched. Returns
     * true if anything changes.
     */
    private static boolean planReplace(byte[] p, List<TagEntry> tags, String sig, String target, boolean onlyP3,
                                       String label, List<String> changes, EditReport rep) {
        if (target == null) return false;
        boolean any = false;
        boolean reported = false;
        boolean warned = false;
        for (TagEntry t : tags) {
            if (t.added || !sig.equals(t.sigText)) continue;
            IccText cur = IccText.decode(p, t.off, t.size);
            String old = cur.text();
            if (old == null) {
                // unknown tag type or malformed text: never guessed at
                if (!onlyP3 && !warned) {
                    warned = true;
                    rep.warn("ICC '" + sig + "' etiketi okunamadı (tür '" + printable(cur.type)
                            + "'); değiştirilmedi");
                }
                continue;
            }
            if (onlyP3 && !cur.mentions("p3")) continue;
            if (cur.matches(target)) continue;
            t.newData = IccText.encode(cur.type, target);
            any = true;
            if (!reported) {
                reported = true;
                changes.add(label + ": " + display(old) + " → " + target);
            }
        }
        return any;
    }

    /** "dmnd/dmdd/cprt/desc": the text tags this identity asks to change ("" = none). */
    private static String textTagList(IccIdentity id, String cprt, String p3Desc) {
        StringBuilder sb = new StringBuilder();
        if (id.manufacturerText != null) sb.append(SIG_DMND);
        if (id.modelText != null) sb.append(sb.length() > 0 ? "/" : "").append(SIG_DMDD);
        if (cprt != null) sb.append(sb.length() > 0 ? "/" : "").append(SIG_CPRT);
        if (p3Desc != null) sb.append(sb.length() > 0 ? "/" : "").append(SIG_DESC);
        return sb.toString();
    }

    /** Header + tag table + 4-byte aligned tag data; trailing bytes after the declared size are kept. */
    private static byte[] rebuild(byte[] p, int size, byte[] header, List<TagEntry> tags) {
        List<TagEntry> outTags = new ArrayList<TagEntry>();
        for (TagEntry t : tags) {
            if (!t.removed) outTags.add(t);
        }

        // Untouched entries: overlapping (incl. identical = shared) ranges form one block of original bytes.
        List<TagEntry> untouched = new ArrayList<TagEntry>();
        for (TagEntry t : outTags) {
            if (t.newData == null) untouched.add(t);
        }
        Collections.sort(untouched, new Comparator<TagEntry>() {
            @Override
            public int compare(TagEntry a, TagEntry b) {
                return a.off < b.off ? -1 : (a.off == b.off ? 0 : 1);
            }
        });
        List<Item> items = new ArrayList<Item>();
        Item cur = null;
        for (TagEntry t : untouched) {
            int end = t.off + t.size;
            if (cur != null && t.off < cur.end) {
                if (end > cur.end) cur.end = end;
            } else {
                cur = new Item(t.off, t.off, end, null);
                items.add(cur);
            }
            t.item = cur;
        }
        // Replaced entries keep their position in the data order; duplicates of one range share the new copy.
        List<TagEntry> replaced = new ArrayList<TagEntry>();
        for (TagEntry t : outTags) {
            if (t.newData == null || t.added) continue;
            for (TagEntry r : replaced) {
                if (r.off == t.off && r.size == t.size && java.util.Arrays.equals(r.newData, t.newData)) {
                    t.item = r.item;
                    break;
                }
            }
            if (t.item == null) {
                t.item = new Item(t.off, t.off, t.off + t.size, t.newData);
                items.add(t.item);
                replaced.add(t);
            }
        }
        // Added entries go last.
        for (TagEntry t : outTags) {
            if (t.added) {
                t.item = new Item(Long.MAX_VALUE, 0, 0, t.newData);
                items.add(t.item);
            }
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.orderKey < b.orderKey ? -1 : (a.orderKey == b.orderKey ? 0 : 1);
            }
        });

        int n = outTags.size();
        long pos = TABLE_START + 12L * n;
        for (Item it : items) {
            pos = align4(pos);
            it.newStart = (int) pos;
            pos += it.length();
        }
        long total = align4(pos);
        int trailing = p.length - size;
        if (total + trailing > Integer.MAX_VALUE - 8) throw new IllegalStateException("ICC too large");
        byte[] out = new byte[(int) total + trailing];
        System.arraycopy(header, 0, out, 0, HEADER_SIZE);
        ByteUtils.put32be(out, 0, total);
        ByteUtils.put32be(out, 128, n);
        for (int i = 0; i < n; i++) {
            TagEntry t = outTags.get(i);
            int e = TABLE_START + 12 * i;
            System.arraycopy(t.sig, 0, out, e, 4);
            long newOff;
            long newSize;
            if (t.newData != null) {
                newOff = t.item.newStart;
                newSize = t.newData.length;
            } else {
                newOff = t.item.newStart + (long) (t.off - t.item.start);
                newSize = t.size;
            }
            ByteUtils.put32be(out, e + 4, newOff);
            ByteUtils.put32be(out, e + 8, newSize);
        }
        for (Item it : items) {
            if (it.data != null) {
                System.arraycopy(it.data, 0, out, it.newStart, it.data.length);
            } else {
                System.arraycopy(p, it.start, out, it.newStart, it.end - it.start);
            }
        }
        if (trailing > 0) System.arraycopy(p, size, out, (int) total, trailing);
        return out;
    }

    /** Recomputes the MD5 profile ID when the original carried a (valid, or v4) non-zero ID. */
    private static void updateProfileId(byte[] orig, int origSize, boolean sizeOk, int major,
                                        byte[] out, int outSize, EditReport rep) {
        if (isZero(orig, OFF_ID, 16)) return;
        if (!sizeOk) {
            rep.warn("ICC profil kimliği (MD5) güncellenemedi (profil boyutu tutarsız)");
            return;
        }
        byte[] origMd5 = computeId(orig, origSize);
        boolean wasValid = origMd5 != null && rangeEquals(origMd5, 0, orig, OFF_ID, 16);
        // In v2 profiles bytes 84..99 were reserved: keep unknown non-MD5 content untouched there.
        if (!wasValid && major < 4) return;
        byte[] md5 = computeId(out, outSize);
        if (md5 == null) {
            for (int i = 0; i < 16; i++) out[OFF_ID + i] = 0;
            rep.warn("ICC profil kimliği hesaplanamadı (MD5 yok), sıfırlandı");
            return;
        }
        System.arraycopy(md5, 0, out, OFF_ID, 16);
        if (!rangeEquals(md5, 0, orig, OFF_ID, 16)) rep.change("ICC profil kimliği (MD5) yeniden hesaplandı");
    }

    /**
     * ICC profile ID: MD5 over the whole profile with the flags (44..47), rendering intent (64..67)
     * and profile ID (84..99) fields set to zero. Null when MD5 is unavailable.
     */
    static byte[] computeId(byte[] b, int size) {
        if (size < HEADER_SIZE || size > b.length) return null;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        byte[] h = new byte[HEADER_SIZE];
        System.arraycopy(b, 0, h, 0, HEADER_SIZE);
        for (int i = 0; i < 4; i++) {
            h[OFF_FLAGS + i] = 0;
            h[OFF_INTENT + i] = 0;
        }
        for (int i = 0; i < 16; i++) h[OFF_ID + i] = 0;
        md.update(h);
        md.update(b, HEADER_SIZE, size - HEADER_SIZE);
        return md.digest();
    }

    // ================================================================== helpers

    static boolean isAcsp(byte[] p) {
        return p.length >= 40 && p[36] == 'a' && p[37] == 'c' && p[38] == 's' && p[39] == 'p';
    }

    private static void patchSig(byte[] h, int off, String value, String label, List<String> changes) {
        if (value == null) return;
        byte[] nb = sigBytes(value);
        if (rangeEquals(nb, 0, h, off, 4)) return;
        String old = sigDisplay(h, off);
        System.arraycopy(nb, 0, h, off, 4);
        changes.add(label + ": " + old + " → " + sigDisplay(h, off));
    }

    /** "" -> 0x00000000, otherwise 4 printable ASCII chars (right-padded with spaces / truncated). */
    static byte[] sigBytes(String v) {
        byte[] r = new byte[4];
        if (v.length() == 0) return r;
        for (int i = 0; i < 4; i++) {
            if (i < v.length()) {
                char c = v.charAt(i);
                r[i] = (byte) (c >= 0x20 && c < 0x7F ? c : '?');
            } else {
                r[i] = ' ';
            }
        }
        return r;
    }

    /** Signature as text: "" when all zero, trimmed ASCII, or "0x........" for binary values. */
    static String sigText(byte[] b, int off) {
        int end = 4;
        while (end > 0 && (b[off + end - 1] == 0 || b[off + end - 1] == ' ')) end--;
        if (end == 0) return "";
        for (int i = 0; i < end; i++) {
            int c = b[off + i] & 0xFF;
            if (c < 0x20 || c >= 0x7F) return "0x" + ByteUtils.hex(b, off, 4);
        }
        return new String(b, off, end, ByteUtils.ISO_8859_1);
    }

    private static String sigDisplay(byte[] b, int off) {
        String s = sigText(b, off);
        return s.length() == 0 ? "(boş)" : s;
    }

    private static String display(String s) {
        return s.length() == 0 ? "(boş)" : s;
    }

    private static String nonEmpty(String s) {
        return s == null || s.length() == 0 ? null : s;
    }

    /** Tag type signature for messages: printable ASCII, "?" otherwise. */
    private static String printable(String s) {
        if (s == null) return "?";
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }

    private static boolean isZero(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (b[off + i] != 0) return false;
        }
        return true;
    }

    private static boolean rangeEquals(byte[] a, int aOff, byte[] b, int bOff, int len) {
        for (int i = 0; i < len; i++) {
            if (a[aOff + i] != b[bOff + i]) return false;
        }
        return true;
    }

    private static long align4(long v) {
        return (v + 3) & ~3L;
    }
}
