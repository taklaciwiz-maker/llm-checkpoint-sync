package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a HEIF file-level 'meta' box: the boxes the editor understands are parsed
 * (hdlr, pitm, iloc, iinf/infe, iref, iprp/ipco/ipma, idat, grpl), everything else is kept as raw
 * bytes. All offsets in {@link BoxHeader}s of this class are relative to {@link #raw}.
 */
final class HeifMeta {

    /** One 'infe' entry. */
    static final class Infe {
        long id;
        int version;
        int flags;
        int protectionIndex;
        /** 4-char item type (versions 2/3), null for versions 0/1. */
        String itemType;
        String contentType;
        String contentEncoding;
    }

    /** One SingleItemTypeReferenceBox inside 'iref'. */
    static final class Ref {
        String type;
        long from;
        long[] to;
    }

    final byte[] raw;
    final int boxHeaderSize;
    final boolean largeHeader;
    final boolean fullBox;
    final int childrenStart;
    final int childrenEnd;
    final List<BoxHeader> children;

    BoxHeader ilocBox;
    BoxHeader iinfBox;
    BoxHeader irefBox;
    BoxHeader iprpBox;
    BoxHeader idatBox;
    BoxHeader pitmBox;

    HeifIloc iloc;

    int iinfVersion;
    int iinfFlags;
    long iinfCount;
    final List<Infe> infes = new ArrayList<Infe>();

    long primaryId = -1;

    /** False when 'iref' exists but could not be parsed (it is then kept verbatim). */
    boolean irefParsed = true;
    int irefVersion;
    int irefFlags;
    final List<Ref> refs = new ArrayList<Ref>();

    /** False when 'iprp' exists but could not be parsed (it is then kept verbatim). */
    boolean iprpParsed = true;
    final List<BoxHeader> iprpChildren = new ArrayList<BoxHeader>();
    BoxHeader ipcoBox;
    /** ipco children in order; property index i (1-based) is ipcoProps.get(i - 1). */
    final List<BoxHeader> ipcoProps = new ArrayList<BoxHeader>();
    /** item ID -> associated property indices (1-based), from every 'ipma'. */
    final Map<Long, List<Integer>> assoc = new LinkedHashMap<Long, List<Integer>>();

    /** Every item/entity/group ID seen anywhere (for allocating a new unique item ID). */
    long maxId;

    final List<String> warnings = new ArrayList<String>();

    private HeifMeta(byte[] raw, int boxHeaderSize, boolean largeHeader, boolean fullBox, int childrenStart,
                     int childrenEnd, List<BoxHeader> children) {
        this.raw = raw;
        this.boxHeaderSize = boxHeaderSize;
        this.largeHeader = largeHeader;
        this.fullBox = fullBox;
        this.childrenStart = childrenStart;
        this.childrenEnd = childrenEnd;
        this.children = children;
    }

    /** Lists child boxes in [start, end); overflow-safe. Throws on a malformed child header. */
    static List<BoxHeader> children(byte[] b, int start, int end, String what) throws UnsupportedFormatException {
        List<BoxHeader> list = new ArrayList<BoxHeader>();
        int pos = start;
        while (end - pos >= 8) {
            BoxHeader h;
            try {
                h = BoxHeader.read(b, pos, end);
            } catch (UnsupportedFormatException e) {
                throw HeifBuf.corrupt(what);
            }
            if (h == null) break;
            if (h.size < h.headerSize || h.size > end - pos) throw HeifBuf.corrupt(what);
            list.add(h);
            pos = (int) (pos + h.size);
        }
        return list;
    }

    /** End offset of the last child returned by {@link #children} (start when none). */
    private static int endOf(List<BoxHeader> list, int start) {
        return list.isEmpty() ? start : (int) list.get(list.size() - 1).end();
    }

    static HeifMeta parse(byte[] raw) throws UnsupportedFormatException {
        BoxHeader mh = BoxHeader.read(raw, 0, raw.length);
        if (mh == null || !"meta".equals(mh.type) || mh.size != raw.length) throw HeifBuf.corrupt("meta");
        int hs = mh.headerSize;
        // ISO-BMFF 'meta' is a FullBox; tolerate the QuickTime form (children start right away)
        boolean full = true;
        if (raw.length - hs >= 8 && "hdlr".equals(ByteUtils.fourcc(raw, hs + 4))) full = false;
        int cs = hs + (full ? 4 : 0);
        if (cs > raw.length) throw HeifBuf.corrupt("meta");
        List<BoxHeader> ch = children(raw, cs, raw.length, "meta");
        HeifMeta m = new HeifMeta(raw, hs, mh.largeSize, full, cs, endOf(ch, cs), ch);
        for (int i = 0; i < ch.size(); i++) {
            BoxHeader c = ch.get(i);
            String t = c.type;
            if ("iloc".equals(t)) {
                if (m.ilocBox != null) throw HeifBuf.corrupt("yinelenen iloc");
                m.ilocBox = c;
            } else if ("iinf".equals(t)) {
                if (m.iinfBox != null) throw HeifBuf.corrupt("yinelenen iinf");
                m.iinfBox = c;
            } else if ("iref".equals(t)) {
                if (m.irefBox != null) throw HeifBuf.corrupt("yinelenen iref");
                m.irefBox = c;
            } else if ("iprp".equals(t)) {
                if (m.iprpBox != null) throw HeifBuf.corrupt("yinelenen iprp");
                m.iprpBox = c;
            } else if ("idat".equals(t)) {
                if (m.idatBox != null) throw HeifBuf.corrupt("yinelenen idat");
                m.idatBox = c;
            } else if ("pitm".equals(t)) {
                if (m.pitmBox != null) throw HeifBuf.corrupt("yinelenen pitm");
                m.pitmBox = c;
            } else if ("grpl".equals(t)) {
                m.parseGrpl(c);
            }
        }
        if (m.ilocBox != null) {
            m.iloc = HeifIloc.parse(raw, (int) m.ilocBox.payloadOffset(), (int) m.ilocBox.end());
            for (int i = 0; i < m.iloc.items.size(); i++) m.seeId(m.iloc.items.get(i).id);
        }
        if (m.iinfBox != null) m.parseIinf();
        if (m.pitmBox != null) {
            HeifBuf r = new HeifBuf(raw, (int) m.pitmBox.payloadOffset(), (int) m.pitmBox.end(), "pitm");
            int v = r.u8();
            r.skip(3);
            m.primaryId = v == 0 ? r.u16() : r.u32();
            m.seeId(m.primaryId);
        }
        if (m.irefBox != null) {
            try {
                m.parseIref();
            } catch (UnsupportedFormatException e) {
                m.irefParsed = false;
                m.refs.clear();
            }
        }
        if (m.iprpBox != null) {
            try {
                m.parseIprp();
            } catch (UnsupportedFormatException e) {
                m.iprpParsed = false;
                m.iprpChildren.clear();
                m.ipcoBox = null;
                m.ipcoProps.clear();
                m.assoc.clear();
                m.warnings.add("HEIF özellik kutusu (iprp) bozuk; ICC profili değiştirilmedi");
            }
        }
        return m;
    }

    private void seeId(long id) {
        if (id > maxId) maxId = id;
    }

    private void parseIinf() throws UnsupportedFormatException {
        HeifBuf r = new HeifBuf(raw, (int) iinfBox.payloadOffset(), (int) iinfBox.end(), "iinf");
        iinfVersion = r.u8();
        iinfFlags = (r.u8() << 16) | r.u16();
        iinfCount = iinfVersion == 0 ? r.u16() : r.u32();
        List<BoxHeader> list = children(raw, r.pos, (int) iinfBox.end(), "iinf");
        for (int i = 0; i < list.size(); i++) {
            BoxHeader c = list.get(i);
            if (!"infe".equals(c.type)) continue;
            Infe e = parseInfe(c);
            if (e != null) {
                infes.add(e);
                seeId(e.id);
            }
        }
    }

    /** Parses one infe; returns null when it is malformed (the box itself is kept verbatim). */
    private Infe parseInfe(BoxHeader c) {
        try {
            HeifBuf r = new HeifBuf(raw, (int) c.payloadOffset(), (int) c.end(), "infe");
            Infe e = new Infe();
            e.version = r.u8();
            e.flags = (r.u8() << 16) | r.u16();
            if (e.version < 2) {
                e.id = r.u16();
                e.protectionIndex = r.u16();
                r.cstring(); // item_name
                e.contentType = r.cstring();
                e.contentEncoding = r.remaining() > 0 ? r.cstring() : "";
            } else {
                e.id = e.version == 2 ? r.u16() : r.u32();
                e.protectionIndex = r.u16();
                e.itemType = r.fourcc();
                r.cstring(); // item_name
                if ("mime".equals(e.itemType)) {
                    e.contentType = r.cstring();
                    e.contentEncoding = r.remaining() > 0 ? r.cstring() : "";
                }
            }
            return e;
        } catch (UnsupportedFormatException ex) {
            return null;
        }
    }

    private void parseIref() throws UnsupportedFormatException {
        HeifBuf r = new HeifBuf(raw, (int) irefBox.payloadOffset(), (int) irefBox.end(), "iref");
        irefVersion = r.u8();
        irefFlags = (r.u8() << 16) | r.u16();
        if (irefVersion > 1) throw HeifBuf.corrupt("iref");
        List<BoxHeader> list = children(raw, r.pos, (int) irefBox.end(), "iref");
        if (endOf(list, r.pos) != (int) irefBox.end()) throw HeifBuf.corrupt("iref");
        for (int i = 0; i < list.size(); i++) {
            BoxHeader c = list.get(i);
            if (c.headerSize != 8) throw HeifBuf.corrupt("iref");
            HeifBuf q = new HeifBuf(raw, (int) c.payloadOffset(), (int) c.end(), "iref");
            Ref ref = new Ref();
            ref.type = c.type;
            ref.from = irefVersion == 0 ? q.u16() : q.u32();
            int n = q.u16();
            if (n > q.remaining() / (irefVersion == 0 ? 2 : 4)) throw HeifBuf.corrupt("iref");
            ref.to = new long[n];
            for (int k = 0; k < n; k++) ref.to[k] = irefVersion == 0 ? q.u16() : q.u32();
            if (q.remaining() != 0) throw HeifBuf.corrupt("iref");
            refs.add(ref);
            seeId(ref.from);
            for (int k = 0; k < n; k++) seeId(ref.to[k]);
        }
    }

    private void parseIprp() throws UnsupportedFormatException {
        List<BoxHeader> list = children(raw, (int) iprpBox.payloadOffset(), (int) iprpBox.end(), "iprp");
        iprpChildren.addAll(list);
        for (int i = 0; i < list.size(); i++) {
            BoxHeader c = list.get(i);
            if ("ipco".equals(c.type)) {
                if (ipcoBox != null) throw HeifBuf.corrupt("yinelenen ipco");
                ipcoBox = c;
                ipcoProps.addAll(children(raw, (int) c.payloadOffset(), (int) c.end(), "ipco"));
            }
        }
        for (int i = 0; i < list.size(); i++) {
            BoxHeader c = list.get(i);
            if (!"ipma".equals(c.type)) continue;
            try {
                parseIpma(c);
            } catch (UnsupportedFormatException e) {
                // associations are only used for inspect(); the box is kept verbatim
            }
        }
    }

    private void parseIpma(BoxHeader c) throws UnsupportedFormatException {
        HeifBuf r = new HeifBuf(raw, (int) c.payloadOffset(), (int) c.end(), "ipma");
        int v = r.u8();
        int fl = (r.u8() << 16) | r.u16();
        long n = r.u32();
        for (long i = 0; i < n; i++) {
            long id = v < 1 ? r.u16() : r.u32();
            int cnt = r.u8();
            List<Integer> l = assoc.get(id);
            if (l == null) {
                l = new ArrayList<Integer>();
                assoc.put(id, l);
            }
            for (int k = 0; k < cnt; k++) {
                int idx = (fl & 1) != 0 ? (r.u16() & 0x7FFF) : (r.u8() & 0x7F);
                l.add(idx);
            }
            seeId(id);
        }
    }

    private void parseGrpl(BoxHeader c) {
        // EntityToGroupBoxes: group_id shares the item ID space
        try {
            List<BoxHeader> list = children(raw, (int) c.payloadOffset(), (int) c.end(), "grpl");
            for (int i = 0; i < list.size(); i++) {
                BoxHeader g = list.get(i);
                HeifBuf r = new HeifBuf(raw, (int) g.payloadOffset(), (int) g.end(), "grpl");
                r.skip(4);
                seeId(r.u32());
                long n = r.u32();
                for (long k = 0; k < n && r.remaining() >= 4; k++) seeId(r.u32());
            }
        } catch (UnsupportedFormatException e) {
            // ignore: only used to avoid ID clashes
        }
    }

    // ------------------------------------------------------------------ lookups

    Infe infe(long id) {
        for (int i = 0; i < infes.size(); i++) {
            if (infes.get(i).id == id) return infes.get(i);
        }
        return null;
    }

    /** Property box (1-based index) or null. */
    BoxHeader property(int index) {
        if (index < 1 || index > ipcoProps.size()) return null;
        return ipcoProps.get(index - 1);
    }

    /** True when {@code from} has a reference of {@code type} to {@code to}. */
    boolean hasRef(String type, long from, long to) {
        for (int i = 0; i < refs.size(); i++) {
            Ref r = refs.get(i);
            if (!r.type.equals(type) || r.from != from) continue;
            for (int k = 0; k < r.to.length; k++) if (r.to[k] == to) return true;
        }
        return false;
    }

    /** idat payload range in raw: {start, end}, or null. */
    int[] idatRange() {
        if (idatBox == null) return null;
        return new int[]{(int) idatBox.payloadOffset(), (int) idatBox.end()};
    }

    /** Raw bytes of a child box (copy). */
    byte[] rawBox(BoxHeader b) {
        byte[] r = new byte[(int) b.size];
        System.arraycopy(raw, (int) b.offset, r, 0, r.length);
        return r;
    }
}
