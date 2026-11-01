package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * QuickTime "Keys" metadata: a 'meta' box with handler 'mdta' holding
 * 'keys' (FullBox: entry_count, entries [size][namespace][key]) and
 * 'ilst' (items whose box type is the 1-based key index, each with 'data' atoms).
 *
 * Also hosts the generic 'meta' helpers (style detection, handler type).
 */
final class QtKeysMeta {

    static final String MDTA = "mdta";
    static final String MDIR = "mdir";

    final Mp4Box meta;
    private Mp4Box keys;
    private Mp4Box ilst;
    private byte[] keysVerFlags = new byte[4];
    private final List<byte[]> namespaces = new ArrayList<byte[]>();
    private final List<String> names = new ArrayList<String>();
    private final List<byte[]> rawNames = new ArrayList<byte[]>();
    private boolean keysDirty;

    private QtKeysMeta(Mp4Box meta) {
        this.meta = meta;
    }

    // ------------------------------------------------------------------ generic 'meta' helpers

    /**
     * True when a 'meta' payload starts with FullBox version/flags: the first 4 bytes are 0 and are
     * followed by a plausible box header. (QuickTime moov/meta has no version/flags.)
     */
    static boolean hasVersionFlags(byte[] b, int payloadOff, int payloadLen) {
        if (payloadLen < 12) return false;
        if (ByteUtils.u32be(b, payloadOff) != 0) return false;
        long sz = ByteUtils.u32be(b, payloadOff + 4);
        if (sz < 8 || sz > payloadLen - 4) return false;
        for (int i = 0; i < 4; i++) {
            int c = b[payloadOff + 8 + i] & 0xFF;
            if (!(c >= 0x20 && c < 0x7F) && c != 0xA9) return false;
        }
        return true;
    }

    /** Expands a 'meta' box (detecting its style) and its 'ilst' items. False if unparseable. */
    static boolean expandMeta(Mp4Box meta) {
        if (!meta.isContainer()) {
            if (meta.src == null) return false;
            boolean full = hasVersionFlags(meta.src, meta.origPayloadOffset(), meta.origPayloadLength());
            if (!meta.tryExpand(full ? 4 : 0) && !meta.tryExpand(full ? 0 : 4)) return false;
        }
        Mp4Box il = meta.child("ilst");
        if (il != null) {
            if (!il.tryExpand(0)) return false;
            for (Mp4Box item : il.children) item.tryExpand(0);
        }
        return true;
    }

    /** Handler type of an expanded 'meta' box ('mdta', 'mdir', ...), or null. */
    static String handler(Mp4Box meta) {
        if (!meta.isContainer()) return null;
        Mp4Box h = meta.child("hdlr");
        if (h == null) return null;
        byte[] p = h.payloadBytes();
        if (p.length < 12) return null;
        return ByteUtils.fourcc(p, 8);
    }

    // ------------------------------------------------------------------ open / create

    /** Reads an expanded 'mdta' meta. Throws when the 'keys' table is corrupt. */
    static QtKeysMeta open(Mp4Box meta) throws UnsupportedFormatException {
        QtKeysMeta m = new QtKeysMeta(meta);
        m.keys = meta.child("keys");
        m.ilst = meta.child("ilst");
        if (m.ilst != null && !m.ilst.isContainer()) throw new UnsupportedFormatException("Bozuk 'ilst'");
        if (m.keys != null) {
            byte[] p = m.keys.payloadBytes();
            if (p.length < 8) throw new UnsupportedFormatException("Bozuk 'keys'");
            System.arraycopy(p, 0, m.keysVerFlags, 0, 4);
            long count = ByteUtils.u32be(p, 4);
            if (count > (p.length - 8) / 8) throw new UnsupportedFormatException("Bozuk 'keys'");
            int pos = 8;
            for (long i = 0; i < count; i++) {
                if (pos + 8 > p.length) throw new UnsupportedFormatException("Bozuk 'keys'");
                long ksz = ByteUtils.u32be(p, pos);
                if (ksz < 8 || ksz > p.length - pos) throw new UnsupportedFormatException("Bozuk 'keys'");
                byte[] ns = new byte[4];
                System.arraycopy(p, pos + 4, ns, 0, 4);
                byte[] raw = new byte[(int) ksz - 8];
                System.arraycopy(p, pos + 8, raw, 0, raw.length);
                m.namespaces.add(ns);
                m.rawNames.add(raw);
                m.names.add(QtData.stripNul(new String(raw, ByteUtils.UTF_8)));
                pos += (int) ksz;
            }
        }
        return m;
    }

    /**
     * Creates a QuickTime-style meta (no version/flags) with hdlr 'mdta', keys and ilst, appended to
     * {@code moov} (or to a 'trak' for track-level keys, as iPhones write them).
     */
    static QtKeysMeta create(Mp4Box moov) {
        Mp4Box meta = Mp4Box.newContainer("meta", null);
        Mp4Writer h = new Mp4Writer();
        h.u32(0); // version/flags
        h.u32(0); // pre_defined
        h.fourcc(MDTA);
        h.u32(0);
        h.u32(0);
        h.u32(0); // reserved
        h.u8(0); // empty name
        meta.children.add(Mp4Box.newLeaf("hdlr", h.toByteArray()));
        moov.addChild(meta);
        QtKeysMeta m = new QtKeysMeta(meta);
        m.ensureKeysAndIlst();
        m.keysDirty = true;
        return m;
    }

    private void ensureKeysAndIlst() {
        if (keys == null) {
            keys = Mp4Box.newLeaf("keys", new byte[8]);
            int at = ilst != null ? meta.children.indexOf(ilst) : -1;
            if (at >= 0) meta.addChild(at, keys);
            else meta.addChild(keys);
        }
        if (ilst == null) {
            ilst = Mp4Box.newContainer("ilst", null);
            meta.addChild(ilst);
        }
    }

    // ------------------------------------------------------------------ queries

    int count() {
        return names.size();
    }

    /** Key name at 1-based index. */
    String name(int index) {
        return names.get(index - 1);
    }

    /** No keys and no ilst items left. */
    boolean isEmpty() {
        return names.isEmpty() && (ilst == null || !ilst.isContainer() || ilst.children.isEmpty());
    }

    /** 1-based index of the first key with this exact name, or 0. */
    int find(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name)) return i + 1;
        }
        return 0;
    }

    private List<Mp4Box> items(int index) {
        List<Mp4Box> r = new ArrayList<Mp4Box>();
        if (ilst == null || !ilst.isContainer()) return r;
        for (Mp4Box it : ilst.children) {
            if (it.typeInt() == index) r.add(it);
        }
        return r;
    }

    /** Value of the key at 1-based index (null when there is no readable item). */
    String value(int index) {
        for (Mp4Box it : items(index)) {
            String v = QtData.itemValue(it);
            if (v != null) return v;
        }
        return null;
    }

    /** Numeric value of the key at 1-based index (integer, float or decimal text item), null if none. */
    Long number(int index) {
        for (Mp4Box it : items(index)) {
            Long v = QtData.itemNumber(it);
            if (v != null) return v;
        }
        return null;
    }

    // ------------------------------------------------------------------ edits

    void setValue(int index, String value) {
        ensureKeysAndIlst();
        List<Mp4Box> its = items(index);
        if (its.isEmpty()) {
            ilst.addChild(QtData.newItem(Mp4Box.typeFromInt(index), value));
            return;
        }
        for (Mp4Box it : its) QtData.setItemValue(it, value);
    }

    /** Sets a numeric value, keeping each 'data' atom's type (and integer width when it fits). */
    void setNumber(int index, long value) {
        ensureKeysAndIlst();
        List<Mp4Box> its = items(index);
        if (its.isEmpty()) {
            ilst.addChild(QtData.newNumberItem(Mp4Box.typeFromInt(index), value));
            return;
        }
        for (Mp4Box it : its) QtData.setItemNumber(it, value);
    }

    /** Appends a key ('mdta' namespace) with a value; returns its 1-based index. */
    int add(String name, String value) {
        int idx = addKey(name);
        setValue(idx, value);
        return idx;
    }

    /** Appends a key ('mdta' namespace) with a big-endian signed integer value; returns its 1-based index. */
    int addNumber(String name, long value) {
        int idx = addKey(name);
        setNumber(idx, value);
        return idx;
    }

    private int addKey(String name) {
        ensureKeysAndIlst();
        namespaces.add(MDTA.getBytes(ByteUtils.ISO_8859_1));
        byte[] raw = name.getBytes(ByteUtils.UTF_8);
        rawNames.add(raw);
        names.add(name);
        keysDirty = true;
        return names.size();
    }

    /** Removes the key at 1-based index, its items, and renumbers the items of later keys. */
    void remove(int index) {
        int oldCount = names.size();
        names.remove(index - 1);
        rawNames.remove(index - 1);
        namespaces.remove(index - 1);
        keysDirty = true;
        if (ilst == null || !ilst.isContainer()) return;
        List<Mp4Box> copy = new ArrayList<Mp4Box>(ilst.children);
        for (Mp4Box it : copy) {
            long t = it.typeInt();
            if (t == index) {
                ilst.removeChild(it);
            } else if (t > index && t <= oldCount) {
                it.type = Mp4Box.typeFromInt(t - 1);
                it.modified = true;
            }
        }
    }

    /** Writes the 'keys' table back when it changed. */
    void commit() {
        if (!keysDirty || keys == null) return;
        Mp4Writer w = new Mp4Writer();
        w.write(keysVerFlags);
        w.u32(names.size());
        for (int i = 0; i < names.size(); i++) {
            byte[] raw = rawNames.get(i);
            w.u32(8 + raw.length);
            w.write(namespaces.get(i));
            w.write(raw);
        }
        keys.setPayload(w.toByteArray());
        keysDirty = false;
    }
}
