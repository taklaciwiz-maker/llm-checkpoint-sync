package com.metakamera.core.isobmff;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.icc.IccProfileEditor;
import com.metakamera.core.tiff.ExifTiff;
import com.metakamera.core.xmp.XmpEditor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * HEIF / HEIC / AVIF still-image metadata editor.
 *
 * <p>Edits the device identity in the Exif item ('Exif'), XMP items ('mime' with
 * application/rdf+xml, plain or deflate-compressed) and every ICC profile stored in a 'colr'
 * property ('prof' / 'rICC'); creates a minimal Exif item linked to the primary image when the
 * file has none (EditSpec.createExifIfMissing). Image data, unknown boxes and every other byte
 * are copied verbatim; old metadata bytes that are replaced or relocated are zero-filled so no
 * vendor strings survive. See {@link HeifPlan} for the layout strategy.
 */
public final class HeifEditor {

    private HeifEditor() {
    }

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        HeifPlan plan;
        try {
            plan = HeifPlan.plan(src, spec);
        } catch (RuntimeException e) {
            UnsupportedFormatException u = new UnsupportedFormatException("HEIF dosyası bozuk; düzenlenemedi");
            u.initCause(e);
            throw u;
        }
        if (report != null) {
            report.changes.addAll(plan.rep.changes);
            report.warnings.addAll(plan.rep.warnings);
        }
        try {
            plan.write(out, listener);
        } catch (RuntimeException e) {
            UnsupportedFormatException u = new UnsupportedFormatException("HEIF dosyası yazılamadı (bozuk veri)");
            u.initCause(e);
            throw u;
        }
    }

    /**
     * Fills make/model/software/lens/date (Exif, then XMP for missing fields), ICC header and
     * description fields (primary image's 'colr' profile first), hasExif/hasXmp/hasIcc,
     * gpsStatus and width/height from the primary item's 'ispe'.
     */
    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
        if (info == null) return;
        HeifFile f;
        try {
            f = HeifFile.open(src);
        } catch (RuntimeException e) {
            throw new UnsupportedFormatException("HEIF dosyası bozuk");
        }
        try {
            inspectMeta(f, info);
        } catch (RuntimeException e) {
            // best effort
        } catch (IOException e) {
            // best effort: metadata items that cannot be read are reported as absent
        }
        if (info.gpsStatus == null) info.gpsStatus = "yok";
    }

    private static void inspectMeta(HeifFile f, MediaInfo info) throws IOException {
        HeifMeta m = f.meta;
        long primary = m.primaryId;
        int[] wh = HeifPlan.ispe(m, primary);
        if (wh != null) {
            info.width = wh[0];
            info.height = wh[1];
        }
        byte[] icc = primaryIcc(m, primary);
        if (icc != null) IccProfileEditor.inspect(icc, info);

        byte[] exif = itemData(f, HeifPlan.KIND_EXIF, primary);
        if (exif != null) {
            int ts = HeifPlan.tiffStart(exif);
            if (ts >= 0) ExifTiff.inspect(Arrays.copyOfRange(exif, ts, exif.length), info);
        }
        HeifMeta.Infe xi = pick(m, HeifPlan.KIND_XMP, primary, f);
        if (xi != null) {
            byte[] x = read(f, xi);
            if (x != null) {
                String enc = xi.contentEncoding == null ? "" : xi.contentEncoding.trim().toLowerCase(Locale.ROOT);
                if ("deflate".equals(enc)) {
                    byte[] d = HeifPlan.inflate(x, false);
                    x = d != null ? d : HeifPlan.inflate(x, true);
                } else if (enc.length() > 0) {
                    x = null;
                }
            }
            if (x != null) {
                int n = x.length;
                while (n > 0 && x[n - 1] == 0) n--;
                String s = HeifPlan.decodeUtf8(x, n);
                if (s != null) XmpEditor.inspect(s, info);
            }
        }
    }

    /** ICC profile of the primary image ('colr' prof/rICC), else the first one in ipco. */
    private static byte[] primaryIcc(HeifMeta m, long primary) {
        List<Integer> l = m.assoc.get(primary);
        if (l != null) {
            for (int i = 0; i < l.size(); i++) {
                byte[] p = iccOf(m, m.property(l.get(i)));
                if (p != null) return p;
            }
        }
        for (int i = 0; i < m.ipcoProps.size(); i++) {
            byte[] p = iccOf(m, m.ipcoProps.get(i));
            if (p != null) return p;
        }
        return null;
    }

    private static byte[] iccOf(HeifMeta m, BoxHeader b) {
        if (b == null || !"colr".equals(b.type) || b.payloadSize() < 4) return null;
        int ps = (int) b.payloadOffset();
        String ct = ByteUtils.fourcc(m.raw, ps);
        if (!"prof".equals(ct) && !"rICC".equals(ct)) return null;
        return Arrays.copyOfRange(m.raw, ps + 4, (int) b.end());
    }

    /** The item of {@code kind} describing the primary image ('cdsc'), else the first readable one. */
    private static HeifMeta.Infe pick(HeifMeta m, int kind, long primary, HeifFile f) {
        HeifMeta.Infe first = null;
        for (int i = 0; i < m.infes.size(); i++) {
            HeifMeta.Infe e = m.infes.get(i);
            if (HeifPlan.kindOf(e) != kind || e.protectionIndex != 0) continue;
            if (m.iloc == null || m.iloc.find(e.id) == null || f.ranges(m.iloc.find(e.id)) == null) continue;
            if (m.hasRef("cdsc", e.id, primary)) return e;
            if (first == null) first = e;
        }
        return first;
    }

    private static byte[] itemData(HeifFile f, int kind, long primary) throws IOException {
        HeifMeta.Infe e = pick(f.meta, kind, primary, f);
        return e == null ? null : read(f, e);
    }

    private static byte[] read(HeifFile f, HeifMeta.Infe e) throws IOException {
        HeifIloc.Item it = f.meta.iloc.find(e.id);
        long[][] r = f.ranges(it);
        if (r == null || HeifFile.total(r) > HeifFile.ITEM_MAX) return null;
        try {
            return f.read(it.constructionMethod(), r);
        } catch (UnsupportedFormatException ex) {
            return null;
        }
    }
}
