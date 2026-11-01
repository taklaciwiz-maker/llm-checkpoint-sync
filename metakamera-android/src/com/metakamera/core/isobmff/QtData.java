package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Encoding helpers for QuickTime metadata values:
 * <ul>
 *   <li>'data' atoms (ilst items of 'mdta' Keys metadata and iTunes 'mdir' metadata):
 *       [uint32 type indicator][uint32 locale][value]</li>
 *   <li>QuickTime user-data text atoms ('©mak', '©mod', '©swr' in moov/udta):
 *       one or more [uint16 length][uint16 language][text]</li>
 * </ul>
 */
final class QtData {

    static final String DATA = "data";
    /** Packed ISO-639-2 "eng". */
    static final int LANG_ENG = 0x15C7;

    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");

    private QtData() {
    }

    // ------------------------------------------------------------------ 'data' atoms

    /** Decodes a 'data' atom payload (type indicator + locale + value); null if too short. */
    static String decodeDataPayload(byte[] p) {
        if (p == null || p.length < 8) return null;
        int type = (int) (ByteUtils.u32be(p, 0) & 0xFFFFFF);
        int n = p.length - 8;
        String s;
        if (type == 2 || type == 5) {
            s = new String(p, 8, n - (n % 2), UTF_16BE);
        } else {
            s = new String(p, 8, n, ByteUtils.UTF_8);
        }
        return stripNul(s);
    }

    /** Payload of a UTF-8 'data' atom (type indicator 1) with the given 4-byte locale. */
    static byte[] encodeDataPayload(String value, long locale) {
        byte[] v = value.getBytes(ByteUtils.UTF_8);
        byte[] p = new byte[8 + v.length];
        ByteUtils.put32be(p, 0, 1);
        ByteUtils.put32be(p, 4, locale);
        System.arraycopy(v, 0, p, 8, v.length);
        return p;
    }

    static long localeOf(byte[] dataPayload) {
        return dataPayload != null && dataPayload.length >= 8 ? ByteUtils.u32be(dataPayload, 4) : 0;
    }

    /** Value of an ilst item (first 'data' child), null if unreadable. */
    static String itemValue(Mp4Box item) {
        if (item.isContainer()) {
            Mp4Box d = item.child(DATA);
            return d == null ? null : decodeDataPayload(d.payloadBytes());
        }
        return null;
    }

    /** Replaces the value of every 'data' child of an ilst item (adds one when there is none). */
    static void setItemValue(Mp4Box item, String value) {
        if (!item.isContainer()) {
            // unreadable item content: replace it with a single fresh 'data' atom
            item.setPayload(Mp4Box.newLeaf(DATA, encodeDataPayload(value, 0)).toBytes());
            return;
        }
        List<Mp4Box> datas = item.childrenOf(DATA);
        if (datas.isEmpty()) {
            item.addChild(Mp4Box.newLeaf(DATA, encodeDataPayload(value, 0)));
            return;
        }
        for (Mp4Box d : datas) {
            d.setPayload(encodeDataPayload(value, localeOf(d.payloadBytes())));
        }
    }

    /** New ilst item box with the given type and a single UTF-8 'data' atom. */
    static Mp4Box newItem(String type, String value) {
        Mp4Box item = Mp4Box.newContainer(type, null);
        item.children.add(Mp4Box.newLeaf(DATA, encodeDataPayload(value, 0)));
        return item;
    }

    // ------------------------------------------------------------------ user-data text atoms

    /** True when a user-data atom payload holds an iTunes-style 'data' atom instead of QuickTime text. */
    static boolean isDataStyle(byte[] p) {
        if (p.length < 16) return false;
        long sz = ByteUtils.u32be(p, 0);
        return sz >= 16 && sz <= p.length && "data".equals(ByteUtils.fourcc(p, 4));
    }

    /** First string of a QuickTime user-data text atom payload (null if malformed). */
    static String decodeText(byte[] p) {
        if (p == null) return null;
        if (isDataStyle(p)) {
            int sz = (int) ByteUtils.u32be(p, 0);
            byte[] dp = new byte[sz - 8];
            System.arraycopy(p, 8, dp, 0, dp.length);
            return decodeDataPayload(dp);
        }
        if (p.length < 4) return null;
        int len = ByteUtils.u16be(p, 0);
        int lang = ByteUtils.u16be(p, 2);
        if (4 + len > p.length) len = p.length - 4;
        String s;
        if (len >= 2 && (p[4] & 0xFF) == 0xFE && (p[5] & 0xFF) == 0xFF) {
            s = new String(p, 6, len - 2 - ((len - 2) % 2), UTF_16BE);
        } else if (lang < 0x400 && !isUtf8(p, 4, len)) {
            s = new String(p, 4, len, ByteUtils.ISO_8859_1); // Mac Roman approximation
        } else {
            s = new String(p, 4, len, ByteUtils.UTF_8);
        }
        return stripNul(s);
    }

    /** Language code of the first text entry, or -1. */
    static int textLang(byte[] p) {
        if (p == null || p.length < 4 || isDataStyle(p)) return -1;
        return ByteUtils.u16be(p, 2);
    }

    /**
     * New payload for a user-data text atom. Keeps the existing language code when it can
     * represent the value (packed ISO codes are UTF-8; Mac codes only for ASCII), else "eng".
     * An iTunes-style payload ('data' atom inside) keeps that style.
     */
    static byte[] encodeText(byte[] oldPayload, String value) {
        if (oldPayload != null && isDataStyle(oldPayload)) {
            int sz = (int) ByteUtils.u32be(oldPayload, 0);
            byte[] dp = new byte[sz - 8];
            System.arraycopy(oldPayload, 8, dp, 0, dp.length);
            return Mp4Box.newLeaf(DATA, encodeDataPayload(value, localeOf(dp))).toBytes();
        }
        byte[] v = value.getBytes(ByteUtils.UTF_8);
        if (v.length > 0xFFFF) {
            byte[] t = new byte[0xFFFF];
            System.arraycopy(v, 0, t, 0, t.length);
            v = t;
        }
        int lang = textLang(oldPayload);
        boolean ascii = isAscii(v);
        if (lang < 0 || lang == 0x7FFF || (lang < 0x400 && !ascii)) lang = LANG_ENG;
        byte[] p = new byte[4 + v.length];
        ByteUtils.put16be(p, 0, v.length);
        ByteUtils.put16be(p, 2, lang);
        System.arraycopy(v, 0, p, 4, v.length);
        return p;
    }

    // ------------------------------------------------------------------ misc

    static boolean isAscii(byte[] b) {
        for (byte x : b) {
            if ((x & 0x80) != 0) return false;
        }
        return true;
    }

    private static boolean isUtf8(byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            int c = b[i] & 0xFF;
            int n;
            if (c < 0x80) n = 0;
            else if (c >= 0xC2 && c <= 0xDF) n = 1;
            else if (c >= 0xE0 && c <= 0xEF) n = 2;
            else if (c >= 0xF0 && c <= 0xF4) n = 3;
            else return false;
            if (n > 0 && i + n >= end) return false;
            for (int k = 1; k <= n; k++) {
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += n + 1;
        }
        return true;
    }

    static String stripNul(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == 0) e--;
        return s.substring(0, e);
    }
}
