package com.metakamera.test;

import com.metakamera.core.EditReport;
import com.metakamera.core.IccIdentity;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.icc.IccProfileEditor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tests for {@link IccProfileEditor}: real profiles shipped with the system (lcms, colord,
 * icc-profiles-free), hand-built v2 ('desc') and v4 ('mluc', MD5 profile ID) profiles, odd tag
 * tables and corrupt input. Cross-checked with exiftool (reference reader, -validate) and with
 * Little CMS through Pillow's ImageCms (header + dmnd/dmdd reading, colour transforms).
 */
public final class IccProfileEditorTest {

    private static final String[] PROFILE_DIRS = {"/usr/share/color/icc", "/usr/share/color/icc/colord",
            "/usr/share/color/icc/ghostscript", "/usr/share/ghostscript"};

    // ================================================================== real profiles

    /** Header-only identity: every byte outside the patched header fields (and the ID) stays identical. */
    public static void testSystemProfilesHeaderOnly() throws Exception {
        List<File> profiles = systemProfiles();
        if (profiles.isEmpty()) {
            System.out.println("  (no system ICC profiles found, skipped)");
            return;
        }
        IccIdentity id = headerOnlyIdentity();
        List<File> origs = new ArrayList<File>();
        List<File> outs = new ArrayList<File>();
        int n = 0;
        for (File f : profiles) {
            byte[] in = T.read(f);
            byte[] inCopy = in.clone();
            EditReport r = new EditReport();
            byte[] out = IccProfileEditor.edit(in, id, r);
            T.check(Arrays.equals(in, inCopy), "input must not be modified: " + f);
            T.eq(in.length, out.length, "length " + f);
            T.eq(u32(in, 0), u32(out, 0), "size field " + f);
            for (int i = 0; i < in.length; i++) {
                boolean patched = (i >= 4 && i < 8) || (i >= 40 && i < 44) || (i >= 48 && i < 56)
                        || (i >= 80 && i < 100);
                if (!patched) T.check(in[i] == out[i], "byte " + i + " changed in " + f);
            }
            T.eq("appl", sig(out, 4), "cmm " + f);
            T.eq("APPL", sig(out, 40), "platform " + f);
            T.eq("APPL", sig(out, 48), "manufacturer " + f);
            T.check(isZero(out, 52, 4), "model zero " + f);
            T.eq("appl", sig(out, 80), "creator " + f);
            checkProfileId(in, out, f.getName());
            T.check(r.warnings.isEmpty(), "warnings " + r.warnings + " for " + f);
            if (!sig(in, 48).equals("APPL")) {
                T.check(hasChangeStarting(r, "ICC üretici: "), "report üretici " + r.changes);
            }
            File o = T.file("hdr-" + n + "-" + f.getName());
            File c = T.file("hdr-orig-" + n + "-" + f.getName());
            T.write(o, out);
            T.write(c, in);
            origs.add(c);
            outs.add(o);
            n++;
            // second run: nothing left to change
            EditReport r2 = new EditReport();
            T.check(IccProfileEditor.edit(out, id, r2) == out, "idempotent " + f);
            T.check(r2.changes.isEmpty() && r2.warnings.isEmpty(), "idempotent report " + r2);
        }
        Map<String, Map<String, String>> ex = exifMany(outs);
        for (File o : outs) {
            Map<String, String> m = ex.get(o.getName());
            T.check(m != null, "exiftool read " + o);
            T.eq("APPL", m.get("ICC-header:DeviceManufacturer"), "exiftool mfr " + o);
            T.eq("", m.get("ICC-header:DeviceModel"), "exiftool model " + o);
            T.eq("appl", m.get("ICC-header:ProfileCMMType"), "exiftool cmm " + o);
            T.eq("APPL", m.get("ICC-header:PrimaryPlatform"), "exiftool platform " + o);
            T.eq("appl", m.get("ICC-header:ProfileCreator"), "exiftool creator " + o);
        }
        checkNoNewValidateWarnings(origs, outs);
        int xf = checkLcmsEquivalent(origs, outs, null, null);
        T.check(xf == -1 || xf >= outs.size() / 2, "lcms transforms compared: " + xf + " of " + outs.size());
    }

    /** Full identity incl. dmnd/dmdd: tag data of other tags, order, sharing and transforms are preserved. */
    public static void testSystemProfilesFullEdit() throws Exception {
        List<File> profiles = systemProfiles();
        if (profiles.isEmpty()) return;
        IccIdentity id = T.appleSpec().icc;
        List<File> origs = new ArrayList<File>();
        List<File> outs = new ArrayList<File>();
        Map<String, boolean[]> hasTexts = new HashMap<String, boolean[]>();
        int n = 0;
        int rebuilt = 0;
        for (File f : profiles) {
            byte[] in = T.read(f);
            EditReport r = new EditReport();
            byte[] out = IccProfileEditor.edit(in, id, r);
            T.check(r.warnings.isEmpty(), "warnings " + r.warnings + " for " + f);
            List<Tag> ti = tags(in);
            boolean hasDmnd = find(ti, "dmnd") != null;
            boolean hasDmdd = find(ti, "dmdd") != null;
            if (hasDmnd || hasDmdd) rebuilt++;
            verifyStructure(in, out, f.getName(), true);
            checkProfileId(in, out, f.getName());
            List<Tag> to = tags(out);
            if (hasDmnd) {
                T.eq(type(in, find(ti, "dmnd")), type(out, find(to, "dmnd")), "dmnd type kept " + f);
                T.check(hasChangeStarting(r, "ICC üretici açıklaması (dmnd): "), "report dmnd " + r.changes);
            }
            if (hasDmdd) T.eq(type(in, find(ti, "dmdd")), type(out, find(to, "dmdd")), "dmdd type kept " + f);
            T.check(find(to, "dmnd") == null || hasDmnd, "dmnd must not be created " + f);
            T.check(find(to, "dmdd") == null || hasDmdd, "dmdd must not be created " + f);

            File o = T.file("full-" + n + "-" + f.getName());
            File c = T.file("full-orig-" + n + "-" + f.getName());
            T.write(o, out);
            T.write(c, in);
            origs.add(c);
            outs.add(o);
            hasTexts.put(o.getName(), new boolean[]{hasDmnd, hasDmdd});
            n++;

            EditReport r2 = new EditReport();
            T.check(IccProfileEditor.edit(out, id, r2) == out, "idempotent " + f + " " + r2);
            T.check(r2.changes.isEmpty(), "idempotent report " + r2);
        }
        if (profiles.size() >= 20) T.check(rebuilt >= 3, "expected several system profiles with dmnd/dmdd, got " + rebuilt);
        Map<String, Map<String, String>> ex = exifMany(outs);
        Map<String, Map<String, String>> exOrig = exifMany(origs);
        for (int i = 0; i < outs.size(); i++) {
            File o = outs.get(i);
            Map<String, String> m = ex.get(o.getName());
            Map<String, String> mo = exOrig.get(origs.get(i).getName());
            boolean[] ht = hasTexts.get(o.getName());
            T.eq("APPL", m.get("ICC-header:DeviceManufacturer"), "exiftool mfr " + o);
            T.eq(ht[0] ? "Apple" : null, m.get("ICC_Profile:DeviceMfgDesc"), "exiftool dmnd " + o);
            T.eq(ht[1] ? "iPhone 17 Pro Max" : null, m.get("ICC_Profile:DeviceModelDesc"), "exiftool dmdd " + o);
            T.eq(mo.get("ICC_Profile:ProfileDescription"), m.get("ICC_Profile:ProfileDescription"), "desc kept " + o);
        }
        checkNoNewValidateWarnings(origs, outs);
        int xf = checkLcmsEquivalent(origs, outs, "Apple", "iPhone 17 Pro Max");
        T.check(xf == -1 || xf >= outs.size() / 2, "lcms transforms compared: " + xf + " of " + outs.size());
    }

    /** inspect() agrees with exiftool on real profiles. */
    public static void testInspectSystemProfiles() throws Exception {
        List<File> profiles = systemProfiles();
        if (profiles.isEmpty()) return;
        List<File> copies = new ArrayList<File>();
        for (int i = 0; i < profiles.size(); i++) {
            File c = T.file("insp-" + i + "-" + profiles.get(i).getName());
            T.write(c, T.read(profiles.get(i)));
            copies.add(c);
        }
        Map<String, Map<String, String>> ex = exifMany(copies);
        for (File c : copies) {
            MediaInfo info = new MediaInfo();
            IccProfileEditor.inspect(T.read(c), info);
            Map<String, String> m = ex.get(c.getName());
            T.check(info.hasIcc, "hasIcc " + c);
            T.eq(m.get("ICC-header:DeviceManufacturer").trim(), info.iccManufacturer, "mfr " + c);
            String model = m.get("ICC-header:DeviceModel");
            if (model.length() > 0 && isPrintable(model)) T.eq(model.trim(), info.iccModel, "model " + c);
            T.eq(norm(m.get("ICC_Profile:ProfileDescription")), norm(info.iccDescription), "desc " + c);
            T.eq(norm(m.get("ICC_Profile:DeviceMfgDesc")), norm(info.iccManufacturerText), "dmnd " + c);
            T.eq(norm(m.get("ICC_Profile:DeviceModelDesc")), norm(info.iccModelText), "dmdd " + c);
        }
    }

    // ================================================================== hand-built profiles

    /** v2 profile with 'desc'-type dmnd/dmdd, shared TRC; growing, shrinking and non-ASCII texts. */
    public static void testHandBuiltV2DescTags() throws Exception {
        byte[] in = v2Profile("HUAWEI", "ELS-NX9", false);
        File inF = T.file("v2-in.icc");
        T.write(inF, in);
        Map<String, String> m0 = T.exif(inF, "-n");
        T.eq("HUAWEI", m0.get("ICC_Profile:DeviceMfgDesc"), "fixture dmnd");
        T.eq("HUAW", m0.get("ICC-header:DeviceManufacturer"), "fixture mfr");

        String[][] cases = {
                {"Apple", "iPhone 17 Pro Max"},                                     // grow
                {"A", "X"},                                                          // shrink
                {"Ünlü Şirket & Co", "Telefon \"Pro\" <Max> 'Ultra' ✓"},             // non-ASCII + specials
        };
        int k = 0;
        for (String[] cs : cases) {
            IccIdentity id = new IccIdentity();
            id.manufacturerSig = "APPL";
            id.manufacturerText = cs[0];
            id.modelText = cs[1];
            EditReport r = new EditReport();
            byte[] out = IccProfileEditor.edit(in, id, r);
            verifyStructure(in, out, "v2 case " + k, true);
            T.check(isZero(out, 84, 16), "v2 without ID keeps zero ID");
            List<Tag> to = tags(out);
            DescParts dm = descParts(out, find(to, "dmnd"));
            T.eq(ascii(cs[0]), dm.ascii, "dmnd ascii");
            T.eq(cs[0], dm.unicode, "dmnd unicode");
            T.eq(0, dm.scriptCount, "script count");
            DescParts dd = descParts(out, find(to, "dmdd"));
            T.eq(ascii(cs[1]), dd.ascii, "dmdd ascii");
            T.eq(cs[1], dd.unicode, "dmdd unicode");
            T.check(r.changes.contains("ICC üretici açıklaması (dmnd): HUAWEI → " + cs[0]), "report " + r.changes);
            T.check(r.changes.contains("ICC model açıklaması (dmdd): ELS-NX9 → " + cs[1]), "report " + r.changes);
            T.check(r.changes.contains("ICC üretici: HUAW → APPL"), "report " + r.changes);
            File o = T.file("v2-out-" + k + ".icc");
            T.write(o, out);
            Map<String, String> m = T.exif(o, "-n");
            T.eq(ascii(cs[0]), m.get("ICC_Profile:DeviceMfgDesc"), "exiftool dmnd " + k);
            T.eq(ascii(cs[1]), m.get("ICC_Profile:DeviceModelDesc"), "exiftool dmdd " + k);
            T.eq("APPL", m.get("ICC-header:DeviceManufacturer"), "exiftool mfr " + k);
            T.eq("ELS", m.get("ICC-header:DeviceModel").trim(), "model kept " + k);
            T.eq("Huawei P3 Profile", m.get("ICC_Profile:ProfileDescription"), "desc kept " + k);
            MediaInfo info = new MediaInfo();
            IccProfileEditor.inspect(out, info);
            T.eq(ascii(cs[0]).trim(), info.iccManufacturerText, "inspect dmnd");
            T.eq("APPL", info.iccManufacturer, "inspect mfr");
            T.check(IccProfileEditor.edit(out, id, new EditReport()) == out, "idempotent " + k);
            // lcms: same transforms; ASCII texts must read back exactly
            T.check(checkLcmsEquivalent(List.of(inF), List.of(o), k < 2 ? cs[0] : null, k < 2 ? cs[1] : null) != 0,
                    "lcms transform v2 " + k);
            k++;
        }
        // lcms reads the new texts (ASCII case)
        IccIdentity id = new IccIdentity();
        id.manufacturerText = "Apple";
        id.modelText = "iPhone 17 Pro Max";
        File o = T.file("v2-lcms.icc");
        T.write(o, IccProfileEditor.edit(in, id, new EditReport()));
        checkLcmsEquivalent(List.of(inF), List.of(o), "Apple", "iPhone 17 Pro Max");
    }

    /** v4 profile with multi-record 'mluc' dmnd and a valid MD5 profile ID. */
    public static void testHandBuiltV4MlucWithProfileId() throws Exception {
        byte[] in = v4Profile(true, true);
        T.check(Arrays.equals(md5Id(in), Arrays.copyOfRange(in, 84, 100)), "fixture ID valid");
        File inF = T.file("v4-in.icc");
        T.write(inF, in);
        T.eq("Samsung Electronics", T.exif(inF).get("ICC_Profile:DeviceMfgDesc"), "fixture dmnd");

        IccIdentity id = T.appleSpec().icc;
        EditReport r = new EditReport();
        byte[] out = IccProfileEditor.edit(in, id, r);
        verifyStructure(in, out, "v4", true);
        byte[] newId = Arrays.copyOfRange(out, 84, 100);
        T.check(Arrays.equals(md5Id(out), newId), "recomputed MD5 valid");
        T.check(!Arrays.equals(newId, Arrays.copyOfRange(in, 84, 100)), "ID changed");
        T.check(r.changes.contains("ICC profil kimliği (MD5) yeniden hesaplandı"), "report " + r.changes);
        List<Tag> to = tags(out);
        Tag dmnd = find(to, "dmnd");
        T.eq("mluc", type(out, dmnd), "mluc kept");
        T.eq(1L, u32(out, dmnd.off + 8), "single record");
        T.eq(12L, u32(out, dmnd.off + 12), "record size");
        T.eq("enUS", new String(out, dmnd.off + 16, 4, StandardCharsets.ISO_8859_1), "en-US");
        T.eq("Apple", new String(out, dmnd.off + 28, (int) u32(out, dmnd.off + 20), StandardCharsets.UTF_16BE), "text");

        File o = T.file("v4-out.icc");
        T.write(o, out);
        Map<String, String> m = T.exif(o);
        T.eq("Apple", m.get("ICC_Profile:DeviceMfgDesc"), "exiftool dmnd");
        T.eq("iPhone 17 Pro Max", m.get("ICC_Profile:DeviceModelDesc"), "exiftool dmdd");
        T.eq(hex(newId), m.get("ICC-header:ProfileID"), "exiftool ProfileID");
        T.eq("Display P3", m.get("ICC_Profile:ProfileDescription"), "desc kept");
        // independent MD5 check with python hashlib
        if (T.hasTool("python3")) {
            String py = "import sys,hashlib\nd=bytearray(open(sys.argv[1],'rb').read())\nid=bytes(d[84:100])\n"
                    + "d[44:48]=bytes(4);d[64:68]=bytes(4);d[84:100]=bytes(16)\n"
                    + "print('OK' if hashlib.md5(d).digest()==id else 'BAD')\n";
            File pf = T.file("md5check.py");
            T.write(pf, py.getBytes(StandardCharsets.UTF_8));
            T.eq("OK", T.run("python3", "-I", pf.getPath(), o.getPath()).trim(), "python md5");
        }
        checkNoNewValidateWarnings(List.of(inF), List.of(o));
        T.check(checkLcmsEquivalent(List.of(inF), List.of(o), "Apple", "iPhone 17 Pro Max") != 0, "lcms transform v4");

        // header-only change also refreshes the ID, in place
        IccIdentity h = new IccIdentity();
        h.manufacturerSig = "GOOG";
        byte[] out2 = IccProfileEditor.edit(in, h, new EditReport());
        T.eq(in.length, out2.length, "header-only keeps length");
        T.check(Arrays.equals(md5Id(out2), Arrays.copyOfRange(out2, 84, 100)), "header-only MD5 valid");
        for (int i = 100; i < in.length; i++) T.check(in[i] == out2[i], "header-only byte " + i);

        // a v4 profile with zero ID keeps it zero
        byte[] noId = v4Profile(false, true);
        byte[] out3 = IccProfileEditor.edit(noId, id, new EditReport());
        T.check(isZero(out3, 84, 16), "zero ID stays zero");
    }

    /** dmnd/dmdd are only created with addDescTagsIfMissing: 'desc' for v2, 'mluc' for v4, appended last. */
    public static void testAddDescTagsIfMissing() throws Exception {
        byte[][] fixtures = {v2Profile(null, null, false), v4Profile(true, false)};
        String[] types = {"desc", "mluc"};
        for (int k = 0; k < 2; k++) {
            byte[] in = fixtures[k];
            IccIdentity id = new IccIdentity();
            id.manufacturerText = "Apple";
            id.modelText = "iPhone 17 Pro Max";
            EditReport r = new EditReport();
            T.check(IccProfileEditor.edit(in, id, r) == in, "not created without flag");
            T.check(r.changes.isEmpty(), "no report " + r.changes);

            id.addDescTagsIfMissing = true;
            id.manufacturerText = "";       // "" = remove if present, never create
            byte[] onlyModel = IccProfileEditor.edit(in, id, new EditReport());
            T.check(find(tags(onlyModel), "dmnd") == null, "empty text never creates dmnd");
            T.check(find(tags(onlyModel), "dmdd") != null, "dmdd created");

            id.manufacturerText = "Apple";
            r = new EditReport();
            byte[] out = IccProfileEditor.edit(in, id, r);
            verifyStructure(in, out, "add " + k, false);
            List<Tag> ti = tags(in);
            List<Tag> to = tags(out);
            T.eq(ti.size() + 2, to.size(), "two tags added");
            T.eq("dmnd", to.get(to.size() - 2).sig, "dmnd appended");
            T.eq("dmdd", to.get(to.size() - 1).sig, "dmdd appended");
            T.eq(types[k], type(out, find(to, "dmnd")), "type by version");
            T.check(r.changes.contains("ICC üretici açıklaması (dmnd) eklendi: Apple"), "report " + r.changes);
            T.check(r.changes.contains("ICC model açıklaması (dmdd) eklendi: iPhone 17 Pro Max"), "report " + r.changes);
            if (k == 1) T.check(Arrays.equals(md5Id(out), Arrays.copyOfRange(out, 84, 100)), "MD5 after add");
            File inF = T.file("add-in-" + k + ".icc");
            File o = T.file("add-out-" + k + ".icc");
            T.write(inF, in);
            T.write(o, out);
            Map<String, String> m = T.exif(o);
            T.eq("Apple", m.get("ICC_Profile:DeviceMfgDesc"), "exiftool dmnd " + k);
            T.eq("iPhone 17 Pro Max", m.get("ICC_Profile:DeviceModelDesc"), "exiftool dmdd " + k);
            checkNoNewValidateWarnings(List.of(inF), List.of(o));
            checkLcmsEquivalent(List.of(inF), List.of(o), "Apple", "iPhone 17 Pro Max");
            T.check(IccProfileEditor.edit(out, id, new EditReport()) == out, "idempotent add " + k);
        }
    }

    /** "" removes existing dmnd/dmdd; data shared with 'desc' stays for 'desc'. */
    public static void testRemoveTagsKeepsSharedData() throws Exception {
        byte[] in = v2Profile("HUAWEI", "@desc", false);   // dmdd shares the 'desc' data
        List<Tag> ti = tags(in);
        T.eq(find(ti, "desc").off, find(ti, "dmdd").off, "fixture shares desc/dmdd");
        IccIdentity id = new IccIdentity();
        id.manufacturerText = "";
        id.modelText = "";
        EditReport r = new EditReport();
        byte[] out = IccProfileEditor.edit(in, id, r);
        List<Tag> to = tags(out);
        T.eq(ti.size() - 2, to.size(), "two tags removed");
        T.check(find(to, "dmnd") == null && find(to, "dmdd") == null, "removed");
        T.check(r.changes.contains("ICC üretici açıklaması (dmnd) kaldırıldı"), "report " + r.changes);
        T.check(r.changes.contains("ICC model açıklaması (dmdd) kaldırıldı"), "report " + r.changes);
        verifyStructure(in, out, "remove", false);
        File o = T.file("removed.icc");
        T.write(o, out);
        Map<String, String> m = T.exif(o);
        T.check(!m.containsKey("ICC_Profile:DeviceMfgDesc") && !m.containsKey("ICC_Profile:DeviceModelDesc"), "exiftool");
        T.eq("Huawei P3 Profile", m.get("ICC_Profile:ProfileDescription"), "desc kept");
        T.check(out.length < in.length, "smaller");
        T.check(IccProfileEditor.edit(out, id, new EditReport()) == out, "idempotent remove");

        // replace dmdd that shares data with desc: desc keeps its text, dmdd gets new data
        IccIdentity id2 = new IccIdentity();
        id2.modelText = "iPhone 17 Pro Max";
        byte[] out2 = IccProfileEditor.edit(in, id2, new EditReport());
        verifyStructure(in, out2, "split shared", true);
        File o2 = T.file("split.icc");
        T.write(o2, out2);
        Map<String, String> m2 = T.exif(o2);
        T.eq("Huawei P3 Profile", m2.get("ICC_Profile:ProfileDescription"), "desc text kept");
        T.eq("iPhone 17 Pro Max", m2.get("ICC_Profile:DeviceModelDesc"), "dmdd new");
    }

    /** Partially overlapping (non-identical) tag ranges keep their exact byte relationship. */
    public static void testPartiallyOverlappingTags() throws Exception {
        byte[] base = v2Profile("HUAWEI", "ELS-NX9", false);
        // make 'cprt' cover the 'wtpt' data and 8 bytes before it (overlap with different start/size)
        List<Tag> ti = tags(base);
        Tag wtpt = find(ti, "wtpt");
        Tag cprt = find(ti, "cprt");
        byte[] in = base.clone();
        int cprtIdx = ti.indexOf(cprt);
        long newOff = wtpt.off - 8;
        put32(in, 132 + 12 * cprtIdx + 4, newOff);
        put32(in, 132 + 12 * cprtIdx + 8, wtpt.size + 8);
        IccIdentity id = new IccIdentity();
        id.manufacturerText = "Apple";
        EditReport r = new EditReport();
        byte[] out = IccProfileEditor.edit(in, id, r);
        T.check(r.warnings.isEmpty(), "warnings " + r.warnings);
        List<Tag> to = tags(out);
        Tag w2 = find(to, "wtpt");
        Tag c2 = find(to, "cprt");
        T.eq((long) w2.off - 8, (long) c2.off, "overlap relationship kept");
        T.check(Arrays.equals(data(in, find(tags(in), "cprt")), data(out, c2)), "cprt bytes");
        T.check(Arrays.equals(data(in, find(tags(in), "wtpt")), data(out, w2)), "wtpt bytes");
        T.eq("Apple", T.exif(writeTmp("overlap.icc", out)).get("ICC_Profile:DeviceMfgDesc"), "exiftool");
    }

    /** Out-of-bounds tag table: only the header is patched, with a warning; everything else identical. */
    public static void testWeirdTableFallsBackToHeaderOnly() throws Exception {
        byte[] in = v2Profile("HUAWEI", "ELS-NX9", false);
        List<Tag> ti = tags(in);
        int idx = ti.indexOf(find(ti, "cprt"));
        put32(in, 132 + 12 * idx + 4, in.length - 4);      // cprt runs past the end
        IccIdentity id = T.appleSpec().icc;
        EditReport r = new EditReport();
        byte[] out = IccProfileEditor.edit(in, id, r);
        T.eq(in.length, out.length, "length");
        for (int i = 100; i < in.length; i++) T.check(in[i] == out[i], "byte " + i);
        T.eq("APPL", sig(out, 48), "header patched");
        T.check(!r.warnings.isEmpty() && r.warnings.get(0).contains("etiket tablosu"), "warning " + r.warnings);
        T.check(!r.changes.isEmpty(), "header changes reported");

        // truncated profile (declared size > data): header-only + warning
        byte[] full = v4Profile(true, true);
        byte[] trunc = Arrays.copyOf(full, full.length - 40);
        EditReport r2 = new EditReport();
        byte[] out2 = IccProfileEditor.edit(trunc, id, r2);
        T.eq(trunc.length, out2.length, "truncated length");
        T.eq("APPL", sig(out2, 48), "truncated header patched");
        for (int i = 0; i < trunc.length; i++) {
            boolean patched = (i >= 4 && i < 8) || (i >= 40 && i < 44) || (i >= 48 && i < 56) || (i >= 80 && i < 84);
            if (!patched) T.check(trunc[i] == out2[i], "truncated byte " + i);
        }
        T.check(r2.warnings.size() >= 1, "truncated warnings " + r2.warnings);
    }

    /** Bytes after the declared profile size (container padding) are preserved after a rebuild. */
    public static void testTrailingBytesPreserved() throws Exception {
        byte[] p = v2Profile("HUAWEI", "ELS-NX9", false);
        byte[] in = Arrays.copyOf(p, p.length + 3);
        in[p.length] = 1;
        in[p.length + 1] = 2;
        in[p.length + 2] = 3;
        IccIdentity id = new IccIdentity();
        id.manufacturerText = "Apple";
        byte[] out = IccProfileEditor.edit(in, id, new EditReport());
        int size = (int) u32(out, 0);
        T.eq(size + 3, out.length, "trailing kept");
        T.check(out[size] == 1 && out[size + 1] == 2 && out[size + 2] == 3, "trailing bytes");
        T.eq(0, size % 4, "size aligned");
    }

    /** Signature encoding: padding, truncation, "" = 0, non-ASCII -> '?', null = keep. */
    public static void testSignatureEncoding() throws Exception {
        byte[] in = v4Profile(false, false);
        IccIdentity id = new IccIdentity();
        id.manufacturerSig = "ab";
        id.modelSig = "ABCDEFG";
        id.cmmSig = "";
        id.platformSig = "Ş1";
        EditReport r = new EditReport();
        byte[] out = IccProfileEditor.edit(in, id, r);
        T.eq("ab  ", sig(out, 48), "padded");
        T.eq("ABCD", sig(out, 52), "truncated");
        T.check(isZero(out, 4, 4), "zero");
        T.eq("?1  ", sig(out, 40), "non-ascii");
        T.eq(sig(in, 80), sig(out, 80), "creator kept");
        T.check(r.changes.contains("ICC CMM: lcms → (boş)"), "report " + r.changes);
        T.check(r.changes.contains("ICC model: (boş) → ABCD"), "report " + r.changes);
        MediaInfo info = new MediaInfo();
        IccProfileEditor.inspect(out, info);
        T.eq("ab", info.iccManufacturer, "inspect trimmed");
        T.eq("ABCD", info.iccModel, "inspect model");
    }

    /** All-null identity, equal values, null identity: same array, empty report. */
    public static void testNoChangeReturnsSameArray() throws Exception {
        for (File f : firstProfiles(6)) {
            byte[] in = T.read(f);
            EditReport r = new EditReport();
            T.check(IccProfileEditor.edit(in, new IccIdentity(), r) == in, "all-null " + f);
            T.check(IccProfileEditor.edit(in, null, r) == in, "null id " + f);
            IccIdentity same = new IccIdentity();
            same.manufacturerSig = new String(in, 48, 4, StandardCharsets.ISO_8859_1).trim();
            if (isZero(in, 48, 4)) same.manufacturerSig = "";
            same.cmmSig = isZero(in, 4, 4) ? "" : new String(in, 4, 4, StandardCharsets.ISO_8859_1);
            T.check(IccProfileEditor.edit(in, same, r) == in, "same values " + f);
            T.check(r.changes.isEmpty() && r.warnings.isEmpty(), "report " + r);
        }
        byte[] v2 = v2Profile("HUAWEI", "ELS-NX9", false);
        IccIdentity id = new IccIdentity();
        id.manufacturerText = "HUAWEI";
        id.modelText = "ELS-NX9";
        id.manufacturerSig = "HUAW";
        T.check(IccProfileEditor.edit(v2, id, new EditReport()) == v2, "same texts");

        // 'desc' with an empty Unicode part (count 0, or 1 = just the terminator) and empty ScriptCode
        for (int uc = 0; uc <= 1; uc++) {
            ProfileBuilder pb = new ProfileBuilder(0x02100000);
            pb.tag("desc", desc("Plain"));
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes("desc".getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(new byte[4]);
            o.writeBytes(be32(6));
            o.writeBytes("Apple\0".getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(be32(0));
            o.writeBytes(be32(uc));
            o.writeBytes(new byte[2 * uc]);
            o.writeBytes(new byte[3 + 67]);
            pb.tag("dmnd", o.toByteArray());
            byte[] p = pb.build();
            IccIdentity a = new IccIdentity();
            a.manufacturerText = "Apple";
            EditReport r = new EditReport();
            T.check(IccProfileEditor.edit(p, a, r) == p, "ASCII-only desc equal to target, uc=" + uc + " " + r);
            a.manufacturerText = "Apple Inc.";
            byte[] out = IccProfileEditor.edit(p, a, r);
            T.eq("Apple Inc.", descParts(out, find(tags(out), "dmnd")).unicode, "rewritten with Unicode part");
        }
    }

    /** v2 profile whose reserved bytes 84..99 are not an MD5: they are kept as they are. */
    public static void testV2NonMd5ReservedBytesKept() throws Exception {
        byte[] in = v2Profile("HUAWEI", "ELS-NX9", false);
        for (int i = 84; i < 100; i++) in[i] = (byte) (i * 7);
        IccIdentity id = T.appleSpec().icc;
        byte[] out = IccProfileEditor.edit(in, id, new EditReport());
        for (int i = 84; i < 100; i++) T.eq(in[i], out[i], "reserved byte " + i);
        // a v2 profile with a VALID MD5 gets it recomputed
        byte[] withId = v2Profile("HUAWEI", "ELS-NX9", true);
        byte[] out2 = IccProfileEditor.edit(withId, id, new EditReport());
        T.check(Arrays.equals(md5Id(out2), Arrays.copyOfRange(out2, 84, 100)), "v2 MD5 recomputed");
    }

    // ================================================================== robustness

    /** Hand-made corrupt inputs: never a RuntimeException, always a non-null result. */
    public static void testCorruptInputs() throws Exception {
        IccIdentity id = T.appleSpec().icc;
        id.addDescTagsIfMissing = true;
        List<byte[]> bad = new ArrayList<byte[]>();
        bad.add(new byte[0]);
        bad.add(new byte[10]);
        bad.add(new byte[127]);
        bad.add(new byte[128]);
        byte[] good = v2Profile("HUAWEI", "ELS-NX9", false);
        byte[] noAcsp = good.clone();
        noAcsp[36] = 'x';
        bad.add(noAcsp);
        bad.add(Arrays.copyOf(good, 131));
        bad.add(Arrays.copyOf(good, 140));
        bad.add(withU32(good, 128, 0xFFFFFFFFL));        // huge tag count
        bad.add(withU32(good, 128, 0x7FFFFFFFL));
        bad.add(withU32(good, 0, 0));                     // size 0
        bad.add(withU32(good, 0, 0xFFFFFFFFL));           // size huge
        bad.add(withU32(good, 0, 100));                   // size < header
        List<Tag> tl = tags(good);
        for (int i = 0; i < tl.size(); i++) {
            int e = 132 + 12 * i;
            bad.add(withU32(good, e + 4, 0xFFFFFFF0L));   // offset overflow
            bad.add(withU32(good, e + 8, 0xFFFFFFF0L));   // size overflow
            bad.add(withU32(good, e + 8, 0));             // zero size
            bad.add(withU32(good, e + 4, 0));             // points at header
            Tag t = tl.get(i);
            bad.add(withU32(good, t.off + 8, 0xFFFFFFFFL)); // desc ascii count / mluc count huge
            bad.add(withU32(good, t.off + 8, 0x7FFFFFF0L));
            if (t.size > 20) bad.add(withU32(good, t.off + t.size - 20, 0xFFFFFFFFL));
        }
        byte[] v4 = v4Profile(true, true);
        for (Tag t : tags(v4)) {
            bad.add(withU32(v4, t.off + 8, 0xFFFFFFFFL));
            bad.add(withU32(v4, t.off + 12, 0));             // mluc record size 0
            bad.add(withU32(v4, t.off + 12, 0xFFFFFFFFL));
            bad.add(withU32(v4, t.off + 20, 0xFFFFFFFFL));   // mluc string length
            bad.add(withU32(v4, t.off + 24, 0xFFFFFFFFL));   // mluc string offset
        }
        int idx = 0;
        for (byte[] b : bad) {
            runSafely(b, id, "corrupt#" + idx++);
        }
        EditReport r = new EditReport();
        T.eq(0, IccProfileEditor.edit(null, id, r).length, "null profile");
        T.check(!r.warnings.isEmpty(), "null warns");
        IccProfileEditor.inspect(null, new MediaInfo());
        IccProfileEditor.inspect(new byte[5], null);
    }

    /** Random mutations/truncations of real and hand-built profiles. */
    public static void testFuzzNeverThrows() throws Exception {
        List<byte[]> seeds = new ArrayList<byte[]>();
        seeds.add(v2Profile("HUAWEI", "ELS-NX9", true));
        seeds.add(v4Profile(true, true));
        for (File f : firstProfiles(8)) {
            byte[] b = T.read(f);
            if (b.length < 40000) seeds.add(b);
        }
        Random rnd = new Random(12345);
        IccIdentity full = T.appleSpec().icc;
        full.addDescTagsIfMissing = true;
        IccIdentity rm = new IccIdentity();
        rm.manufacturerText = "";
        rm.modelText = "";
        int iterations = 0;
        for (byte[] seed : seeds) {
            List<Tag> tl = tags(seed);
            for (int it = 0; it < 250; it++) {
                byte[] b = seed.clone();
                int mode = rnd.nextInt(4);
                if (mode == 0) {
                    b = Arrays.copyOf(b, rnd.nextInt(b.length + 1));
                } else {
                    int muts = 1 + rnd.nextInt(6);
                    for (int m = 0; m < muts; m++) {
                        int pos;
                        if (mode == 1) pos = rnd.nextInt(Math.min(b.length, 132 + 12 * tl.size()));
                        else if (mode == 2 && !tl.isEmpty()) {
                            Tag t = tl.get(rnd.nextInt(tl.size()));
                            pos = t.off + rnd.nextInt(Math.min(t.size, 40));
                        } else pos = rnd.nextInt(b.length);
                        b[pos] = (byte) (rnd.nextInt(5) == 0 ? 0xFF : rnd.nextInt(256));
                    }
                }
                runSafely(b, (it & 1) == 0 ? full : rm, "fuzz#" + iterations);
                iterations++;
            }
        }
        T.check(iterations > 1000, "iterations " + iterations);
    }

    // ================================================================== verification helpers

    private static void runSafely(byte[] b, IccIdentity id, String what) {
        byte[] copy = b.clone();
        EditReport r = new EditReport();
        byte[] out;
        try {
            out = IccProfileEditor.edit(b, id, r);
            IccProfileEditor.inspect(b, new MediaInfo());
            if (out != null) IccProfileEditor.inspect(out, new MediaInfo());
        } catch (Throwable t) {
            throw new AssertionError(what + ": " + t, t);
        }
        T.check(out != null, what + ": null result");
        T.check(Arrays.equals(copy, b), what + ": input modified");
        T.check(!r.warnings.contains(IccProfileEditor.INTERNAL_ERROR_WARNING), what + ": internal error");
        if (out == b) return;
        boolean headerOnly = out.length == b.length
                && Arrays.equals(Arrays.copyOfRange(b, 100, b.length), Arrays.copyOfRange(out, 100, out.length));
        if (headerOnly) {
            for (int i = 0; i < 100; i++) {
                boolean patched = (i >= 4 && i < 8) || (i >= 40 && i < 44) || (i >= 48 && i < 56) || (i >= 80);
                if (!patched) T.check(b[i] == out[i], what + ": header byte " + i + " changed");
            }
            return;
        }
        // rebuilt: the new tag table must be sane
        long size = u32(out, 0);
        T.check(size >= 132 && size <= out.length && size % 4 == 0, what + ": rebuilt size " + size);
        long n = u32(out, 128);
        T.check(n <= (size - 132) / 12, what + ": rebuilt tag count");
        long[][] ranges = new long[(int) n][];
        for (int i = 0; i < n; i++) {
            long off = u32(out, 132 + 12 * i + 4);
            long sz = u32(out, 132 + 12 * i + 8);
            T.check(off >= 132 + 12 * n && off + sz <= size, what + ": rebuilt tag " + i + " " + off + "+" + sz);
            ranges[i] = new long[]{off, sz};
        }
        // tags that do not overlap any other tag start 4-byte aligned (overlapping ones keep the
        // relative position they had inside their shared block)
        for (int i = 0; i < n; i++) {
            boolean overlaps = false;
            for (int j = 0; j < n; j++) {
                if (i != j && ranges[i][0] < ranges[j][0] + ranges[j][1] && ranges[j][0] < ranges[i][0] + ranges[i][1]) {
                    overlaps = true;
                }
            }
            if (!overlaps) T.check(ranges[i][0] % 4 == 0, what + ": rebuilt tag " + i + " unaligned");
        }
    }

    /**
     * Checks a rebuilt (or patched) profile against the original: same tag order (except
     * removed/added dmnd/dmdd), identical data for every other tag, shared entries still shared,
     * 4-byte alignment, size field = profile length (multiple of 4 after a rebuild).
     */
    private static void verifyStructure(byte[] in, byte[] out, String what, boolean sameTagSet) {
        List<Tag> ti = tags(in);
        List<Tag> to = tags(out);
        long size = u32(out, 0);
        T.check(size <= out.length, what + ": size field");
        for (int i = 0; i < 128; i++) {
            boolean patched = (i < 8 && i >= 0) || (i >= 40 && i < 44) || (i >= 48 && i < 56) || (i >= 80 && i < 100);
            if (!patched) T.eq(in[i], out[i], what + ": header byte " + i);
        }
        boolean rebuilt = !Arrays.equals(Arrays.copyOfRange(in, 128, (int) Math.min(in.length, 132 + 12 * ti.size())),
                Arrays.copyOfRange(out, 128, (int) Math.min(out.length, 132 + 12 * to.size())));
        if (rebuilt) {
            T.eq(0L, size % 4, what + ": size multiple of 4");
            T.eq((long) out.length, size, what + ": size = length");
        }
        List<String> si = new ArrayList<String>();
        for (Tag t : ti) if (sameTagSet || !isText(t.sig)) si.add(t.sig);
        List<String> so = new ArrayList<String>();
        for (Tag t : to) if (sameTagSet || !isText(t.sig)) so.add(t.sig);
        T.eq(si, so, what + ": tag order");
        for (Tag t : to) {
            T.check(t.off + (long) t.size <= size, what + ": tag in bounds " + t.sig);
            if (rebuilt) T.eq(0, t.off % 4, what + ": aligned " + t.sig);
            T.check(t.off >= 132 + 12 * to.size(), what + ": after table " + t.sig);
            if (isText(t.sig)) continue;
            Tag o = find(ti, t.sig);
            T.check(Arrays.equals(data(in, o), data(out, t)), what + ": data of " + t.sig);
        }
        // sharing preserved
        for (int a = 0; a < ti.size(); a++) {
            for (int b = a + 1; b < ti.size(); b++) {
                Tag x = ti.get(a);
                Tag y = ti.get(b);
                if (isText(x.sig) || isText(y.sig)) continue;
                if (x.off == y.off && x.size == y.size) {
                    Tag x2 = find(to, x.sig);
                    Tag y2 = find(to, y.sig);
                    T.eq(x2.off, y2.off, what + ": shared " + x.sig + "/" + y.sig);
                }
            }
        }
        // no two distinct output ranges overlap unless they overlapped before
        for (int a = 0; a < to.size(); a++) {
            for (int b = a + 1; b < to.size(); b++) {
                Tag x = to.get(a);
                Tag y = to.get(b);
                boolean overlap = x.off < y.off + y.size && y.off < x.off + x.size;
                if (!overlap) continue;
                Tag xi = find(ti, x.sig);
                Tag yi = find(ti, y.sig);
                boolean before = xi != null && yi != null && xi.off < yi.off + yi.size && yi.off < xi.off + xi.size;
                T.check(before && !isText(x.sig) && !isText(y.sig), what + ": new overlap " + x.sig + "/" + y.sig);
            }
        }
    }

    private static boolean isText(String sig) {
        return sig.equals("dmnd") || sig.equals("dmdd");
    }

    /** ID must be: zero if it was zero; valid MD5 if it was valid; unchanged if v2 non-MD5. */
    private static void checkProfileId(byte[] in, byte[] out, String what) throws Exception {
        byte[] idIn = Arrays.copyOfRange(in, 84, 100);
        byte[] idOut = Arrays.copyOfRange(out, 84, 100);
        if (isZero(in, 84, 16)) {
            T.check(isZero(out, 84, 16), what + ": zero ID must stay zero");
        } else if (Arrays.equals(md5Id(in), idIn) || (in[8] & 0xFF) >= 4) {
            T.check(Arrays.equals(md5Id(out), idOut), what + ": ID must be valid MD5");
        } else {
            T.check(Arrays.equals(idIn, idOut), what + ": non-MD5 v2 bytes must be kept");
        }
    }

    /** exiftool -validate on the outputs must not report anything the originals did not. */
    private static void checkNoNewValidateWarnings(List<File> origs, List<File> outs) throws Exception {
        Map<String, List<String>> vo = validateMany(origs);
        Map<String, List<String>> vn = validateMany(outs);
        for (int i = 0; i < outs.size(); i++) {
            List<String> a = vo.get(origs.get(i).getName());
            List<String> b = vn.get(outs.get(i).getName());
            T.check(b != null, "validate output for " + outs.get(i));
            for (String w : b) {
                T.check(a != null && a.contains(w), "new exiftool warning for " + outs.get(i).getName() + ": " + w
                        + " (original: " + a + ")");
            }
        }
    }

    private static Map<String, List<String>> validateMany(List<File> files) throws IOException {
        List<String> cmd = new ArrayList<String>(List.of("exiftool", "-validate", "-warning", "-error", "-a", "-G1", "-s"));
        for (File f : files) cmd.add(f.getAbsolutePath());
        String outText = T.exec(cmd.toArray(new String[0])).out;
        Map<String, List<String>> m = new HashMap<String, List<String>>();
        List<String> cur = null;
        boolean single = files.size() == 1;
        if (single) {
            cur = new ArrayList<String>();
            m.put(files.get(0).getName(), cur);
        }
        for (String line : outText.split("\n")) {
            if (line.startsWith("======== ")) {
                cur = new ArrayList<String>();
                m.put(new File(line.substring(9).trim()).getName(), cur);
                continue;
            }
            if (cur == null || line.trim().isEmpty() || line.contains("image files read")) continue;
            if (line.contains("Validate") && line.contains(":")) continue;
            cur.add(line.replaceAll("\\s+", " ").trim());
        }
        return m;
    }

    /** One exiftool call for many files: file name -> tags (-n raw values). */
    private static Map<String, Map<String, String>> exifMany(List<File> files) throws IOException {
        Map<String, Map<String, String>> res = new HashMap<String, Map<String, String>>();
        for (int from = 0; from < files.size(); from += 40) {
            List<String> cmd = new ArrayList<String>(List.of("exiftool", "-a", "-G1", "-s", "-args", "-n",
                    "-charset", "UTF8", "-System:FileName", "-ICC-header:all", "-ICC_Profile:all"));
            for (File f : files.subList(from, Math.min(files.size(), from + 40))) cmd.add(f.getAbsolutePath());
            String out = T.run(cmd.toArray(new String[0]));
            Map<String, String> cur = null;
            for (String line : out.split("\n")) {
                if (!line.startsWith("-")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(1, eq);
                String v = line.substring(eq + 1);
                if (k.equals("System:FileName")) {
                    cur = new LinkedHashMap<String, String>();
                    res.put(v, cur);
                    continue;
                }
                if (cur != null && !cur.containsKey(k)) cur.put(k, v);
            }
        }
        return res;
    }

    // ------------------------------------------------------------------ Little CMS (Pillow ImageCms)

    private static final String LCMS_PY = String.join("\n",
            "import sys, hashlib",
            "from PIL import Image, ImageCms",
            "def hx(v):",
            "    if v is None: return '-'",
            "    if isinstance(v, bytes): return 'b' + v.hex()",
            "    return 's' + str(v).encode('utf-8', 'surrogatepass').hex()",
            "srgb = ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB'))",
            "img = Image.new('RGB', (256, 3))",
            "px = img.load()",
            "for x in range(256):",
            "    px[x, 0] = (x, 255 - x, (x * 7) % 256)",
            "    px[x, 1] = ((x * 3) % 256, x, 128)",
            "    px[x, 2] = (x, x, x)",
            "for path in sys.argv[1:]:",
            "    print('F\\t' + path)",
            "    try:",
            "        p = ImageCms.getOpenProfile(path)",
            "    except Exception as e:",
            "        print('open\\tE' + type(e).__name__)",
            "        continue",
            "    pr = p.profile",
            "    for k in ('manufacturer', 'model', 'header_manufacturer', 'header_model', 'profile_description', 'profile_id', 'version', 'device_class', 'xcolor_space'):",
            "        try:",
            "            print(k + '\\t' + hx(getattr(pr, k)))",
            "        except Exception as e:",
            "            print(k + '\\tE' + type(e).__name__)",
            "    mode = {'RGB': 'RGB', 'CMYK': 'CMYK', 'GRAY': 'L'}.get(pr.xcolor_space.strip())",
            "    if mode and pr.device_class in ('mntr', 'prtr', 'scnr'):",
            "        try:",
            "            t = ImageCms.buildTransform(srgb, p, 'RGB', mode)",
            "            o = ImageCms.applyTransform(img, t)",
            "            print('fwd\\t' + hashlib.md5(o.tobytes()).hexdigest())",
            "            t2 = ImageCms.buildTransform(p, srgb, mode, 'RGB')",
            "            o2 = ImageCms.applyTransform(o, t2)",
            "            print('rev\\t' + hashlib.md5(o2.tobytes()).hexdigest())",
            "        except Exception as e:",
            "            print('xf\\tE' + type(e).__name__ + ':' + str(e).replace('\\n', ' '))",
            "");

    /**
     * Little CMS must open the edited profiles, see the new header manufacturer and (when given)
     * the dmnd/dmdd texts, and produce bit-identical colour transforms to the originals.
     */
    private static int checkLcmsEquivalent(List<File> origs, List<File> outs, String expMfgText,
                                           String expModelText) throws Exception {
        if (!T.hasTool("python3")) return -1;
        if (T.exec("python3", "-I", "-c", "from PIL import ImageCms").exit != 0) return -1;
        File py = T.file("lcms_check.py");
        T.write(py, LCMS_PY.getBytes(StandardCharsets.UTF_8));
        List<String> cmd = new ArrayList<String>(List.of("python3", "-I", py.getAbsolutePath()));
        for (File f : origs) cmd.add(f.getAbsolutePath());
        for (File f : outs) cmd.add(f.getAbsolutePath());
        String text = T.run(cmd.toArray(new String[0]));
        Map<String, Map<String, String>> res = new HashMap<String, Map<String, String>>();
        Map<String, String> cur = null;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String k = line.substring(0, tab);
            String v = line.substring(tab + 1);
            if (k.equals("F")) {
                cur = new HashMap<String, String>();
                res.put(v, cur);
            } else if (cur != null) {
                cur.put(k, decodeHx(v));
            }
        }
        int transforms = 0;
        for (int i = 0; i < outs.size(); i++) {
            Map<String, String> a = res.get(origs.get(i).getAbsolutePath());
            Map<String, String> b = res.get(outs.get(i).getAbsolutePath());
            String what = outs.get(i).getName();
            T.check(a != null && b != null, "lcms output for " + what);
            if (a.containsKey("open")) continue;   // lcms cannot open the original either
            T.check(!b.containsKey("open"), "lcms cannot open edited " + what + ": " + b.get("open"));
            for (String k : new String[]{"fwd", "rev", "xf", "profile_description", "device_class", "xcolor_space", "version"}) {
                T.eq(a.get(k), b.get(k), "lcms " + k + " for " + what);
            }
            byte[] ob = T.read(outs.get(i));
            T.eq("s" + hexUtf8(new String(ob, 48, 4, StandardCharsets.ISO_8859_1)), "s" + hexUtf8(b.get("header_manufacturer")),
                    "lcms header_manufacturer " + what);
            List<Tag> to = tags(ob);
            if (expMfgText != null && find(to, "dmnd") != null) T.eq(expMfgText, trimOrNull(b.get("manufacturer")), "lcms dmnd " + what);
            if (expModelText != null && find(to, "dmdd") != null) T.eq(expModelText, trimOrNull(b.get("model")), "lcms dmdd " + what);
            T.eq("b" + hex(Arrays.copyOfRange(ob, 84, 100)), "b" + hex(hexToBytes(b.get("profile_id"))), "lcms profile id " + what);
            if (b.get("fwd") != null && b.get("rev") != null) transforms++;
        }
        return transforms;
    }

    private static String decodeHx(String v) {
        if (v.equals("-")) return null;
        if (v.startsWith("s")) return new String(hexToBytes(v.substring(1)), StandardCharsets.UTF_8);
        if (v.startsWith("b")) return v.substring(1);
        return v;  // error marker "E..."
    }

    // ================================================================== fixtures

    private static List<File> systemProfiles() {
        List<File> out = new ArrayList<File>();
        for (String d : PROFILE_DIRS) {
            File[] fs = new File(d).listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".icc") || n.endsWith(".icm")) && f.length() >= 132 && f.length() < 2_000_000) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    private static List<File> firstProfiles(int n) {
        List<File> all = systemProfiles();
        return all.subList(0, Math.min(n, all.size()));
    }

    private static IccIdentity headerOnlyIdentity() {
        IccIdentity id = new IccIdentity();
        id.manufacturerSig = "APPL";
        id.modelSig = "";
        id.cmmSig = "appl";
        id.platformSig = "APPL";
        id.creatorSig = "appl";
        return id;
    }

    /**
     * v2.1 RGB display profile like old Android/Huawei phones embed. dmnd/dmdd texts null = absent,
     * "@desc" = entry shares the 'desc' tag data. rTRC/gTRC/bTRC share one curve.
     */
    static byte[] v2Profile(String dmnd, String dmdd, boolean withId) {
        ProfileBuilder b = new ProfileBuilder(0x02100000);
        b.cmm = "HUAW";
        b.platform = "MSFT";
        b.mfr = "HUAW";
        b.model = "ELS ";
        b.creator = "HUAW";
        b.withId = withId;
        b.tag("desc", desc("Huawei P3 Profile"));
        if (dmnd != null) b.tag("dmnd", dmnd.startsWith("@") ? null : desc(dmnd), dmnd.startsWith("@") ? dmnd.substring(1) : null);
        b.tag("cprt", text("Copyright Huawei"));
        b.tag("wtpt", xyz(0.9642, 1.0, 0.8249));
        b.tag("rXYZ", xyz(0.4361, 0.2225, 0.0139));
        b.tag("gXYZ", xyz(0.3851, 0.7169, 0.0971));
        b.tag("bXYZ", xyz(0.1431, 0.0606, 0.7141));
        b.tag("rTRC", curv(2.2));
        b.tag("gTRC", null, "rTRC");
        b.tag("bTRC", null, "rTRC");
        if (dmdd != null) b.tag("dmdd", dmdd.startsWith("@") ? null : desc(dmdd), dmdd.startsWith("@") ? dmdd.substring(1) : null);
        return b.build();
    }

    /** v4.3 Display P3-like profile with multi-record mluc dmnd (en-US, de-DE) and dmdd. */
    static byte[] v4Profile(boolean withId, boolean withTexts) {
        ProfileBuilder b = new ProfileBuilder(0x04300000);
        b.cmm = "lcms";
        b.platform = "APPL";
        b.mfr = "SECE";
        b.model = null;
        b.creator = "SECE";
        b.withId = withId;
        b.flags = 0x00000001;   // "embedded" flag: must be zeroed for the MD5
        b.intent = 1;
        b.tag("desc", mluc(new String[][]{{"enUS", "Display P3"}}));
        b.tag("cprt", mluc(new String[][]{{"enUS", "Copyright Samsung"}}));
        b.tag("wtpt", xyz(0.9642, 1.0, 0.8249));
        b.tag("rXYZ", xyz(0.5151, 0.2412, -0.0011));
        b.tag("gXYZ", xyz(0.2919, 0.6922, 0.0419));
        b.tag("bXYZ", xyz(0.1571, 0.0666, 0.7841));
        b.tag("rTRC", para(2.4));
        b.tag("gTRC", null, "rTRC");
        b.tag("bTRC", null, "rTRC");
        b.tag("chad", sf32(new double[]{1.0479, 0.0229, -0.0502, 0.0296, 0.9904, -0.0171, -0.0092, 0.0151, 0.7519}));
        if (withTexts) {
            b.tag("dmnd", mluc(new String[][]{{"enUS", "Samsung Electronics"}, {"deDE", "Samsung Elektronik"}}));
            b.tag("dmdd", mluc(new String[][]{{"enUS", "Galaxy S24 Ultra"}}));
        }
        return b.build();
    }

    static final class ProfileBuilder {
        final int version;
        String cmm;
        String platform;
        String mfr;
        String model;
        String creator;
        boolean withId;
        int flags;
        int intent;
        final List<Object[]> tags = new ArrayList<Object[]>();

        ProfileBuilder(int version) {
            this.version = version;
        }

        void tag(String sig, byte[] data) {
            tag(sig, data, null);
        }

        void tag(String sig, byte[] data, String shareWith) {
            tags.add(new Object[]{sig, data, shareWith});
        }

        byte[] build() {
            int n = tags.size();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int base = 132 + 12 * n;
            Map<String, int[]> placed = new HashMap<String, int[]>();
            int[][] entries = new int[n][];
            for (int i = 0; i < n; i++) {
                Object[] t = tags.get(i);
                if (t[2] != null) {
                    entries[i] = placed.get((String) t[2]);
                    continue;
                }
                byte[] d = (byte[]) t[1];
                while ((base + data.size()) % 4 != 0) data.write(0);
                int off = base + data.size();
                data.write(d, 0, d.length);
                entries[i] = new int[]{off, d.length};
                placed.put((String) t[0], entries[i]);
            }
            while ((base + data.size()) % 4 != 0) data.write(0);
            byte[] p = new byte[base + data.size()];
            put32(p, 0, p.length);
            putSig(p, 4, cmm);
            put32(p, 8, version & 0xFFFFFFFFL);
            putSig(p, 12, "mntr");
            putSig(p, 16, "RGB ");
            putSig(p, 20, "XYZ ");
            p[24] = 0x07;
            p[25] = (byte) 0xE9;    // 2025
            p[27] = 3;
            p[29] = 14;
            putSig(p, 36, "acsp");
            putSig(p, 40, platform);
            put32(p, 44, flags);
            putSig(p, 48, mfr);
            putSig(p, 52, model);
            put32(p, 64, intent);
            put32(p, 68, 0x0000F6D6L);
            put32(p, 72, 0x00010000L);
            put32(p, 76, 0x0000D32DL);
            putSig(p, 80, creator);
            put32(p, 128, n);
            for (int i = 0; i < n; i++) {
                putSig(p, 132 + 12 * i, (String) tags.get(i)[0]);
                put32(p, 132 + 12 * i + 4, entries[i][0]);
                put32(p, 132 + 12 * i + 8, entries[i][1]);
            }
            byte[] d = data.toByteArray();
            System.arraycopy(d, 0, p, base, d.length);
            if (withId) System.arraycopy(md5Id(p), 0, p, 84, 16);
            return p;
        }
    }

    static byte[] desc(String s) {
        byte[] a = s.getBytes(StandardCharsets.ISO_8859_1);
        byte[] u = s.getBytes(StandardCharsets.UTF_16BE);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("desc".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        o.writeBytes(be32(a.length + 1));
        o.writeBytes(a);
        o.write(0);
        o.writeBytes(be32(0));
        o.writeBytes(be32(s.length() + 1));
        o.writeBytes(u);
        o.writeBytes(new byte[2]);
        o.writeBytes(new byte[2]);
        o.write(a.length + 1 > 67 ? 0 : a.length + 1);
        byte[] sc = new byte[67];
        System.arraycopy(a, 0, sc, 0, Math.min(a.length, 66));
        o.writeBytes(sc);
        return o.toByteArray();
    }

    static byte[] mluc(String[][] recs) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("mluc".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        o.writeBytes(be32(recs.length));
        o.writeBytes(be32(12));
        int off = 16 + 12 * recs.length;
        List<byte[]> strs = new ArrayList<byte[]>();
        for (String[] r : recs) {
            byte[] s = r[1].getBytes(StandardCharsets.UTF_16BE);
            o.writeBytes(r[0].getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(be32(s.length));
            o.writeBytes(be32(off));
            off += s.length;
            strs.add(s);
        }
        for (byte[] s : strs) o.writeBytes(s);
        return o.toByteArray();
    }

    static byte[] text(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("text".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        o.write(0);
        return o.toByteArray();
    }

    static byte[] xyz(double x, double y, double z) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("XYZ ".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        o.writeBytes(be32(Math.round(x * 65536)));
        o.writeBytes(be32(Math.round(y * 65536)));
        o.writeBytes(be32(Math.round(z * 65536)));
        return o.toByteArray();
    }

    static byte[] sf32(double[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("sf32".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        for (double d : v) o.writeBytes(be32(Math.round(d * 65536)));
        return o.toByteArray();
    }

    static byte[] curv(double gamma) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("curv".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        o.writeBytes(be32(1));
        int g = (int) Math.round(gamma * 256);
        o.write(g >> 8);
        o.write(g & 0xFF);
        return o.toByteArray();
    }

    static byte[] para(double gamma) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("para".getBytes(StandardCharsets.ISO_8859_1));
        o.writeBytes(new byte[4]);
        o.writeBytes(new byte[]{0, 0, 0, 0});
        o.writeBytes(be32(Math.round(gamma * 65536)));
        return o.toByteArray();
    }

    // ================================================================== byte helpers (independent of core)

    static final class Tag {
        String sig;
        int off;
        int size;
    }

    static List<Tag> tags(byte[] p) {
        List<Tag> l = new ArrayList<Tag>();
        long n = u32(p, 128);
        for (int i = 0; i < n; i++) {
            Tag t = new Tag();
            t.sig = new String(p, 132 + 12 * i, 4, StandardCharsets.ISO_8859_1);
            t.off = (int) u32(p, 132 + 12 * i + 4);
            t.size = (int) u32(p, 132 + 12 * i + 8);
            l.add(t);
        }
        return l;
    }

    static Tag find(List<Tag> l, String sig) {
        for (Tag t : l) if (t.sig.equals(sig)) return t;
        return null;
    }

    static byte[] data(byte[] p, Tag t) {
        return Arrays.copyOfRange(p, t.off, t.off + t.size);
    }

    static String type(byte[] p, Tag t) {
        return new String(p, t.off, 4, StandardCharsets.ISO_8859_1);
    }

    static final class DescParts {
        String ascii;
        String unicode;
        int scriptCount;
    }

    static DescParts descParts(byte[] p, Tag t) {
        DescParts d = new DescParts();
        int o = t.off;
        T.eq("desc", type(p, t), "desc type");
        int ac = (int) u32(p, o + 8);
        d.ascii = new String(p, o + 12, ac - 1, StandardCharsets.ISO_8859_1);
        T.eq((byte) 0, p[o + 12 + ac - 1], "ascii NUL");
        int u = o + 12 + ac;
        int uc = (int) u32(p, u + 4);
        d.unicode = new String(p, u + 8, (uc - 1) * 2, StandardCharsets.UTF_16BE);
        T.eq(0, (p[u + 8 + (uc - 1) * 2] | p[u + 9 + (uc - 1) * 2]), "unicode NUL");
        int s = u + 8 + uc * 2;
        d.scriptCount = p[s + 2] & 0xFF;
        T.eq((long) (s + 3 + 67 - o), (long) t.size, "desc size");
        return d;
    }

    static String ascii(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }

    /** Independent ICC profile ID computation. */
    static byte[] md5Id(byte[] p) {
        try {
            int size = (int) u32(p, 0);
            byte[] c = Arrays.copyOf(p, size);
            Arrays.fill(c, 44, 48, (byte) 0);
            Arrays.fill(c, 64, 68, (byte) 0);
            Arrays.fill(c, 84, 100, (byte) 0);
            return MessageDigest.getInstance("MD5").digest(c);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static byte[] be32(long v) {
        byte[] b = new byte[4];
        put32(b, 0, v);
        return b;
    }

    static void putSig(byte[] b, int off, String s) {
        if (s == null) return;
        byte[] x = s.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(x, 0, b, off, 4);
    }

    static byte[] withU32(byte[] b, int off, long v) {
        byte[] c = b.clone();
        if (off >= 0 && off + 4 <= c.length) put32(c, off, v);
        return c;
    }

    static String sig(byte[] b, int off) {
        return new String(b, off, 4, StandardCharsets.ISO_8859_1);
    }

    static boolean isZero(byte[] b, int off, int len) {
        for (int i = 0; i < len; i++) if (b[off + i] != 0) return false;
        return true;
    }

    static boolean hasChangeStarting(EditReport r, String prefix) {
        for (String c : r.changes) if (c.startsWith(prefix)) return true;
        return false;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static String hexUtf8(String s) {
        return s == null ? "null" : hex(s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hexToBytes(String h) {
        if (h == null) return new byte[0];
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static boolean isPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c >= 0x7F) return false;
        }
        return true;
    }

    /** exiftool -args prints newlines as '.': normalise both sides the same way. */
    static String norm(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\r\\n]", ".").replaceAll("^[.\\s]+|[.\\s]+$", "");
    }

    static String trimOrNull(String s) {
        return s == null ? null : s.trim();
    }

    static File writeTmp(String name, byte[] data) throws IOException {
        File f = T.file(name);
        T.write(f, data);
        return f;
    }
}
