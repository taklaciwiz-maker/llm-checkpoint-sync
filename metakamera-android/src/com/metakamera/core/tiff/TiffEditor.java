package com.metakamera.core.tiff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.icc.IccProfileEditor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.metakamera.core.tiff.TiffFile.*;

/**
 * Applies an {@link EditSpec} to a parsed EXIF TIFF.
 *
 * <p>Edits are first recorded on the in-memory model ({@link TiffIfd}/{@link TiffEntry}); then
 * {@link #commitAppend()} writes them append-only (nothing that exists moves), and only when that
 * result is larger than the allowed size {@link #commitCompact(int)} re-serializes everything.
 */
final class TiffEditor {

    private static final Comparator<int[]> BY_START = new Comparator<int[]>() {
        @Override
        public int compare(int[] x, int[] y) {
            return x[0] < y[0] ? -1 : (x[0] == y[0] ? 0 : 1);
        }
    };

    private final TiffFile tf;
    private final EditSpec spec;
    private final EditReport r;
    /** Source ranges [start, end) whose content is no longer referenced (zero-filled on commit). */
    private final List<int[]> freed = new ArrayList<int[]>();
    private boolean changed;
    private boolean lensChanged;

    private TiffEditor(TiffFile tf, EditSpec spec, EditReport r) {
        this.tf = tf;
        this.spec = spec;
        this.r = r;
    }

    static byte[] edit(byte[] tiff, EditSpec spec, int maxSize, EditReport report) throws IOException {
        TiffFile tf = TiffFile.parse(tiff);
        for (int i = 0; i < tf.warnings.size(); i++) report.warn(tf.warnings.get(i));
        TiffEditor ed = new TiffEditor(tf, spec, report);
        ed.applyEdits();
        if (!ed.changed) return tiff;
        ed.dropBrokenLinks();
        byte[] out = ed.commitAppend();
        if (out.length > maxSize) out = ed.commitCompact(maxSize);
        return out;
    }

    // =================================================================== model edits

    private void applyEdits() {
        TiffIfd ifd0 = tf.ifd0;
        String oldMake = tf.string(ifd0.find(TAG_MAKE));

        setString(ifd0, TAG_MAKE, "Make", spec.make, true, "EXIF ");
        setString(ifd0, TAG_MODEL, "Model", spec.model, true, "EXIF ");
        setString(ifd0, TAG_SOFTWARE, "Software", spec.software, true, "EXIF ");
        setString(ifd0, TAG_HOST_COMPUTER, "HostComputer", spec.hostComputer, true, "EXIF ");
        if (tf.ifd1 != null) {
            // the thumbnail IFD: only update what is already there
            setString(tf.ifd1, TAG_MAKE, "Make", spec.make, false, "EXIF IFD1 ");
            setString(tf.ifd1, TAG_MODEL, "Model", spec.model, false, "EXIF IFD1 ");
            setString(tf.ifd1, TAG_SOFTWARE, "Software", spec.software, false, "EXIF IFD1 ");
            setString(tf.ifd1, TAG_HOST_COMPUTER, "HostComputer", spec.hostComputer, false, "EXIF IFD1 ");
        }

        boolean wantLens = nonEmpty(spec.lensMake) || nonEmpty(spec.lensModel);
        if (tf.exif == null && wantLens) {
            if (tf.exifBroken) {
                // the old pointer leads nowhere usable: replace it with a fresh Exif IFD
                dropPointer(ifd0, TAG_EXIF_IFD, "Bozuk Exif IFD bağlantısı kaldırıldı");
                tf.exifBroken = false;
            }
            createExifIfd();
        }
        if (tf.exif != null) {
            setString(tf.exif, TAG_LENS_MAKE, "LensMake", spec.lensMake, true, "EXIF ");
            setString(tf.exif, TAG_LENS_MODEL, "LensModel", spec.lensModel, true, "EXIF ");
            if (lensChanged) bumpExifVersion(tf.exif);
        }

        if (spec.removeMakerNote) {
            TiffIfd[] where = {tf.exif, ifd0};
            for (TiffIfd ifd : where) {
                if (ifd == null) continue;
                List<TiffEntry> mns = ifd.findAll(TAG_MAKER_NOTE);
                for (int i = 0; i < mns.size(); i++) {
                    TiffEntry mn = mns.get(i);
                    String vendor = makerNoteVendor(mn, oldMake);
                    removeEntry(ifd, mn);
                    r.change(vendor == null ? "MakerNote temizlendi" : "MakerNote temizlendi (" + vendor + ")");
                }
            }
        }

        if (spec.clearSerials) {
            int[] tags = {TAG_BODY_SERIAL, TAG_LENS_SERIAL, TAG_CAMERA_OWNER, TAG_IMAGE_UNIQUE_ID};
            String[] names = {"BodySerialNumber", "LensSerialNumber", "CameraOwnerName", "ImageUniqueID"};
            TiffIfd[] where = {tf.exif, ifd0};
            for (TiffIfd ifd : where) {
                if (ifd == null) continue;
                for (int t = 0; t < tags.length; t++) {
                    List<TiffEntry> es = ifd.findAll(tags[t]);
                    if (es.isEmpty()) continue;
                    for (int i = 0; i < es.size(); i++) removeEntry(ifd, es.get(i));
                    r.change("EXIF " + names[t] + " kaldırıldı");
                }
            }
        }

        if (spec.editIcc && spec.icc != null) editEmbeddedIcc(ifd0);
    }

    private static boolean nonEmpty(String s) {
        return s != null && s.replace("\0", "").length() > 0;
    }

    /**
     * null = keep; "" = remove if present; otherwise set (create only when {@code allowCreate}).
     */
    private void setString(TiffIfd ifd, int tag, String name, String value, boolean allowCreate, String prefix) {
        if (value == null) return;
        value = value.replace("\0", "");
        List<TiffEntry> es = ifd.findAll(tag);
        if (value.length() == 0) {
            if (es.isEmpty()) return;
            for (int i = 0; i < es.size(); i++) removeEntry(ifd, es.get(i));
            r.change(prefix + name + " kaldırıldı");
            return;
        }
        byte[] nb = TiffWriter.ascii(value);
        if (es.isEmpty()) {
            if (!allowCreate) return;
            ifd.entries.add(TiffEntry.create(tag, T_ASCII, nb.length, nb));
            ifd.structural = true;
            changed = true;
            r.change(prefix + name + " eklendi: " + value);
            markLens(tag);
            return;
        }
        TiffEntry e = es.get(0);
        for (int i = 1; i < es.size(); i++) removeEntry(ifd, es.get(i)); // duplicate entries
        byte[] old = tf.rawString(e);
        boolean same = old != null && e.type == T_ASCII && Arrays.equals(old, Arrays.copyOf(nb, nb.length - 1));
        if (same) {
            if (es.size() > 1) r.change(prefix + name + ": yinelenen girdi kaldırıldı");
            return;
        }
        e.newValue = nb;
        e.type = T_ASCII;
        e.count = nb.length;
        e.dirty = true;
        changed = true;
        String oldText = old == null ? "?" : TiffFile.decode(old);
        if (oldText.length() == 0) oldText = "(boş)";
        r.change(prefix + name + ": " + oldText + " → " + value);
        markLens(tag);
    }

    private void markLens(int tag) {
        if (tag == TAG_LENS_MAKE || tag == TAG_LENS_MODEL) lensChanged = true;
    }

    private void removeEntry(TiffIfd ifd, TiffEntry e) {
        ifd.entries.remove(e);
        ifd.structural = true;
        changed = true;
        if (e.hasOutOfLineSourceValue()) freed.add(new int[]{e.valuePos, e.valuePos + (int) e.byteLen});
    }

    /**
     * Called only when the block is re-written: links that lead outside the data or into another
     * structure (and values stored beyond the end) could otherwise point into the bytes appended by
     * this edit, so they are removed.
     */
    private void dropBrokenLinks() {
        if (tf.exifBroken) dropPointer(tf.ifd0, TAG_EXIF_IFD, "Bozuk Exif IFD bağlantısı kaldırıldı");
        if (tf.gpsBroken) dropPointer(tf.ifd0, TAG_GPS_IFD, "Bozuk GPS IFD bağlantısı kaldırıldı");
        if (tf.interopBroken && tf.exif != null) {
            dropPointer(tf.exif, TAG_INTEROP_IFD, "Bozuk Interop IFD bağlantısı kaldırıldı");
        }
        if (tf.ifd1Broken) {
            tf.ifd0.next = 0;
            tf.ifd0.nextDirty = true;
            r.warn("Bozuk IFD1 bağlantısı kaldırıldı");
        }
        int dangling = 0;
        TiffIfd[] ifds = tf.allIfds();
        for (TiffIfd ifd : ifds) {
            if (ifd == null || ifd.pos < 0) continue;
            List<TiffEntry> es = new ArrayList<TiffEntry>(ifd.entries);
            for (int i = 0; i < es.size(); i++) {
                TiffEntry e = es.get(i);
                if (e.newValue != null || e.entryPos < 0 || e.inline || e.byteLen <= 4 || e.valuePos >= 0) continue;
                if (tf.u32(e.field, 0) < 8) continue; // can never become valid
                removeEntry(ifd, e);
                dangling++;
            }
        }
        if (dangling > 0) r.warn(dangling + " bozuk EXIF girdisi (dosya sonunu aşan) kaldırıldı");
    }

    private void dropPointer(TiffIfd ifd, int tag, String warning) {
        List<TiffEntry> es = ifd.findAll(tag);
        if (es.isEmpty()) return;
        for (int i = 0; i < es.size(); i++) ifd.entries.remove(es.get(i));
        ifd.structural = true;
        changed = true;
        r.warn(warning);
    }

    /** Adds a new Exif IFD (with the tags EXIF requires) and its pointer in IFD0. */
    private void createExifIfd() {
        TiffIfd ex = new TiffIfd("ExifIFD");
        ex.structural = true;
        ex.entries.add(TiffEntry.create(TAG_EXIF_VERSION, T_UNDEFINED, 4, "0232".getBytes(ByteUtils.US_ASCII)));
        ex.entries.add(TiffEntry.create(TAG_COMPONENTS_CONFIGURATION, T_UNDEFINED, 4, new byte[]{1, 2, 3, 0}));
        ex.entries.add(TiffEntry.create(TAG_FLASHPIX_VERSION, T_UNDEFINED, 4, "0100".getBytes(ByteUtils.US_ASCII)));
        byte[] cs = new byte[2];
        tf.put16(cs, 0, 1);
        ex.entries.add(TiffEntry.create(TAG_COLOR_SPACE, T_SHORT, 1, cs));
        tf.exif = ex;
        TiffEntry ptr = TiffEntry.create(TAG_EXIF_IFD, T_LONG, 1, null); // value set on commit
        tf.ifd0.entries.add(ptr);
        tf.ifd0.structural = true;
        changed = true;
        r.change("Exif IFD oluşturuldu");
    }

    /** LensMake/LensModel need ExifVersion 0230+; raise an older version to 0232. */
    private void bumpExifVersion(TiffIfd ex) {
        TiffEntry v = ex.find(TAG_EXIF_VERSION);
        if (v == null || v.newValue != null || v.type != T_UNDEFINED || v.count != 4) return;
        byte[] b = tf.value(v);
        if (b == null || b.length != 4) return;
        int ver = 0;
        for (int i = 0; i < 4; i++) {
            if (b[i] < '0' || b[i] > '9') return;
            ver = ver * 10 + (b[i] - '0');
        }
        if (ver >= 230) return;
        v.newValue = "0232".getBytes(ByteUtils.US_ASCII);
        v.dirty = true;
        changed = true;
        r.change("EXIF ExifVersion: " + new String(b, ByteUtils.US_ASCII) + " → 0232");
    }

    /** Vendor label for the report: the MakerNote's ASCII signature ("HUAWEI", "Apple iOS") or the old Make. */
    private String makerNoteVendor(TiffEntry mn, String oldMake) {
        if (mn.valuePos >= 0 && mn.byteLen > 0) {
            StringBuilder sb = new StringBuilder();
            int n = (int) Math.min(16, mn.byteLen);
            for (int i = 0; i < n; i++) {
                int c = tf.d[mn.valuePos + i] & 0xFF;
                boolean alnum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
                if (alnum || (c == ' ' && sb.length() > 0)) sb.append((char) c);
                else break;
            }
            String s = sb.toString().trim();
            if (s.length() >= 3 && Character.isLetter(s.charAt(0))) return s;
        }
        if (oldMake != null && oldMake.trim().length() > 0) return oldMake.trim();
        return null;
    }

    /** ICC profile stored in EXIF (tag 0x8773): rewrite its device identity too. */
    private void editEmbeddedIcc(TiffIfd ifd0) {
        TiffEntry e = ifd0.find(TAG_ICC);
        if (e == null) return;
        byte[] prof = tf.value(e);
        if (prof == null || prof.length < 128) {
            r.warn("EXIF içindeki ICC profili bozuk; olduğu gibi bırakıldı");
            return;
        }
        try {
            byte[] np = IccProfileEditor.edit(prof, spec.icc, r);
            if (np != null && np != prof && !Arrays.equals(np, prof)) {
                e.newValue = np;
                e.type = T_UNDEFINED;
                e.count = np.length;
                e.dirty = true;
                changed = true;
            }
        } catch (IOException ex) {
            r.warn("EXIF içindeki ICC profili düzenlenemedi: " + ex.getMessage());
        } catch (RuntimeException ex) {
            r.warn("EXIF içindeki ICC profili düzenlenemedi");
        }
    }

    // =================================================================== append-only commit

    private byte[] commitAppend() {
        TiffBuf out = new TiffBuf(tf.le, tf.d);
        List<int[]> live = new ArrayList<int[]>();
        List<int[]> fr = new ArrayList<int[]>(freed);
        live.add(new int[]{0, 8});

        // children before parents, so parents can point at moved children
        placeIfd(tf.interop, out, live, fr);
        placeIfd(tf.gps, out, live, fr);
        if (tf.exif != null) {
            if (tf.interop != null && tf.interop.newPos >= 0) setPointer(tf.exif, TAG_INTEROP_IFD, tf.interop.newPos);
            placeIfd(tf.exif, out, live, fr);
        }
        placeIfd(tf.ifd1, out, live, fr);

        TiffIfd ifd0 = tf.ifd0;
        if (tf.exif != null && tf.exif.newPos >= 0) setPointer(ifd0, TAG_EXIF_IFD, tf.exif.newPos);
        if (tf.gps != null && tf.gps.newPos >= 0) setPointer(ifd0, TAG_GPS_IFD, tf.gps.newPos);
        if (tf.ifd1 != null && tf.ifd1.newPos >= 0) {
            ifd0.next = tf.ifd1.newPos;
            ifd0.nextDirty = true;
        }
        placeIfd(ifd0, out, live, fr);
        if (ifd0.newPos >= 0) out.put32(4, ifd0.newPos);

        zeroFreed(out, fr, live);
        return out.toByteArray();
    }

    private void setPointer(TiffIfd ifd, int tag, int pos) {
        TiffEntry e = ifd.find(tag);
        if (e == null) return;
        e.type = T_LONG;
        e.count = 1;
        tf.put32(e.field, 0, pos);
        e.dirty = true;
    }

    private void placeIfd(TiffIfd ifd, TiffBuf out, List<int[]> live, List<int[]> fr) {
        if (ifd == null) return;
        for (int i = 0; i < ifd.entries.size(); i++) {
            TiffEntry e = ifd.entries.get(i);
            if (e.newValue != null) placeValue(e, out, fr);
        }
        if (ifd.structural || ifd.pos < 0) {
            if (ifd.pos >= 0) fr.add(new int[]{ifd.pos, ifd.srcEnd()});
            byte[] ser = serialize(ifd);
            out.align2();
            int p = out.write(ser);
            ifd.newPos = p;
            live.add(new int[]{p, p + ser.length});
        } else {
            for (int i = 0; i < ifd.entries.size(); i++) {
                TiffEntry e = ifd.entries.get(i);
                if (e.dirty && e.entryPos >= 0) writeEntryAt(out, e.entryPos, e);
            }
            if (ifd.nextDirty && ifd.hasNextField) out.put32(ifd.pos + 2 + 12 * ifd.srcCount, ifd.next);
            live.add(new int[]{ifd.pos, ifd.srcEnd()});
        }
        for (int i = 0; i < ifd.entries.size(); i++) {
            TiffEntry e = ifd.entries.get(i);
            if (e.newValue != null) {
                if (e.outValuePos >= 0) live.add(new int[]{e.outValuePos, e.outValuePos + e.outValueLen});
            } else if (e.hasOutOfLineSourceValue()) {
                live.add(new int[]{e.valuePos, e.valuePos + (int) e.byteLen});
            }
        }
        live.addAll(tf.dataRanges(ifd));
    }

    /** Stores a new value: inline, in place over the old slot, or appended. */
    private void placeValue(TiffEntry e, TiffBuf out, List<int[]> fr) {
        byte[] nv = e.newValue;
        boolean hadOld = e.hasOutOfLineSourceValue();
        if (nv.length <= 4) {
            Arrays.fill(e.field, (byte) 0);
            System.arraycopy(nv, 0, e.field, 0, nv.length);
            e.outValuePos = -1;
            if (hadOld) fr.add(new int[]{e.valuePos, e.valuePos + (int) e.byteLen});
        } else if (hadOld && e.byteLen >= nv.length && exclusive(e)) {
            out.writeAt(e.valuePos, nv, 0, nv.length);
            tf.put32(e.field, 0, e.valuePos);
            e.outValuePos = e.valuePos;
            e.outValueLen = nv.length;
            if (e.byteLen > nv.length) fr.add(new int[]{e.valuePos + nv.length, e.valuePos + (int) e.byteLen});
        } else {
            int p = out.align2();
            out.write(nv);
            tf.put32(e.field, 0, p);
            e.outValuePos = p;
            e.outValueLen = nv.length;
            if (hadOld) fr.add(new int[]{e.valuePos, e.valuePos + (int) e.byteLen});
        }
        e.dirty = true;
    }

    /** The entry's source slot is not shared with any other structure, value or image data. */
    private boolean exclusive(TiffEntry e) {
        int s = e.valuePos;
        int t = s + (int) e.byteLen;
        if (s < 8) return false;
        for (int i = 0; i < tf.ifdRanges.size(); i++) {
            int[] rg = tf.ifdRanges.get(i);
            if (s < rg[1] && rg[0] < t) return false;
        }
        for (int i = 0; i < tf.allEntries.size(); i++) {
            TiffEntry o = tf.allEntries.get(i);
            if (o == e || !o.hasOutOfLineSourceValue()) continue;
            if (s < o.valuePos + o.byteLen && o.valuePos < t) return false;
        }
        TiffIfd[] ifds = tf.allIfds();
        for (TiffIfd ifd : ifds) {
            List<int[]> dr = tf.dataRanges(ifd);
            for (int i = 0; i < dr.size(); i++) {
                int[] rg = dr.get(i);
                if (s < rg[1] && rg[0] < t) return false;
            }
        }
        return true;
    }

    private byte[] serialize(TiffIfd ifd) {
        List<TiffEntry> es = ifd.sortedEntries();
        int n = es.size();
        if (n > 0xFFFF) throw new IllegalStateException("too many IFD entries");
        byte[] b = new byte[2 + 12 * n + 4];
        tf.put16(b, 0, n);
        for (int i = 0; i < n; i++) {
            TiffEntry e = es.get(i);
            int p = 2 + 12 * i;
            tf.put16(b, p, e.tag);
            tf.put16(b, p + 2, e.type);
            tf.put32(b, p + 4, e.count);
            System.arraycopy(e.field, 0, b, p + 8, 4);
        }
        tf.put32(b, 2 + 12 * n, ifd.next);
        return b;
    }

    private void writeEntryAt(TiffBuf out, int pos, TiffEntry e) {
        out.put16(pos, e.tag);
        out.put16(pos + 2, e.type);
        out.put32(pos + 4, e.count);
        out.writeAt(pos + 8, e.field, 0, 4);
    }

    /** Zero-fills every freed byte that is not part of a live (still referenced) range. */
    private static void zeroFreed(TiffBuf out, List<int[]> fr, List<int[]> live) {
        for (int k = 0; k < fr.size(); k++) {
            int[] f = fr.get(k);
            int s = Math.max(0, f[0]);
            int t = Math.min(out.length(), f[1]);
            if (s >= t) continue;
            List<int[]> ov = new ArrayList<int[]>();
            for (int i = 0; i < live.size(); i++) {
                int[] l = live.get(i);
                if (l[0] < t && s < l[1]) ov.add(l);
            }
            Collections.sort(ov, BY_START);
            int cur = s;
            for (int i = 0; i < ov.size() && cur < t; i++) {
                int[] l = ov.get(i);
                if (l[0] > cur) out.zero(cur, Math.min(l[0], t));
                cur = Math.max(cur, l[1]);
            }
            if (cur < t) out.zero(cur, t);
        }
    }

    // =================================================================== compacting fallback

    private byte[] commitCompact(int maxSize) throws UnsupportedFormatException {
        List<String> notes = new ArrayList<String>();
        boolean[] hasIfd1 = new boolean[1];
        byte[] res = buildCompact(false, notes, hasIfd1);
        if (res.length > maxSize && hasIfd1[0]) {
            notes.clear();
            res = buildCompact(true, notes, hasIfd1);
            notes.add("Küçük resim kaldırıldı");
        }
        if (res.length > maxSize) {
            throw new UnsupportedFormatException("EXIF verisi izin verilen boyuta (" + maxSize + " bayt) sığmıyor");
        }
        r.warn("EXIF boyut sınırını aştığı için yeniden düzenlendi");
        for (int i = 0; i < notes.size(); i++) r.warn(notes.get(i));
        return res;
    }

    /** Full re-serialization of IFD0, Exif, Interop, GPS and (optionally) IFD1 with its thumbnail. */
    private byte[] buildCompact(boolean dropIfd1, List<String> notes, boolean[] hasIfd1) {
        TiffWriter w = new TiffWriter(tf.le, tf.d.length + 256);
        int[] dropped = new int[1];
        boolean[] makerNote = new boolean[1];

        TiffWriter.WIfd i0 = toW(tf.ifd0, false, dropped, makerNote);
        TiffWriter.WIfd ex = tf.exif == null ? null : toW(tf.exif, false, dropped, makerNote);
        TiffWriter.WIfd io = (ex == null || tf.interop == null) ? null : toW(tf.interop, false, dropped, makerNote);
        TiffWriter.WIfd gp = tf.gps == null ? null : toW(tf.gps, false, dropped, makerNote);
        TiffWriter.WIfd i1 = null;

        // IFD1 image data: JPEG thumbnail and/or strips
        long thumbOff = -1;
        long thumbLen = 0;
        long[] stripOff = null;
        long[] stripCnt = null;
        if (!dropIfd1 && tf.ifd1 != null) {
            boolean ok = true;
            TiffEntry jp = tf.ifd1.find(TAG_JPEG_IF);
            if (jp != null) {
                thumbOff = tf.pointerValue(jp);
                thumbLen = tf.pointerValue(tf.ifd1.find(TAG_JPEG_IF_LEN));
                if (thumbOff < 8 || thumbLen <= 0 || thumbOff + thumbLen > tf.d.length) ok = false;
            }
            TiffEntry so = tf.ifd1.find(TAG_STRIP_OFFSETS);
            if (ok && so != null) {
                stripOff = tf.uintArray(so);
                stripCnt = tf.uintArray(tf.ifd1.find(TAG_STRIP_BYTE_COUNTS));
                if (stripOff == null || stripCnt == null || stripOff.length != stripCnt.length) {
                    ok = false;
                } else {
                    for (int k = 0; k < stripOff.length; k++) {
                        if (stripOff[k] < 8 || stripOff[k] + stripCnt[k] > tf.d.length) ok = false;
                    }
                }
            }
            if (ok) {
                i1 = toW(tf.ifd1, true, dropped, makerNote);
                if (jp != null) i1.add(new TiffWriter.WEntry(TAG_JPEG_IF, T_LONG, 1, new byte[4]));
                if (so != null) {
                    i1.add(new TiffWriter.WEntry(TAG_STRIP_OFFSETS, T_LONG, stripOff.length, new byte[4 * stripOff.length]));
                }
            } else {
                notes.add("Bozuk küçük resim (IFD1) kaldırıldı");
            }
        }
        hasIfd1[0] = i1 != null;
        if (tf.ifd1Broken) notes.add("Bozuk IFD1 kaldırıldı");
        if (tf.exifBroken) notes.add("Bozuk Exif IFD kaldırıldı");
        if (tf.gpsBroken) notes.add("Bozuk GPS IFD kaldırıldı");
        if (tf.interopBroken) notes.add("Bozuk Interop IFD kaldırıldı");

        TiffWriter.WEntry pEx = null;
        TiffWriter.WEntry pGps = null;
        TiffWriter.WEntry pIo = null;
        if (ex != null) i0.add(pEx = new TiffWriter.WEntry(TAG_EXIF_IFD, T_LONG, 1, new byte[4]));
        if (gp != null) i0.add(pGps = new TiffWriter.WEntry(TAG_GPS_IFD, T_LONG, 1, new byte[4]));
        if (io != null) ex.add(pIo = new TiffWriter.WEntry(TAG_INTEROP_IFD, T_LONG, 1, new byte[4]));

        w.writeIfd(i0);
        w.setIfd0(i0.pos);
        if (ex != null) {
            w.writeIfd(ex);
            w.patch32(pEx.fieldPos, ex.pos);
        }
        if (io != null) {
            w.writeIfd(io);
            w.patch32(pIo.fieldPos, io.pos);
        }
        if (gp != null) {
            w.writeIfd(gp);
            w.patch32(pGps.fieldPos, gp.pos);
        }
        if (i1 != null) {
            w.writeIfd(i1);
            w.patch32(i0.nextPos, i1.pos);
            TiffWriter.WEntry jp = i1.find(TAG_JPEG_IF);
            if (jp != null) {
                int p = w.writeData(tf.d, (int) thumbOff, (int) thumbLen);
                w.patch32(jp.fieldPos, p);
            }
            TiffWriter.WEntry so = i1.find(TAG_STRIP_OFFSETS);
            if (so != null) {
                for (int k = 0; k < stripOff.length; k++) {
                    int p = w.writeData(tf.d, (int) stripOff[k], (int) stripCnt[k]);
                    w.patch32(so.dataPos + 4 * k, p);
                }
            }
        }
        if (dropped[0] > 0) notes.add(dropped[0] + " bozuk/desteklenmeyen EXIF girdisi atlandı");
        if (makerNote[0]) notes.add("MakerNote taşındı; içindeki ofsetler bozulabilir");
        return w.toByteArray();
    }

    /** Converts the edited model of one IFD to serializable entries (pointers/image data handled by caller). */
    private TiffWriter.WIfd toW(TiffIfd ifd, boolean isIfd1, int[] dropped, boolean[] makerNote) {
        TiffWriter.WIfd w = new TiffWriter.WIfd();
        for (int i = 0; i < ifd.entries.size(); i++) {
            TiffEntry e = ifd.entries.get(i);
            int tag = e.tag;
            if (tag == TAG_EXIF_IFD || tag == TAG_GPS_IFD || tag == TAG_INTEROP_IFD) continue; // re-added
            if (tag == TAG_JPEG_IF || tag == TAG_STRIP_OFFSETS) {
                if (!isIfd1) dropped[0]++;
                continue; // IFD1: re-added by the caller
            }
            if (tag == TAG_SUB_IFDS || tag == TAG_TILE_OFFSETS || tag == TAG_TILE_BYTE_COUNTS
                    || (!isIfd1 && (tag == TAG_JPEG_IF_LEN || tag == TAG_STRIP_BYTE_COUNTS))) {
                dropped[0]++;
                continue;
            }
            byte[] data;
            if (e.newValue != null) {
                data = e.newValue;
            } else if (e.byteLen < 0 || e.valuePos < 0) {
                dropped[0]++;
                continue;
            } else if (e.inline) {
                data = Arrays.copyOf(e.field, (int) e.byteLen);
            } else {
                data = Arrays.copyOfRange(tf.d, e.valuePos, e.valuePos + (int) e.byteLen);
            }
            if (tag == TAG_MAKER_NOTE) makerNote[0] = true;
            w.add(new TiffWriter.WEntry(tag, e.type, e.count, data));
        }
        return w;
    }
}
