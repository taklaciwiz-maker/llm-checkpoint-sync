package com.metakamera.test;

import com.metakamera.core.ByteArraySource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.FileChannelSource;
import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.MetadataEditor;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.isobmff.Mp4Editor;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Tests for {@link Mp4Editor} (MP4 / MOV / 3GP, QuickTime Keys + user data, offsets, fragments). */
public final class Mp4EditorTest {

    private static final String MAKE = "Apple";
    private static final String MODEL = "iPhone 18 Pro Max";
    private static final String SOFTWARE = "26.1";

    private static boolean tools() {
        return T.hasTool("ffmpeg") && T.hasTool("ffprobe") && T.hasTool("exiftool");
    }

    private static EditSpec spec() {
        EditSpec s = T.appleSpec();
        s.make = MAKE;
        s.model = MODEL;
        s.software = SOFTWARE;
        return s;
    }

    private static EditSpec nullSpec() {
        EditSpec s = new EditSpec();
        s.removeMakerNote = false;
        s.clearSerials = false;
        s.editIcc = false;
        s.editXmp = false;
        s.createExifIfMissing = false;
        return s;
    }

    // =================================================================== fixtures

    private static final String[] AV = {"-f", "lavfi", "-i", "testsrc=size=160x120:rate=15", "-f", "lavfi", "-i",
            "sine=frequency=440:sample_rate=44100", "-t", "2", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-c:a", "aac"};

    /** Generates (once per run) an ffmpeg fixture. */
    private static File ff(String name, String... extra) throws IOException {
        File f = T.file(name);
        if (f.exists() && f.length() > 0) return f;
        List<String> cmd = new ArrayList<String>(Arrays.asList("ffmpeg", "-y", "-v", "error"));
        cmd.addAll(Arrays.asList(AV));
        cmd.addAll(Arrays.asList(extra));
        cmd.add(f.getPath());
        T.run(cmd.toArray(new String[0]));
        return f;
    }

    private static File mp4End() throws IOException {
        return ff("mp4-end.mp4");
    }

    private static File mp4Fast() throws IOException {
        return ff("mp4-fast.mp4", "-movflags", "+faststart");
    }

    private static File mov() throws IOException {
        return ff("udta.mov", "-metadata", "make=HUAWEI", "-metadata", "model=ALN-AL00");
    }

    private static File keysFfmpeg() throws IOException {
        return ff("keys-ffmpeg.mp4", "-movflags", "use_metadata_tags+faststart",
                "-metadata", "com.apple.quicktime.make=HUAWEI", "-metadata", "com.apple.quicktime.model=ALN-AL00",
                "-metadata", "com.android.version=14", "-metadata", "com.android.capture.fps=30.000000");
    }

    /** QuickTime-style moov/meta (no version/flags) written by exiftool; com.android.version is key #1. */
    private static File keysQt() throws IOException {
        File f = T.file("keys-qt.mp4");
        if (f.exists()) return f;
        T.run("exiftool", "-q", "-o", f.getPath(), "-Keys:AndroidVersion=13", "-Keys:Make=HUAWEI",
                "-Keys:GPSCoordinates=41.0082, 28.9784, 30", "-Keys:Model=ALN-AL00", "-Keys:Software=HarmonyOS",
                "-Keys:CreationDate=2024:05:06 10:08:09+03:00", mp4Fast().getPath());
        return f;
    }

    private static File udtaExiftool() throws IOException {
        File f = T.file("udta-exiftool.mp4");
        if (f.exists()) return f;
        T.run("exiftool", "-q", "-o", f.getPath(), "-UserData:Make=HUAWEI", "-UserData:Model=ALN-AL00",
                "-UserData:SoftwareVersion=HarmonyOS 4.2", mp4Fast().getPath());
        return f;
    }

    // =================================================================== verification helpers

    private static void sameMedia(File in, File out) throws IOException {
        T.eq("", T.ffmpegDecodeErrors(out), "ffmpeg decode errors of " + out.getName());
        String a = T.run("ffmpeg", "-v", "error", "-i", in.getPath(), "-map", "0", "-c", "copy", "-f", "framemd5", "-");
        String b = T.run("ffmpeg", "-v", "error", "-i", out.getPath(), "-map", "0", "-c", "copy", "-f", "framemd5", "-");
        T.check(a.length() > 100, "framemd5 output too short: " + a);
        T.eq(a, b, "per-packet sample hashes of " + out.getName());
        String[] probe = {"ffprobe", "-v", "error", "-show_entries",
                "format=duration:stream=index,codec_type,codec_name,duration,nb_frames,width,height,sample_rate",
                "-of", "compact"};
        T.eq(T.run(cat(probe, in.getPath())), T.run(cat(probe, out.getPath())), "ffprobe streams/duration");
    }

    private static String[] cat(String[] a, String b) {
        String[] r = Arrays.copyOf(a, a.length + 1);
        r[a.length] = b;
        return r;
    }

    private static void spoofed(File out, boolean expectUserDataSoftware) throws IOException {
        Map<String, String> x = T.exif(out);
        T.eq(MAKE, x.get("Keys:Make"), "Keys:Make");
        T.eq(MODEL, x.get("Keys:Model"), "Keys:Model");
        T.eq(SOFTWARE, x.get("Keys:Software"), "Keys:Software");
        T.eq(MAKE, x.get("UserData:Make"), "UserData:Make");
        T.eq(MODEL, x.get("UserData:Model"), "UserData:Model");
        if (expectUserDataSoftware) T.eq(SOFTWARE, x.get("UserData:SoftwareVersion"), "UserData:SoftwareVersion");
        T.check(!x.containsKey("Keys:AndroidVersion"), "AndroidVersion must be removed");
        for (Map.Entry<String, String> e : x.entrySet()) {
            T.notContains(e.getValue(), "HUAWEI", e.getKey());
            T.notContains(e.getValue(), "ALN-AL00", e.getKey());
        }
        MediaInfo info = T.inspect(out);
        T.eq(MAKE, info.make, "inspect make");
        T.eq(MODEL, info.model, "inspect model");
        T.eq(SOFTWARE, info.software, "inspect software");
    }

    private static File out(File in, String suffix) {
        String n = in.getName();
        int dot = n.lastIndexOf('.');
        return T.file(n.substring(0, dot) + "-" + suffix + n.substring(dot));
    }

    private static String changes(EditReport r) {
        return r.changes.toString();
    }

    // =================================================================== real files

    public static void testMoovAtEnd() throws Exception {
        if (!tools()) return;
        File in = mp4End();
        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.eq(MediaFormat.MP4, r.format, "format");
        T.contains(changes(r), "Video Make (Keys): (yok) → Apple", "report");
        T.contains(changes(r), "Video Model (UserData): (yok) → " + MODEL, "report");
        T.eq(out.length(), r.outputLength, "outputLength");
        spoofed(out, false);
        sameMedia(in, out);
        // everything before moov (ftyp, free, mdat) is untouched
        byte[] a = T.read(in);
        byte[] b = T.read(out);
        Box moov = find(a, "moov");
        T.check(Arrays.equals(Arrays.copyOf(a, moov.off), Arrays.copyOf(b, moov.off)), "bytes before moov identical");
        MediaInfo info = T.inspect(out);
        T.eq(160, info.width, "width");
        T.eq(120, info.height, "height");
        // created moov/meta is QuickTime style (no version/flags) with hdlr 'mdta'
        Box meta = find(b, "moov", "meta");
        T.check(meta != null, "moov/meta created");
        T.check(ByteUtils.u32be(b, meta.payload()) != 0, "moov/meta must not have version/flags");
        Box hdlr = find(b, "moov", "meta", "hdlr");
        T.eq("mdta", ByteUtils.fourcc(b, hdlr.payload() + 8), "hdlr handler");
    }

    public static void testFaststartShiftsChunkOffsets() throws Exception {
        if (!tools()) return;
        File in = mp4Fast();
        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.contains(changes(r), "ofset", "report mentions offset update");
        spoofed(out, false);
        sameMedia(in, out);
        byte[] a = T.read(in);
        byte[] b = T.read(out);
        long delta = find(b, "moov").size - find(a, "moov").size;
        T.check(delta > 0, "moov grew");
        T.eq((long) a.length + delta, (long) b.length, "output length");
        long[] oa = chunkOffsets(a);
        long[] ob = chunkOffsets(b);
        T.eq(oa.length, ob.length, "chunk count");
        for (int i = 0; i < oa.length; i++) T.eq(oa[i] + delta, ob[i], "chunk offset " + i);
    }

    public static void testMovUserData() throws Exception {
        if (!tools()) return;
        File in = mov();
        T.eq("HUAWEI", T.exif(in).get("UserData:Make"), "fixture UserData:Make");
        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.eq(MediaFormat.MOV, r.format, "format");
        T.contains(changes(r), "Video Make (UserData): HUAWEI → Apple", "report");
        spoofed(out, true);
        Map<String, String> x = T.exif(out);
        T.check(!x.containsKey("UserData:Make#2"), "no duplicate ©mak");
        T.check(!x.containsKey("UserData:Model#2"), "no duplicate ©mod");
        sameMedia(in, out);
    }

    public static void test3gp() throws Exception {
        if (!tools()) return;
        File in = T.file("v.3gp");
        if (!in.exists()) {
            T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=176x144:rate=15", "-f", "lavfi",
                    "-i", "sine=sample_rate=16000", "-t", "2", "-c:v", "h263", "-c:a", "aac", "-ac", "1", in.getPath());
        }
        File out = out(in, "out");
        T.edit(in, out, spec());
        spoofed(out, false);
        sameMedia(in, out);
        T.eq(MediaFormat.MP4, T.inspect(out).format, "3gp detected as MP4 family");
    }

    public static void testFfmpegKeysInUdtaMeta() throws Exception {
        if (!tools()) return;
        File in = keysFfmpeg();
        Map<String, String> x0 = T.exif(in);
        T.eq("HUAWEI", x0.get("Keys:Make"), "fixture Keys:Make");
        T.eq("14", x0.get("Keys:AndroidVersion"), "fixture Keys:AndroidVersion");
        T.eq("HUAWEI", T.inspect(in).make, "inspect fixture make");
        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.contains(changes(r), "com.android.version anahtarı kaldırıldı", "report");
        T.contains(changes(r), "HUAWEI → Apple", "report");
        spoofed(out, false);
        Map<String, String> x = T.exif(out);
        T.check(x.containsKey("Keys:Encoder"), "unrelated key kept");
        T.check(!x.containsKey("Keys:Make#2"), "single key table: " + x);
        // ffmpeg keeps only one key table, so the keys must not be split over two metas
        String tags = T.run("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default", out.getPath());
        T.contains(tags, "com.android.capture.fps=30", "capture.fps kept");
        T.contains(tags, "com.apple.quicktime.software=" + SOFTWARE, "software key");
        T.contains(tags, "com.apple.quicktime.model=" + MODEL, "model key");
        T.notContains(tags, "com.android.version", "android version");
        sameMedia(in, out);
        // the keys moved to a QuickTime-style moov/meta; no 'mdta' meta is left in udta
        byte[] b = T.read(out);
        Box mm = find(b, "moov", "meta");
        T.check(mm != null && ByteUtils.u32be(b, mm.payload()) != 0, "moov/meta in QuickTime style");
        T.check(find(b, "moov", "udta", "meta") == null, "udta/meta moved");

        // removal-only edit: keys stay in the FullBox-style udta/meta (version/flags kept)
        EditSpec rm = nullSpec();
        rm.model = "";
        File outRm = out(in, "rm");
        T.edit(in, outRm, rm);
        byte[] c = T.read(outRm);
        Box um = find(c, "moov", "udta", "meta");
        T.check(um != null && ByteUtils.u32be(c, um.payload()) == 0, "udta/meta keeps version/flags");
        T.check(find(c, "moov", "meta") == null, "no moov/meta created by a removal");
        Map<String, String> y = T.exif(outRm);
        T.eq("HUAWEI", y.get("Keys:Make"), "make kept");
        T.check(!y.containsKey("Keys:Model"), "model removed");
        T.eq("14", y.get("Keys:AndroidVersion"), "android version kept (removeMakerNote off)");
        T.contains(T.run("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default", outRm.getPath()),
                "com.android.capture.fps=30", "renumbered capture.fps");
        sameMedia(in, outRm);

        // removeMakerNote=false keeps com.android.version
        EditSpec keep = spec();
        keep.removeMakerNote = false;
        File out2 = out(in, "keep");
        T.edit(in, out2, keep);
        T.eq("14", T.exif(out2).get("Keys:AndroidVersion"), "AndroidVersion kept");
    }

    public static void testQuickTimeKeysRenumbering() throws Exception {
        if (!tools()) return;
        File in = keysQt();
        Map<String, String> x0 = T.exif(in);
        T.eq("13", x0.get("Keys:AndroidVersion"), "fixture AndroidVersion");
        byte[] a = T.read(in);
        Box meta0 = find(a, "moov", "meta");
        T.check(ByteUtils.u32be(a, meta0.payload()) != 0, "fixture moov/meta is QuickTime style");

        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.contains(changes(r), "Video Make (Keys): HUAWEI → Apple", "report");
        T.contains(changes(r), "Video Software (Keys): HarmonyOS → " + SOFTWARE, "report");
        spoofed(out, false);
        Map<String, String> x = T.exif(out);
        T.eq(x0.get("Keys:GPSCoordinates"), x.get("Keys:GPSCoordinates"), "location untouched");
        T.eq(x0.get("Keys:CreationDate"), x.get("Keys:CreationDate"), "creationdate untouched");
        T.check(!x.containsKey("Keys:Make#2"), "no duplicate make key");
        byte[] b = T.read(out);
        Box meta = find(b, "moov", "meta");
        T.check(ByteUtils.u32be(b, meta.payload()) != 0, "QuickTime style preserved");
        sameMedia(in, out);

        // removal: make="" only; other keys survive renumbering
        EditSpec rm = nullSpec();
        rm.make = "";
        File out2 = out(in, "rm");
        EditReport r2 = T.edit(in, out2, rm);
        T.contains(changes(r2), "Video Make (Keys): HUAWEI → (silindi)", "removal report");
        Map<String, String> y = T.exif(out2);
        T.check(!y.containsKey("Keys:Make"), "Keys:Make removed: " + y);
        T.eq("ALN-AL00", y.get("Keys:Model"), "model kept");
        T.eq("HarmonyOS", y.get("Keys:Software"), "software kept");
        T.eq("13", y.get("Keys:AndroidVersion"), "android version kept");
        T.eq(x0.get("Keys:GPSCoordinates"), y.get("Keys:GPSCoordinates"), "location kept");
        T.eq(x0.get("Keys:CreationDate"), y.get("Keys:CreationDate"), "creationdate kept");
        T.check(!y.containsKey("UserData:Make"), "removal never creates");
        // moov shrank and moov is followed by media data -> a 'free' box keeps every offset
        T.eq(in.length(), out2.length(), "size preserved by free padding");
        T.eq("free", topLevelAfter(T.read(out2), "moov"), "free inserted after moov");
        sameMedia(in, out2);
    }

    public static void testExiftoolUserData() throws Exception {
        if (!tools()) return;
        File in = udtaExiftool();
        Map<String, String> x0 = T.exif(in);
        T.eq("HUAWEI", x0.get("UserData:Make"), "fixture");
        File out = out(in, "out");
        T.edit(in, out, spec());
        spoofed(out, true);
        Map<String, String> x = T.exif(out);
        T.check(!x.containsKey("UserData:Make#2"), "no duplicate");
        sameMedia(in, out);
    }

    public static void testCo64() throws Exception {
        if (!tools() || !T.hasTool("MP4Box")) return;
        File in = T.file("co64.mp4");
        if (!in.exists()) T.run("MP4Box", "-quiet", "-co64", "-add", mp4End().getPath(), "-new", in.getPath());
        byte[] a = T.read(in);
        T.check(find(a, "moov", "trak", "mdia", "minf", "stbl", "co64") != null, "fixture uses co64");
        T.check(find(a, "moov").off < find(a, "mdat").off, "fixture moov before mdat");
        File out = out(in, "out");
        T.edit(in, out, spec());
        spoofed(out, false);
        sameMedia(in, out);
        byte[] b = T.read(out);
        long delta = find(b, "moov").size - find(a, "moov").size;
        long[] oa = chunkOffsets(a);
        long[] ob = chunkOffsets(b);
        for (int i = 0; i < oa.length; i++) T.eq(oa[i] + delta, ob[i], "co64 offset " + i);
    }

    public static void testFragmented() throws Exception {
        if (!tools()) return;
        File in = ff("frag.mp4", "-movflags", "frag_keyframe+empty_moov", "-g", "10");
        byte[] a = T.read(in);
        T.check(find(a, "moof") != null && find(a, "mfra") != null, "fixture is fragmented with mfra");
        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.contains(changes(r), "parça ofsetleri", "fragment offsets patched");
        spoofed(out, false);
        sameMedia(in, out);
        byte[] b = T.read(out);
        // ffmpeg writes base_data_offset = start of the moof; must still hold after the shift
        List<Box> moofs = topLevel(b, "moof");
        T.check(moofs.size() >= 2, "moofs");
        for (Box moof : moofs) {
            for (Box traf : children(b, moof, "traf")) {
                for (Box tfhd : children(b, traf, "tfhd")) {
                    long flags = ByteUtils.u32be(b, tfhd.payload()) & 0xFFFFFF;
                    T.check((flags & 1) != 0, "fixture has base_data_offset");
                    T.eq((long) moof.off, ByteUtils.u64be(b, tfhd.payload() + 8), "tfhd base_data_offset");
                }
            }
        }
        // tfra entries point at moofs
        Box mfra = topLevel(b, "mfra").get(0);
        List<Long> moofOffsets = new ArrayList<Long>();
        for (Box m : moofs) moofOffsets.add((long) m.off);
        for (Box tfra : children(b, mfra, "tfra")) {
            int p = tfra.payload();
            int version = b[p] & 0xFF;
            long sizes = ByteUtils.u32be(b, p + 8);
            int extra = (int) (((sizes >> 4) & 3) + ((sizes >> 2) & 3) + (sizes & 3) + 3);
            long n = ByteUtils.u32be(b, p + 12);
            int pos = p + 16;
            int tl = version == 1 ? 8 : 4;
            for (int i = 0; i < n; i++) {
                long off = version == 1 ? ByteUtils.u64be(b, pos + tl) : ByteUtils.u32be(b, pos + tl);
                T.check(moofOffsets.contains(off), "tfra moof_offset " + off + " points at a moof");
                pos += tl * 2 + extra;
            }
        }
        T.eq(Long.valueOf(T.read(out).length), Long.valueOf(r.outputLength), "length");
    }

    public static void testFragmentedDefaultBaseMoof() throws Exception {
        if (!tools()) return;
        File in = ff("frag-dbm.mp4", "-movflags", "frag_keyframe+empty_moov+default_base_moof", "-g", "10");
        File out = out(in, "out");
        T.edit(in, out, spec());
        spoofed(out, false);
        sameMedia(in, out);
    }

    public static void testFreeAfterMoovAbsorbsGrowth() throws Exception {
        if (!tools()) return;
        File in = ff("moov-free.mp4", "-moov_size", "8192");
        byte[] a = T.read(in);
        T.eq("free", topLevelAfter(a, "moov"), "fixture has free after moov");
        File out = out(in, "out");
        EditReport r = T.edit(in, out, spec());
        T.notContains(changes(r), "ofset", "no offset changes needed");
        T.eq(in.length(), out.length(), "file size unchanged");
        byte[] b = T.read(out);
        T.eq(find(a, "mdat").off, find(b, "mdat").off, "mdat did not move");
        T.check(Arrays.equals(chunkOffsets(a), chunkOffsets(b)), "chunk offsets untouched");
        spoofed(out, false);
        sameMedia(in, out);
    }

    public static void testLargeFileStreaming() throws Exception {
        if (!tools()) return;
        File in = T.file("big.mp4");
        if (!in.exists()) {
            T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "nullsrc=s=640x360:r=30,geq=random(1)*255:128:128",
                    "-f", "lavfi", "-i", "sine", "-t", "5", "-c:v", "mpeg4", "-q:v", "2", "-c:a", "aac",
                    "-movflags", "+faststart", in.getPath());
        }
        T.check(in.length() > 30L * 1024 * 1024, "fixture is large: " + in.length());
        File out = out(in, "out");
        final List<long[]> calls = new ArrayList<long[]>();
        ProgressListener pl = new ProgressListener() {
            @Override
            public void onProgress(long done, long total) {
                calls.add(new long[]{done, total});
            }
        };
        FileChannelSource src = FileChannelSource.open(in);
        EditReport r;
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
            try {
                r = MetadataEditor.edit(src, os, spec(), pl);
            } finally {
                os.close();
            }
        } finally {
            src.close();
        }
        T.check(calls.size() >= 5, "progress reported: " + calls.size());
        long prev = -1;
        for (long[] c : calls) {
            T.check(c[0] >= prev, "progress monotonic");
            T.check(c[0] <= c[1], "progress <= total");
            prev = c[0];
        }
        long[] last = calls.get(calls.size() - 1);
        T.eq(out.length(), last[0], "final progress");
        T.eq(out.length(), last[1], "progress total");
        T.eq(out.length(), r.outputLength, "outputLength");
        spoofed(out, false);
        sameMedia(in, out);
    }

    public static void testIdempotent() throws Exception {
        if (!tools()) return;
        File[] ins = {mp4End(), mp4Fast(), mov(), keysFfmpeg(), keysQt(), vendorKeys(), iphoneLike(), samsungLike()};
        for (File in : ins) {
            File o1 = out(in, "idem1");
            File o2 = out(in, "idem2");
            T.edit(in, o1, spec());
            EditReport r2 = T.edit(o1, o2, spec());
            T.check(Arrays.equals(T.read(o1), T.read(o2)), "second edit identical: " + in.getName() + " " + r2);
            T.eq(0, r2.changes.size(), "second edit reports no changes: " + r2);
        }
    }

    public static void testAllNullSpecIsByteIdentical() throws Exception {
        if (!tools()) return;
        File[] ins = {mp4End(), mp4Fast(), mov(), keysFfmpeg(), keysQt(), udtaExiftool(), vendorKeys(), iphoneLike(),
                samsungLike()};
        for (File in : ins) {
            byte[] a = T.read(in);
            byte[] b = T.edit(a, nullSpec());
            T.check(Arrays.equals(a, b), "byte identical: " + in.getName());
        }
        // default spec (removeMakerNote on) on a file without com.android.version: also untouched
        EditSpec d = new EditSpec();
        byte[] a = T.read(mp4End());
        T.check(Arrays.equals(a, T.edit(a, d)), "default spec without identity values is a no-op");
    }

    public static void testRemovalNeverCreates() throws Exception {
        if (!tools()) return;
        File in = mov();
        EditSpec s = nullSpec();
        s.make = "";
        File out = out(in, "rm");
        T.edit(in, out, s);
        Map<String, String> x = T.exif(out);
        T.check(!x.containsKey("UserData:Make"), "©mak removed");
        T.eq("ALN-AL00", x.get("UserData:Model"), "model untouched (null = keep)");
        T.check(!x.containsKey("Keys:Make"), "no Keys created for removal");
        T.check(!x.containsKey("Keys:Model"), "no Keys created for null");
        sameMedia(in, out);
        // removing from a file that has nothing is a no-op
        s.model = "";
        s.software = "";
        byte[] a = T.read(mp4End());
        T.check(Arrays.equals(a, T.edit(a, s)), "removal on clean file is a no-op");
    }

    public static void testInspectCreationTime() throws Exception {
        if (!tools()) return;
        File in = ff("ctime.mp4", "-metadata", "creation_time=2024-05-06T07:08:09Z");
        MediaInfo info = T.inspect(in);
        T.eq(1714979289000L, info.creationTimeMillis, "creationTimeMillis");
        T.eq(160, info.width, "width");
        T.eq(120, info.height, "height");
        T.eq("yok", info.gpsStatus, "no location");
        T.eq(0L, T.inspect(mp4End()).creationTimeMillis, "0 stays 0");
        T.eq("var", T.inspect(keysQt()).gpsStatus, "location key present");
    }

    // =================================================================== brand policy (Android / vendor / Samsung / lens)

    private static final String S_MAKE = "samsung";
    private static final String S_MODEL = "SM-S938B";
    private static final String APPLE_LENS = "iPhone 18 Pro Max back triple camera 6.765mm f/1.78";
    private static final String OLD_LENS = "iPhone 14 Pro back camera 2.22mm f/2.2";
    private static final String KEY_LENS = "com.apple.quicktime.camera.lens_model";

    /** Spec for a Samsung (non-Apple) target. */
    private static EditSpec samsungSpec(boolean removeTraces) {
        EditSpec s = nullSpec();
        s.make = S_MAKE;
        s.model = S_MODEL;
        s.removeMakerNote = removeTraces;
        return s;
    }

    private static EditSpec appleSpec(boolean removeTraces) {
        EditSpec s = spec();
        s.lensModel = APPLE_LENS;
        s.removeMakerNote = removeTraces;
        return s;
    }

    /** ffmpeg 'mdta' keys (moov/udta/meta) as AOSP + OEM camera apps write them. */
    private static File vendorKeys() throws IOException {
        return ff("vendor-keys.mp4", "-movflags", "use_metadata_tags+faststart",
                "-metadata", "com.android.manufacturer=HUAWEI", "-metadata", "com.android.model=ALN-AL00",
                "-metadata", "com.android.version=14", "-metadata", "com.android.capture.fps=30.000000",
                "-metadata", "com.oplus.product.model=PHN110", "-metadata", "com.oplus.lens.model=OPPO wide 6.0mm",
                "-metadata", "com.honor.product.model=PGT-AN10", "-metadata", "com.honor.lens.model=HONOR main 6.7mm",
                "-metadata", "com.xiaomi.camera.lensModel=Xiaomi tele 9.0mm",
                "-metadata", "com.huawei.product.model=ALN-AL00",
                "-metadata", "com.huawei.movingphoto.version=2", "-metadata", "com.openharmony.movingphoto=1",
                "-metadata", "com.example.camera.model=ALN-AL00", "-metadata", "com.example.lens.model=Generic lens");
    }

    /** ffprobe format tags (key -> value). */
    private static Map<String, String> ffTags(File f) throws IOException {
        String out = T.run("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default=nw=1",
                f.getPath());
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (String line : out.split("\n")) {
            if (!line.startsWith("TAG:")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(4, eq), line.substring(eq + 1).trim());
        }
        return m;
    }

    private static void idempotent(File out, EditSpec s) throws IOException {
        File o2 = out(out, "again");
        EditReport r2 = T.edit(out, o2, s);
        T.check(Arrays.equals(T.read(out), T.read(o2)), "second edit identical: " + out.getName() + " " + r2);
        T.eq(0, r2.changes.size(), "second edit reports no changes: " + r2);
    }

    public static void testAndroidKeysAppleTargetRemovesTraces() throws Exception {
        if (!tools()) return;
        File in = vendorKeys();
        Map<String, String> t0 = ffTags(in);
        T.eq("PHN110", t0.get("com.oplus.product.model"), "fixture vendor key");
        Map<String, String> x0 = T.exif(in);
        T.eq("HUAWEI", x0.get("Keys:AndroidManufacturer"), "fixture Keys:AndroidManufacturer");
        T.eq("ALN-AL00", x0.get("Keys:AndroidModel"), "fixture Keys:AndroidModel");
        MediaInfo i0 = T.inspect(in);
        T.eq("HUAWEI", i0.make, "inspect: com.android.manufacturer fallback");
        T.eq("ALN-AL00", i0.model, "inspect: com.android.model fallback");

        EditSpec s = appleSpec(true);
        File out = out(in, "apple");
        EditReport r = T.edit(in, out, s);
        String c = changes(r);
        T.contains(c, "Orijinal marka izi kaldırıldı (Keys: com.android.manufacturer): HUAWEI", "report");
        T.contains(c, "Orijinal marka izi kaldırıldı (Keys: com.oplus.product.model): PHN110", "report");
        T.contains(c, "Orijinal marka izi kaldırıldı (Keys: com.honor.lens.model): HONOR main 6.7mm", "report");
        T.contains(c, "com.android.version anahtarı kaldırıldı", "report");
        Map<String, String> t = ffTags(out);
        String[] gone = {"com.android.manufacturer", "com.android.model", "com.android.version",
                "com.oplus.product.model", "com.oplus.lens.model", "com.honor.product.model", "com.honor.lens.model",
                "com.xiaomi.camera.lensModel", "com.huawei.product.model"};
        for (String k : gone) T.check(!t.containsKey(k), k + " must be removed: " + t);
        T.eq("2", t.get("com.huawei.movingphoto.version"), "moving-photo key kept");
        T.eq("1", t.get("com.openharmony.movingphoto"), "openharmony key kept");
        T.eq("30.000000", t.get("com.android.capture.fps"), "capture fps kept");
        T.eq(MODEL, t.get("com.example.camera.model"), "generic *.model key follows the model");
        T.eq("Generic lens", t.get("com.example.lens.model"), "generic lens key untouched");
        T.eq(MAKE, t.get("com.apple.quicktime.make"), "apple make");
        T.eq(MODEL, t.get("com.apple.quicktime.model"), "apple model");
        for (Map.Entry<String, String> e : t.entrySet()) {
            for (String old : new String[]{"HUAWEI", "ALN-AL00", "PHN110", "PGT-AN10", "HONOR", "OPPO", "Xiaomi"}) {
                T.notContains(e.getValue(), old, e.getKey());
            }
        }
        spoofed(out, false);
        Map<String, String> x = T.exif(out);
        T.eq(APPLE_LENS, x.get("Track1:CameraLensModel"), "Apple lens model created at track level");
        T.eq(APPLE_LENS, T.inspect(out).lensModel, "inspect lensModel");
        sameMedia(in, out);
        idempotent(out, s);
    }

    public static void testAndroidKeysAppleTargetKeepsTraceKeysWhenOptionOff() throws Exception {
        if (!tools()) return;
        File in = vendorKeys();
        EditSpec s = appleSpec(false);
        File out = out(in, "apple-keep");
        EditReport r = T.edit(in, out, s);
        T.contains(changes(r), "Video Make (Keys: com.android.manufacturer): HUAWEI → Apple", "report");
        T.contains(changes(r), "Video Model (Keys: com.oplus.product.model): PHN110 → " + MODEL, "report");
        Map<String, String> t = ffTags(out);
        T.eq(MAKE, t.get("com.android.manufacturer"), "android manufacturer set");
        T.eq(MODEL, t.get("com.android.model"), "android model set");
        T.eq("14", t.get("com.android.version"), "android version kept (option off)");
        T.eq(MODEL, t.get("com.oplus.product.model"), "vendor model key set");
        T.eq(MODEL, t.get("com.honor.product.model"), "vendor model key set");
        T.eq(MODEL, t.get("com.huawei.product.model"), "vendor model key set");
        // lens keys must never become the phone model
        T.eq("HONOR main 6.7mm", t.get("com.honor.lens.model"), "vendor lens key kept");
        T.eq("OPPO wide 6.0mm", t.get("com.oplus.lens.model"), "vendor lens key kept");
        T.eq("Xiaomi tele 9.0mm", t.get("com.xiaomi.camera.lensModel"), "vendor lens key kept");
        T.eq("Generic lens", t.get("com.example.lens.model"), "generic lens key kept");
        T.eq("2", t.get("com.huawei.movingphoto.version"), "moving-photo key kept");
        sameMedia(in, out);
        idempotent(out, s);

        // lensModel "" removes the vendor lens keys too
        EditSpec rmLens = nullSpec();
        rmLens.lensModel = "";
        File out2 = out(in, "rm-lens");
        T.edit(in, out2, rmLens);
        Map<String, String> t2 = ffTags(out2);
        T.check(!t2.containsKey("com.honor.lens.model") && !t2.containsKey("com.oplus.lens.model")
                && !t2.containsKey("com.xiaomi.camera.lensModel"), "vendor lens keys removed: " + t2);
        T.eq("PHN110", t2.get("com.oplus.product.model"), "model keys untouched (null)");
        T.eq("Generic lens", t2.get("com.example.lens.model"), "non-vendor lens key untouched");
        sameMedia(in, out2);
    }

    public static void testAndroidTargetWritesAndroidKeys() throws Exception {
        if (!tools()) return;
        File in = vendorKeys();
        EditSpec s = samsungSpec(true);
        File out = out(in, "samsung");
        EditReport r = T.edit(in, out, s);
        T.contains(changes(r), "Video Make (Keys: com.android.manufacturer): HUAWEI → samsung", "report");
        T.notContains(changes(r), "com.android.version", "android version is realistic for Android targets");
        Map<String, String> t = ffTags(out);
        T.eq(S_MAKE, t.get("com.android.manufacturer"), "android manufacturer");
        T.eq(S_MODEL, t.get("com.android.model"), "android model");
        T.eq("14", t.get("com.android.version"), "android version kept for Android targets");
        T.eq(S_MAKE, t.get("com.apple.quicktime.make"), "apple make key");
        T.eq(S_MODEL, t.get("com.apple.quicktime.model"), "apple model key");
        T.check(!t.containsKey("com.oplus.product.model") && !t.containsKey("com.honor.lens.model"),
                "vendor keys removed: " + t);
        T.eq("2", t.get("com.huawei.movingphoto.version"), "moving-photo key kept");
        Map<String, String> x = T.exif(out);
        T.check(!x.containsKey("Track1:CameraLensModel"), "no Apple lens key for an Android target");
        MediaInfo info = T.inspect(out);
        T.eq(S_MAKE, info.make, "inspect make");
        T.eq(S_MODEL, info.model, "inspect model");
        sameMedia(in, out);
        idempotent(out, s);

        // removeMakerNote off: vendor model keys follow the target, lens keys stay
        EditSpec keep = samsungSpec(false);
        File out2 = out(in, "samsung-keep");
        T.edit(in, out2, keep);
        Map<String, String> t2 = ffTags(out2);
        T.eq(S_MODEL, t2.get("com.oplus.product.model"), "vendor model key set");
        T.eq("HONOR main 6.7mm", t2.get("com.honor.lens.model"), "vendor lens key kept");
        idempotent(out2, keep);

        // file without any Keys: com.android.* are created (Android galleries read them), Apple target: never
        File clean = mp4Fast();
        File out3 = out(clean, "samsung");
        EditReport r3 = T.edit(clean, out3, s);
        T.contains(changes(r3), "Video Make (Keys: com.android.manufacturer): (yok) → samsung", "report");
        Map<String, String> x3 = T.exif(out3);
        T.eq(S_MAKE, x3.get("Keys:AndroidManufacturer"), "created Keys:AndroidManufacturer");
        T.eq(S_MODEL, x3.get("Keys:AndroidModel"), "created Keys:AndroidModel");
        T.eq(S_MAKE, x3.get("Keys:Make"), "Keys:Make");
        T.check(!x3.containsKey("Keys:AndroidVersion"), "android version never created");
        sameMedia(clean, out3);
        idempotent(out3, s);
        File out4 = out(clean, "apple-clean");
        T.edit(clean, out4, appleSpec(true));
        Map<String, String> x4 = T.exif(out4);
        T.check(!x4.containsKey("Keys:AndroidManufacturer") && !x4.containsKey("Keys:AndroidModel"),
                "no com.android.* for an Apple target: " + x4);
        // make unknown (null): the brand is not known, nothing Android-specific is created
        EditSpec onlyModel = nullSpec();
        onlyModel.model = S_MODEL;
        File out5 = out(clean, "model-only");
        T.edit(clean, out5, onlyModel);
        T.check(!T.exif(out5).containsKey("Keys:AndroidModel"), "no com.android.model without a make");
    }

    // ------------------------------------------------------------------ track-level lens model

    /** QuickTime-style (or FullBox) 'mdta' meta with UTF-8 string keys: name, value, name, value... */
    static byte[] keysMeta(boolean fullBox, String... kv) {
        byte[] hdlr = fullbox("hdlr", 0, u32(0), ascii("mdta"), u32(0), u32(0), u32(0), new byte[1]);
        int n = kv.length / 2;
        byte[] entries = new byte[0];
        byte[] items = new byte[0];
        for (int i = 0; i < n; i++) {
            byte[] name = kv[2 * i].getBytes(ByteUtils.UTF_8);
            entries = cat(entries, u32(8 + name.length), ascii("mdta"), name);
            items = cat(items, box(new String(u32(i + 1), ByteUtils.ISO_8859_1),
                    box("data", u32(1), u32(0), kv[2 * i + 1].getBytes(ByteUtils.UTF_8))));
        }
        byte[] body = cat(hdlr, fullbox("keys", 0, u32(n), entries), box("ilst", items));
        return fullBox ? fullbox("meta", 0, body) : box("meta", body);
    }

    /**
     * Appends {@code child} at the end of the (first) box at {@code path}, growing every box on the
     * path; stco/co64 entries that pointed after 'moov' are shifted (independent of the editor).
     */
    static byte[] insertChild(byte[] file, byte[] child, String... path) {
        long threshold = find(file, "moov").end();
        List<Box> chain = new ArrayList<Box>();
        for (int i = 1; i <= path.length; i++) chain.add(find(file, Arrays.copyOf(path, i)));
        Box target = chain.get(chain.size() - 1);
        int at = target.end();
        byte[] r = cat(Arrays.copyOf(file, at), child, Arrays.copyOfRange(file, at, file.length));
        for (Box b : chain) {
            if (b.hdr == 16) ByteUtils.put64be(r, b.off + 8, b.size + child.length);
            else ByteUtils.put32be(r, b.off, b.size + child.length);
        }
        Box moov = find(r, "moov");
        for (Box trak : childrenOf(r, moov, "trak")) {
            Box stbl = find2(r, trak, "mdia", "minf", "stbl");
            if (stbl == null) continue;
            for (Box t : childrenOf(r, stbl, null)) {
                boolean wide = t.type.equals("co64");
                if (!wide && !t.type.equals("stco")) continue;
                long cnt = ByteUtils.u32be(r, t.payload() + 4);
                for (int i = 0; i < cnt; i++) {
                    int p = t.payload() + 8 + i * (wide ? 8 : 4);
                    long v = wide ? ByteUtils.u64be(r, p) : ByteUtils.u32be(r, p);
                    if (v < threshold) continue;
                    if (wide) ByteUtils.put64be(r, p, v + child.length);
                    else ByteUtils.put32be(r, p, v + child.length);
                }
            }
        }
        return r;
    }

    /** Every chunk offset moved exactly as far as 'mdat' did. */
    private static void checkOffsetsFollowMdat(byte[] a, byte[] b) {
        long shift = (long) find(b, "mdat").off - find(a, "mdat").off;
        long[] oa = chunkOffsets(a);
        long[] ob = chunkOffsets(b);
        T.eq(oa.length, ob.length, "chunk count");
        for (int i = 0; i < oa.length; i++) T.eq(oa[i] + shift, ob[i], "chunk offset " + i);
    }

    private static Box find2(byte[] b, Box from, String... path) {
        Box cur = from;
        for (String t : path) {
            List<Box> c = childrenOf(b, cur, t);
            if (c.isEmpty()) return null;
            cur = c.get(0);
        }
        return cur;
    }

    /** iPhone-like MP4 (moov before mdat): Keys make/model + video trak/meta with the lens model. */
    private static File iphoneLike() throws IOException {
        File f = T.file("iphone-like.mp4");
        if (f.exists()) return f;
        File k = T.file("iphone-keys.mp4");
        if (!k.exists()) {
            T.run("exiftool", "-q", "-o", k.getPath(), "-Keys:Make=Apple", "-Keys:Model=iPhone 14 Pro",
                    "-Keys:Software=16.1", mp4Fast().getPath());
        }
        byte[] meta = keysMeta(false, KEY_LENS, OLD_LENS, "com.apple.quicktime.camera.identifier", "Back");
        T.write(f, insertChild(T.read(k), meta, "moov", "trak"));
        return f;
    }

    public static void testTrackLensModel() throws Exception {
        if (!tools()) return;
        File in = iphoneLike();
        byte[] a = T.read(in);
        Map<String, String> x0 = T.exif(in);
        T.eq(OLD_LENS, x0.get("Track1:CameraLensModel"), "fixture track lens model");
        T.eq("Back", x0.get("Track1:CameraIdentifier"), "fixture camera identifier");
        T.check(find(a, "moov").off < find(a, "mdat").off, "fixture moov before mdat");
        sameMedia(mp4Fast(), in); // the fixture builder kept every chunk offset valid
        MediaInfo i0 = T.inspect(in);
        T.eq("Apple", i0.make, "fixture make");
        T.eq(OLD_LENS, i0.lensModel, "inspect reads the track-level lens model");

        // Android target, lensModel null, remove traces: Apple lens trace removed, other keys kept
        EditSpec s = samsungSpec(true);
        File out = out(in, "samsung");
        EditReport r = T.edit(in, out, s);
        T.contains(changes(r), "Orijinal marka izi kaldırıldı (Keys/trak: " + KEY_LENS + "): " + OLD_LENS, "report");
        Map<String, String> x = T.exif(out);
        T.check(!x.containsKey("Track1:CameraLensModel"), "lens model removed: " + x);
        T.eq("Back", x.get("Track1:CameraIdentifier"), "other track keys kept");
        T.eq(S_MAKE, x.get("Keys:Make"), "Keys:Make");
        T.eq(null, T.inspect(out).lensModel, "inspect lensModel");
        sameMedia(in, out);
        byte[] b = T.read(out);
        checkOffsetsFollowMdat(a, b);
        Box tm = find(b, "moov", "trak", "meta");
        T.check(tm != null && ByteUtils.u32be(b, tm.payload()) != 0, "track meta stays QuickTime style");
        idempotent(out, s);

        // Apple target with a lens model: replaced in place (no second key / meta)
        EditSpec ap = appleSpec(true);
        File out2 = out(in, "apple");
        EditReport r2 = T.edit(in, out2, ap);
        T.contains(changes(r2), "Video LensModel (Keys/trak: " + KEY_LENS + "): " + OLD_LENS + " → " + APPLE_LENS,
                "report");
        Map<String, String> x2 = T.exif(out2);
        T.eq(APPLE_LENS, x2.get("Track1:CameraLensModel"), "lens model set");
        T.check(!x2.containsKey("Track1:CameraLensModel#2"), "single lens key");
        T.eq(1, childrenOf(T.read(out2), find(T.read(out2), "moov", "trak"), "meta").size(), "single track meta");
        sameMedia(in, out2);
        idempotent(out2, ap);

        // lensModel "" removes it even for an Apple target
        EditSpec rm = nullSpec();
        rm.lensModel = "";
        File out3 = out(in, "rm-lens");
        T.edit(in, out3, rm);
        T.check(!T.exif(out3).containsKey("Track1:CameraLensModel"), "lens model removed by \"\"");
        sameMedia(in, out3);

        // null lens model: kept for Apple targets, and when the option is off
        EditSpec keepApple = spec();
        keepApple.lensModel = null;
        File out4 = out(in, "keep-apple");
        T.edit(in, out4, keepApple);
        T.eq(OLD_LENS, T.exif(out4).get("Track1:CameraLensModel"), "kept for Apple target");
        File out5 = out(in, "keep-opt");
        T.edit(in, out5, samsungSpec(false));
        T.eq(OLD_LENS, T.exif(out5).get("Track1:CameraLensModel"), "kept when removeMakerNote is off");

        // a track meta holding only the lens key disappears with it
        File only = T.file("iphone-lens-only.mp4");
        T.write(only, insertChild(T.read(mp4Fast()), keysMeta(false, KEY_LENS, OLD_LENS), "moov", "trak"));
        File out6 = out(only, "samsung");
        T.edit(only, out6, samsungSpec(true));
        T.check(find(T.read(out6), "moov", "trak", "meta") == null, "empty track meta dropped");
        sameMedia(only, out6);
    }

    public static void testAppleTargetCreatesTrackLens() throws Exception {
        if (!tools()) return;
        File in = mp4Fast();
        byte[] a = T.read(in);
        EditSpec s = appleSpec(true);
        File out = out(in, "lens");
        EditReport r = T.edit(in, out, s);
        T.contains(changes(r), "Video LensModel (Keys/trak): (yok) → " + APPLE_LENS, "report");
        byte[] b = T.read(out);
        Box tm = find(b, "moov", "trak", "meta");
        T.check(tm != null, "trak/meta created in the video track");
        T.check(ByteUtils.u32be(b, tm.payload()) != 0, "QuickTime style (no version/flags) like iPhones");
        T.eq("mdta", ByteUtils.fourcc(b, find(b, "moov", "trak", "meta", "hdlr").payload() + 8), "handler");
        T.eq(APPLE_LENS, T.exif(out).get("Track1:CameraLensModel"), "exiftool reads the lens model");
        checkOffsetsFollowMdat(a, b);
        sameMedia(in, out);
        idempotent(out, s);
        // only the video track gets one
        Box moov = find(b, "moov");
        List<Box> traks = childrenOf(b, moov, "trak");
        T.check(traks.size() == 2, "fixture has video + audio");
        T.eq(1, childrenOf(b, traks.get(0), "meta").size(), "one meta in the video track");
        T.eq(0, childrenOf(b, traks.get(1), "meta").size(), "no meta in the audio track");
    }

    // ------------------------------------------------------------------ Samsung smta / mdln, 3GPP auth

    private static byte[] auth3gpp(String text, boolean utf16) {
        byte[] t = utf16 ? cat(new byte[]{(byte) 0xFE, (byte) 0xFF},
                text.getBytes(java.nio.charset.Charset.forName("UTF-16BE")), new byte[2])
                : cat(text.getBytes(ByteUtils.UTF_8), new byte[1]);
        return box("auth", u32(0), u16(0x15C7), t);
    }

    /** Samsung-like MP4: udta/smta (mdln + saut) and 3GPP 'auth' atoms naming the device. */
    private static File samsungLike() throws IOException {
        File f = T.file("samsung-like.mp4");
        if (f.exists()) return f;
        byte[] a = T.read(mp4Fast());
        T.check(find(a, "moov", "udta") != null, "ffmpeg fixture has moov/udta");
        byte[] smta = box("smta", u32(0), box("mdln", ascii("SM-S918B")), box("saut", new byte[4]));
        byte[] extra = cat(smta, auth3gpp("Galaxy S23 Ultra", false), auth3gpp("Ali Veli", false),
                auth3gpp("Samsung SM-S918B", true));
        T.write(f, insertChild(a, extra, "moov", "udta"));
        return f;
    }

    public static void testSamsungSmtaAndAuth() throws Exception {
        if (!tools()) return;
        File in = samsungLike();
        Map<String, String> x0 = T.exif(in);
        T.eq("SM-S918B", x0.get("Samsung:SamsungModel"), "fixture SamsungModel");
        T.eq("Galaxy S23 Ultra", x0.get("UserData:Author"), "fixture Author");
        T.eq("Samsung SM-S918B", x0.get("UserData:Author#3"), "fixture UTF-16 Author");
        sameMedia(mp4Fast(), in);
        MediaInfo i0 = T.inspect(in);
        T.eq("SM-S918B", i0.model, "inspect: smta/mdln model fallback");
        T.eq(null, i0.make, "inspect make");

        // Apple target, remove traces: mdln and device 'auth' atoms removed, the rest of smta kept
        EditSpec s = appleSpec(true);
        File out = out(in, "apple");
        EditReport r = T.edit(in, out, s);
        T.contains(changes(r), "Orijinal marka izi kaldırıldı (UserData: smta/mdln): SM-S918B", "report");
        T.contains(changes(r), "Orijinal marka izi kaldırıldı (UserData: auth): Galaxy S23 Ultra", "report");
        T.contains(changes(r), "Orijinal marka izi kaldırıldı (UserData: auth): Samsung SM-S918B", "report");
        Map<String, String> x = T.exif(out);
        T.check(!x.containsKey("Samsung:SamsungModel"), "SamsungModel removed: " + x);
        T.eq("Ali Veli", x.get("UserData:Author"), "unrelated author kept");
        T.check(!x.containsKey("UserData:Author#2"), "device authors removed: " + x);
        byte[] b = T.read(out);
        Box smta = find(b, "moov", "udta", "smta");
        T.check(smta != null, "smta kept");
        T.eq(0L, ByteUtils.u32be(b, smta.payload()), "smta version/flags kept");
        T.eq(12L, ByteUtils.u32be(b, smta.payload() + 4), "saut kept");
        T.eq("saut", ByteUtils.fourcc(b, smta.payload() + 8), "saut kept");
        spoofed(out, false);
        sameMedia(in, out);
        idempotent(out, s);

        // Samsung target, option off: mdln and device authors follow the model (UTF-16 stays UTF-16)
        EditSpec keep = samsungSpec(false);
        File out2 = out(in, "samsung");
        EditReport r2 = T.edit(in, out2, keep);
        T.contains(changes(r2), "Video Model (UserData: smta/mdln): SM-S918B → " + S_MODEL, "report");
        T.contains(changes(r2), "Video Author (UserData: auth): Galaxy S23 Ultra → " + S_MODEL, "report");
        Map<String, String> x2 = T.exif(out2);
        T.eq(S_MODEL, x2.get("Samsung:SamsungModel"), "SamsungModel set");
        T.eq(S_MODEL, x2.get("UserData:Author"), "author replaced");
        T.eq("Ali Veli", x2.get("UserData:Author#2"), "unrelated author kept");
        T.eq(S_MODEL, x2.get("UserData:Author#3"), "UTF-16 author replaced");
        byte[] b2 = T.read(out2);
        T.check(indexOf(b2, cat(new byte[]{(byte) 0xFE, (byte) 0xFF},
                S_MODEL.getBytes(java.nio.charset.Charset.forName("UTF-16BE")))) > 0, "UTF-16 kept");
        MediaInfo i2 = T.inspect(out2);
        T.eq(S_MAKE, i2.make, "inspect make");
        T.eq(S_MODEL, i2.model, "inspect model");
        sameMedia(in, out2);
        idempotent(out2, keep);

        // model "" with the option off: removed (never created)
        EditSpec rm = nullSpec();
        rm.model = "";
        File out3 = out(in, "rm");
        EditReport r3 = T.edit(in, out3, rm);
        T.contains(changes(r3), "Video Model (UserData: smta/mdln): SM-S918B → (silindi)", "report");
        Map<String, String> x3 = T.exif(out3);
        T.check(!x3.containsKey("Samsung:SamsungModel"), "mdln removed");
        T.eq("Ali Veli", x3.get("UserData:Author"), "only the unrelated author is left: " + x3);
        T.check(!x3.containsKey("UserData:Author#2"), "device authors removed");
        sameMedia(in, out3);

        // nothing to do: byte identical
        byte[] a = T.read(in);
        T.check(Arrays.equals(a, T.edit(a, nullSpec())), "null spec is a no-op");
    }

    public static void testInspectFallbackOrder() throws Exception {
        byte[] stco = fullbox("stco", 0, u32(0));
        byte[] udta = box("udta", box("©mak", u16(9), u16(0x15C7), ascii("UDTA-MAKE")),
                box("©mod", u16(10), u16(0x15C7), ascii("UDTA-MODEL")),
                box("smta", u32(0), box("mdln", ascii("SMTA-MODEL\0"))));
        byte[] android = keysMeta(false, "com.android.manufacturer", "ANDROID-MAKE", "com.android.model",
                "ANDROID-MODEL");
        byte[] both = keysMeta(false, "com.android.manufacturer", "ANDROID-MAKE", "com.android.model",
                "ANDROID-MODEL", "com.apple.quicktime.make", "APPLE-MAKE");
        MediaInfo i = MetadataEditor.inspect(new ByteArraySource(synth(stco, cat(both, udta))));
        T.eq("APPLE-MAKE", i.make, "com.apple.quicktime.make first");
        T.eq("ANDROID-MODEL", i.model, "then com.android.model");
        i = MetadataEditor.inspect(new ByteArraySource(synth(stco, cat(android, udta))));
        T.eq("ANDROID-MAKE", i.make, "com.android.manufacturer before udta");
        i = MetadataEditor.inspect(new ByteArraySource(synth(stco, udta)));
        T.eq("UDTA-MAKE", i.make, "udta ©mak");
        T.eq("UDTA-MODEL", i.model, "udta ©mod before smta");
        byte[] smtaOnly = box("udta", box("smta", u32(0), box("mdln", ascii("SMTA-MODEL\0"))));
        i = MetadataEditor.inspect(new ByteArraySource(synth(stco, smtaOnly)));
        T.eq(null, i.make, "smta has no make");
        T.eq("SMTA-MODEL", i.model, "smta/mdln (NUL stripped)");

        // NUL-terminated mdln keeps its terminator when rewritten; Android target updates it in place
        EditSpec s = samsungSpec(false);
        byte[] b = T.edit(synth(stco, smtaOnly), s);
        Box sm = find(b, "moov", "udta", "smta");
        T.eq("mdln", ByteUtils.fourcc(b, sm.payload() + 8), "mdln after smta version/flags");
        T.eq(8L + S_MODEL.length() + 1, ByteUtils.u32be(b, sm.payload() + 4), "mdln size with NUL");
        T.eq(S_MODEL, new String(b, sm.payload() + 12, S_MODEL.length(), ByteUtils.ISO_8859_1), "mdln text");
        T.eq(0, b[sm.payload() + 12 + S_MODEL.length()] & 0xFF, "mdln NUL terminator kept");
        T.eq(S_MODEL, MetadataEditor.inspect(new ByteArraySource(b)).model, "mdln model");
    }

    public static void testKeyClassificationOnSyntheticKeys() throws Exception {
        // every key family in one QuickTime-style moov/meta; Apple target, option off -> only make/model keys change
        String[] kv = {
                "com.honor.product.model", "PGT-AN10",
                "com.honor.lens.model", "HONOR main",
                "com.vivo.camera.lens_model", "vivo lens",
                "com.samsung.android.manufacturer", "SAMSUNG-MFR",
                "com.xiaomi.product.marketname", "Xiaomi 14", // neither model nor make: untouched
                "com.huawei.movingphoto.model", "MOVING-VAL", // moving-photo: protected
                "com.openharmony.product.model", "OHOS-VAL", // openharmony: protected
                "com.android.version", "14",
                "Com.Android.Model", "ALN-AL00", // case-insensitive
        };
        byte[] meta = keysMeta(false, kv);
        byte[] file = synth(fullbox("stco", 0, u32(0)), meta);
        EditSpec s = nullSpec();
        s.make = MAKE;
        s.model = MODEL;
        byte[] out = T.edit(file, s);
        String o = new String(out, ByteUtils.ISO_8859_1);
        T.contains(o, "HONOR main", "honor lens kept");
        T.contains(o, "vivo lens", "vivo lens kept");
        T.contains(o, "Xiaomi 14", "unrelated vendor key kept");
        T.contains(o, "MOVING-VAL", "moving-photo key kept");
        T.contains(o, "OHOS-VAL", "openharmony key kept");
        T.notContains(o, "PGT-AN10", "vendor model replaced");
        T.notContains(o, "ALN-AL00", "android model replaced (case-insensitive name)");
        T.notContains(o, "SAMSUNG-MFR", "vendor manufacturer replaced");
        T.check(Arrays.equals(out, T.edit(out, s)), "idempotent");
        // Apple target with the option on: every brand key goes, protected keys stay
        s.removeMakerNote = true;
        byte[] out2 = T.edit(file, s);
        String o2 = new String(out2, ByteUtils.ISO_8859_1);
        for (String k : new String[]{"com.honor.product.model", "com.honor.lens.model", "com.vivo.camera.lens_model",
                "com.samsung.android.manufacturer", "com.android.version", "Com.Android.Model"}) {
            T.notContains(o2, k, "removed key name");
        }
        T.contains(o2, "com.xiaomi.product.marketname", "kept");
        T.contains(o2, "com.huawei.movingphoto.model", "kept");
        T.contains(o2, "com.openharmony.product.model", "kept");
        T.check(Arrays.equals(out2, T.edit(out2, s)), "idempotent");
    }

    // =================================================================== synthetic files

    public static void testStcoOverflowBecomesCo64() throws Exception {
        long big = 0xFFFFFFF0L;
        byte[] stco = fullbox("stco", 0, u32(3), u32(big), u32(7), u32(0)); // last patched below
        byte[] saio = fullbox("saio", 0, u32(1), u32(0xFFFFFFF8L));
        byte[] file = synth(cat(stco, saio), null);
        int moovEnd = find(file, "moov").end();
        // third chunk offset = start of mdat payload (>= moov end)
        Box st = find(file, "moov", "trak", "mdia", "minf", "stbl", "stco");
        ByteUtils.put32be(file, st.payload() + 16, moovEnd + 8);

        EditSpec s = nullSpec();
        s.make = MAKE;
        s.model = MODEL;
        EditReport r = new EditReport();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Mp4Editor.edit(new ByteArraySource(file), bo, s, r, null);
        byte[] b = bo.toByteArray();
        T.contains(changes(r), "co64", "report mentions co64 conversion");
        long delta = find(b, "moov").size - find(file, "moov").size;
        T.check(delta > 0, "moov grew");
        Box c = find(b, "moov", "trak", "mdia", "minf", "stbl", "co64");
        T.check(c != null, "stco converted to co64");
        T.check(find(b, "moov", "trak", "mdia", "minf", "stbl", "stco") == null, "no stco left");
        int p = c.payload();
        T.eq(0L, ByteUtils.u32be(b, p), "co64 version/flags");
        T.eq(3L, ByteUtils.u32be(b, p + 4), "count");
        T.eq(big + delta, ByteUtils.u64be(b, p + 8), "shifted past 4 GiB");
        T.eq(7L, ByteUtils.u64be(b, p + 16), "offset before moov untouched");
        T.eq((long) moovEnd + 8 + delta, ByteUtils.u64be(b, p + 24), "mdat offset shifted");
        Box sa = find(b, "moov", "trak", "mdia", "minf", "stbl", "saio");
        T.eq(1, b[sa.payload()] & 0xFF, "saio version 1");
        T.eq(0xFFFFFFF8L + delta, ByteUtils.u64be(b, sa.payload() + 8), "saio offset shifted");
        // the mdat payload follows the new moov
        Box mdat = find(b, "mdat");
        T.eq((long) moovEnd + 8 + delta, (long) mdat.payload(), "mdat moved by delta");
        T.eq("MDATDATA", new String(b, mdat.payload(), 8, "US-ASCII"), "mdat content");
    }

    /**
     * moov/meta (QuickTime style, com.apple.quicktime.make = com.android.manufacturer = HUAWEI) + udta
     * ©mak HUAWEI, moov followed by {@code afterMoov} + mdat.
     */
    private static byte[] keysAndUdtaFile(byte[] afterMoov) {
        byte[] hdlr = fullbox("hdlr", 0, u32(0), ascii("mdta"), u32(0), u32(0), u32(0), new byte[1]);
        String key = "com.apple.quicktime.make";
        String key2 = "com.android.manufacturer";
        byte[] keys = fullbox("keys", 0, u32(2), u32(8 + key.length()), ascii("mdta"), ascii(key),
                u32(8 + key2.length()), ascii("mdta"), ascii(key2));
        byte[] ilst = box("ilst", box(new String(u32(1), ByteUtils.ISO_8859_1),
                box("data", u32(1), u32(0), ascii("HUAWEI"))), box(new String(u32(2), ByteUtils.ISO_8859_1),
                box("data", u32(1), u32(0), ascii("HUAWEI"))));
        byte[] meta = box("meta", hdlr, keys, ilst);
        byte[] udta = box("udta", box("©mak", u16(6), u16(0x15C7), ascii("HUAWEI")));
        byte[] f = synth(fullbox("stco", 0, u32(1), u32(0)), cat(meta, udta));
        Box moov = find(f, "moov");
        byte[] r = cat(Arrays.copyOf(f, moov.end()), afterMoov, Arrays.copyOfRange(f, moov.end(), f.length));
        Box st = find(r, "moov", "trak", "mdia", "minf", "stbl", "stco");
        ByteUtils.put32be(r, st.payload() + 8, find(r, "mdat").payload());
        return r;
    }

    private static void checkMdatReachable(byte[] b) {
        Box st = find(b, "moov", "trak", "mdia", "minf", "stbl", "stco");
        long off = ByteUtils.u32be(b, st.payload() + 8);
        T.eq("MDATDATA", new String(b, (int) off, 8, ByteUtils.ISO_8859_1), "chunk offset points at the sample");
    }

    public static void testFreeBoxPlans() throws Exception {
        EditSpec grow = nullSpec();
        grow.make = "HUAWEI-LONGER"; // +7 in Keys (apple make), +7 in Keys (android manufacturer), +7 in UserData
        EditSpec shrink = nullSpec();
        shrink.make = "HUAW"; // -2 / -2 / -2

        // no free: small negative delta (-6) cannot hold a free box -> offsets shift down
        byte[] a = keysAndUdtaFile(new byte[0]);
        byte[] b = T.edit(a, shrink);
        T.eq(a.length - 6, b.length, "shrunk by 6");
        checkMdatReachable(b);
        T.eq("HUAW", MetadataEditor.inspect(new ByteArraySource(b)).make, "make");

        // growth of exactly the free size: free removed, nothing moves
        a = keysAndUdtaFile(box("free", new byte[13]));
        b = T.edit(a, grow);
        T.eq(a.length, b.length, "same length");
        T.eq("mdat", topLevelAfter(b, "moov"), "free consumed");
        T.eq(find(a, "mdat").off, find(b, "mdat").off, "mdat in place");
        checkMdatReachable(b);

        // larger free: shrunk
        a = keysAndUdtaFile(box("free", new byte[30]));
        b = T.edit(a, grow);
        T.eq(a.length, b.length, "same length");
        T.eq(38L - 21, find(b, "free").size, "free shrunk by 21");
        checkMdatReachable(b);

        // free too small to keep a header after shrinking: offsets shift instead
        a = keysAndUdtaFile(box("free", new byte[12]));
        b = T.edit(a, grow);
        T.eq(a.length + 21, b.length, "grew by 21");
        T.eq(20L, find(b, "free").size, "free untouched");
        checkMdatReachable(b);

        // shrink next to a free: the free grows
        a = keysAndUdtaFile(box("free", new byte[0]));
        b = T.edit(a, shrink);
        T.eq(a.length, b.length, "same length");
        T.eq(14L, find(b, "free").size, "free extended by 6");
        checkMdatReachable(b);
        T.eq("HUAW", MetadataEditor.inspect(new ByteArraySource(b)).make, "make");

        // large negative delta without free: a free box is inserted
        EditSpec rm = nullSpec();
        rm.make = "";
        a = keysAndUdtaFile(new byte[0]);
        b = T.edit(a, rm);
        T.eq(a.length, b.length, "same length");
        T.eq("free", topLevelAfter(b, "moov"), "free inserted");
        checkMdatReachable(b);
        MediaInfo info = MetadataEditor.inspect(new ByteArraySource(b));
        T.eq(null, info.make, "make removed everywhere");
    }

    public static void testForeignMoovMetaUntouched() throws Exception {
        // moov/meta with an iTunes 'mdir' handler (FullBox style) must not be touched
        byte[] hdlr = fullbox("hdlr", 0, u32(0), ascii("mdir"), u32(0), u32(0), u32(0), new byte[1]);
        byte[] item = box("©too", box("data", u32(1), u32(0), ascii("Lavf")));
        byte[] meta = fullbox("meta", 0, hdlr, box("ilst", item));
        byte[] file = synth(fullbox("stco", 0, u32(0)), meta);
        EditSpec s = nullSpec();
        s.make = MAKE;
        s.model = MODEL;
        EditReport r = new EditReport();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Mp4Editor.edit(new ByteArraySource(file), bo, s, r, null);
        byte[] b = bo.toByteArray();
        T.check(indexOf(b, meta) >= 0, "foreign moov/meta copied verbatim");
        T.check(r.warnings.toString().contains("moov/meta"), "warning: " + r.warnings);
        T.eq(1, childrenOf(b, find(b, "moov"), "meta").size(), "no second moov/meta created");
        Box mak = find(b, "moov", "udta", "©mak");
        T.check(mak != null, "©mak created");
        T.eq(MAKE, new String(b, mak.payload() + 4, MAKE.length(), "UTF-8"), "©mak text");
        T.eq(0x15C7, ByteUtils.u16be(b, mak.payload() + 2), "©mak language 'eng'");
        T.eq(MAKE.length(), ByteUtils.u16be(b, mak.payload()), "©mak length");
    }

    public static void testUdtaTerminatorAndLargeSizeMoov() throws Exception {
        // QuickTime udta ending with a 32-bit zero terminator, moov written with a 64-bit largesize header
        byte[] mod = box("©mod", u16(8), u16(0), ascii("ALN-AL00"));
        byte[] udta = box("udta", mod, new byte[4]);
        byte[] file = synth(fullbox("stco", 0, u32(0)), udta);
        file = toLargeSizeMoov(file);
        T.check(ByteUtils.u32be(file, find(file, "moov").off) == 1, "fixture moov uses largesize");
        EditSpec s = nullSpec();
        s.make = MAKE;
        s.model = MODEL;
        byte[] b = T.edit(file, s);
        Box moov = find(b, "moov");
        T.eq(1L, ByteUtils.u32be(b, moov.off), "largesize header kept");
        Box u = find(b, "moov", "udta");
        T.eq(0L, ByteUtils.u32be(b, u.end() - 4), "udta terminator kept at the end");
        Box m = find(b, "moov", "udta", "©mod");
        T.eq(0, ByteUtils.u16be(b, m.payload() + 2), "existing Mac language code kept for ASCII");
        T.eq(MODEL, new String(b, m.payload() + 4, ByteUtils.u16be(b, m.payload()), "UTF-8"), "©mod");
        T.check(find(b, "moov", "udta", "©mak") != null, "©mak added");
        MediaInfo info = MetadataEditor.inspect(new ByteArraySource(b));
        T.eq(MAKE, info.make, "inspect make");
        T.eq(MODEL, info.model, "inspect model");
        // idempotent
        T.check(Arrays.equals(b, T.edit(b, s)), "idempotent");
    }

    public static void testCorruptInputsNeverThrowRuntimeExceptions() throws Exception {
        List<byte[]> bases = new ArrayList<byte[]>();
        if (tools()) {
            bases.add(T.read(keysQt()));
            bases.add(T.read(ff("frag.mp4", "-movflags", "frag_keyframe+empty_moov", "-g", "10")));
            bases.add(T.read(mov()));
            bases.add(T.read(vendorKeys()));
            bases.add(T.read(iphoneLike()));
            bases.add(T.read(samsungLike()));
        }
        bases.add(synth(fullbox("stco", 0, u32(1), u32(0)), box("udta", box("©mak", u16(2), u16(0),
                ascii("HW")))));
        Random rnd = new Random(42);
        int runs = 0;
        for (byte[] base : bases) {
            Box moov = find(base, "moov");
            int hi = Math.min(base.length, Math.max(moov.end(), 4096) + 64);
            // truncations
            for (int cut = 0; cut < hi; cut += 1 + rnd.nextInt(41)) {
                tryAll(Arrays.copyOf(base, cut));
                runs++;
            }
            // random byte corruption inside moov (sizes, counts, keys, offsets...)
            for (int i = 0; i < 400; i++) {
                byte[] c = base.clone();
                int n = 1 + rnd.nextInt(4);
                for (int k = 0; k < n; k++) {
                    int pos = moov.off + rnd.nextInt((int) moov.size);
                    c[pos] = (byte) (rnd.nextInt(3) == 0 ? 0xFF : rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(256));
                }
                tryAll(c);
                runs++;
            }
            // corruption anywhere
            for (int i = 0; i < 100; i++) {
                byte[] c = base.clone();
                c[rnd.nextInt(c.length)] = (byte) rnd.nextInt(256);
                tryAll(c);
                runs++;
            }
        }
        T.check(runs > 100, "runs " + runs);
    }

    private static void tryAll(byte[] data) throws Exception {
        EditSpec[] specs = {spec(), removeAll(), samsungSpec(true)};
        for (EditSpec s : specs) {
            try {
                Mp4Editor.edit(new ByteArraySource(data), new ByteArrayOutputStream(), s, new EditReport(), null);
            } catch (IOException expected) {
                // fine: UnsupportedFormatException with a Turkish message
            } catch (RuntimeException e) {
                throw new AssertionError("RuntimeException from Mp4Editor.edit (len " + data.length + "): " + e, e);
            }
            try {
                MetadataEditor.edit(new ByteArraySource(data), new ByteArrayOutputStream(), s, null);
            } catch (IOException expected) {
                // fine
            } catch (RuntimeException e) {
                throw new AssertionError("RuntimeException from MetadataEditor.edit: " + e, e);
            }
        }
        try {
            Mp4Editor.inspect(new ByteArraySource(data), new MediaInfo());
        } catch (Exception e) {
            throw new AssertionError("inspect must never throw: " + e, e);
        }
    }

    private static EditSpec removeAll() {
        EditSpec s = nullSpec();
        s.make = "";
        s.model = "";
        s.software = "";
        s.removeMakerNote = true;
        return s;
    }

    // =================================================================== synthetic builders

    /**
     * ftyp + moov(mvhd, trak(tkhd, mdia(mdhd, hdlr vide, minf(stbl(stblContent)))), extra) + mdat("MDATDATA").
     */
    private static byte[] synth(byte[] stblContent, byte[] moovExtra) {
        byte[] ftyp = box("ftyp", ascii("isom"), u32(512), ascii("isomiso2mp41"));
        byte[] mvhd = fullbox("mvhd", 0, new byte[96]);
        byte[] tkhdBody = new byte[80];
        ByteUtils.put32be(tkhdBody, 72, 320L << 16);
        ByteUtils.put32be(tkhdBody, 76, 240L << 16);
        byte[] tkhd = fullbox("tkhd", 3, tkhdBody);
        byte[] mdhd = fullbox("mdhd", 0, new byte[20]);
        byte[] hdlr = fullbox("hdlr", 0, u32(0), ascii("vide"), u32(0), u32(0), u32(0), new byte[1]);
        byte[] stbl = box("stbl", stblContent);
        byte[] minf = box("minf", stbl);
        byte[] mdia = box("mdia", mdhd, hdlr, minf);
        byte[] trak = box("trak", tkhd, mdia);
        byte[] moov = moovExtra == null ? box("moov", mvhd, trak) : box("moov", mvhd, trak, moovExtra);
        byte[] mdat = box("mdat", ascii("MDATDATA"));
        return cat(ftyp, moov, mdat);
    }

    private static byte[] toLargeSizeMoov(byte[] file) {
        Box moov = find(file, "moov");
        byte[] payload = Arrays.copyOfRange(file, moov.payload(), moov.end());
        byte[] h = new byte[16];
        ByteUtils.put32be(h, 0, 1);
        System.arraycopy(ascii("moov"), 0, h, 4, 4);
        ByteUtils.put64be(h, 8, 16 + payload.length);
        return cat(Arrays.copyOf(file, moov.off), h, payload, Arrays.copyOfRange(file, moov.end(), file.length));
    }

    static byte[] box(String type, byte[]... parts) {
        byte[] body = cat(parts);
        byte[] r = new byte[8 + body.length];
        ByteUtils.put32be(r, 0, r.length);
        System.arraycopy(type.getBytes(ByteUtils.ISO_8859_1), 0, r, 4, 4);
        System.arraycopy(body, 0, r, 8, body.length);
        return r;
    }

    static byte[] fullbox(String type, int flags, byte[]... parts) {
        return box(type, cat(u32(flags), cat(parts)));
    }

    static byte[] u32(long v) {
        byte[] b = new byte[4];
        ByteUtils.put32be(b, 0, v);
        return b;
    }

    static byte[] u16(int v) {
        byte[] b = new byte[2];
        ByteUtils.put16be(b, 0, v);
        return b;
    }

    static byte[] ascii(String s) {
        return s.getBytes(ByteUtils.ISO_8859_1);
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // =================================================================== independent box parser

    static final class Box {
        String type;
        int off;
        long size;
        int hdr;

        int payload() {
            return off + hdr;
        }

        int end() {
            return (int) (off + size);
        }
    }

    private static List<Box> parse(byte[] b, int start, int end) {
        List<Box> r = new ArrayList<Box>();
        int pos = start;
        while (pos + 8 <= end) {
            Box x = new Box();
            x.off = pos;
            x.size = ByteUtils.u32be(b, pos);
            x.type = ByteUtils.fourcc(b, pos + 4);
            x.hdr = 8;
            if (x.size == 1) {
                x.size = ByteUtils.u64be(b, pos + 8);
                x.hdr = 16;
            } else if (x.size == 0) {
                x.size = end - pos;
            }
            if (x.size < x.hdr || pos + x.size > end) break;
            r.add(x);
            pos += (int) x.size;
        }
        return r;
    }

    private static List<Box> childrenOf(byte[] b, Box parent, String type) {
        int start = parent.payload();
        if ("meta".equals(parent.type) && ByteUtils.u32be(b, start) == 0) start += 4;
        List<Box> r = new ArrayList<Box>();
        for (Box c : parse(b, start, parent.end())) {
            if (type == null || c.type.equals(type)) r.add(c);
        }
        return r;
    }

    private static List<Box> children(byte[] b, Box parent, String type) {
        return childrenOf(b, parent, type);
    }

    private static List<Box> topLevel(byte[] b, String type) {
        List<Box> r = new ArrayList<Box>();
        for (Box c : parse(b, 0, b.length)) {
            if (c.type.equals(type)) r.add(c);
        }
        return r;
    }

    private static String topLevelAfter(byte[] b, String type) {
        List<Box> all = parse(b, 0, b.length);
        for (int i = 0; i + 1 < all.size(); i++) {
            if (all.get(i).type.equals(type)) return all.get(i + 1).type;
        }
        return null;
    }

    /** First box along a path of types (top level first). */
    static Box find(byte[] b, String... path) {
        List<Box> level = parse(b, 0, b.length);
        Box cur = null;
        for (String t : path) {
            cur = null;
            for (Box x : level) {
                if (x.type.equals(t)) {
                    cur = x;
                    break;
                }
            }
            if (cur == null) return null;
            level = childrenOf(b, cur, null);
        }
        return cur;
    }

    /** All stco/co64 entries of all tracks, in order. */
    private static long[] chunkOffsets(byte[] b) {
        List<Long> r = new ArrayList<Long>();
        Box moov = find(b, "moov");
        for (Box trak : childrenOf(b, moov, "trak")) {
            Box mdia = childrenOf(b, trak, "mdia").get(0);
            Box minf = childrenOf(b, mdia, "minf").get(0);
            Box stbl = childrenOf(b, minf, "stbl").get(0);
            for (Box t : childrenOf(b, stbl, null)) {
                boolean wide = t.type.equals("co64");
                if (!wide && !t.type.equals("stco")) continue;
                long n = ByteUtils.u32be(b, t.payload() + 4);
                for (int i = 0; i < n; i++) {
                    int p = t.payload() + 8 + i * (wide ? 8 : 4);
                    r.add(wide ? ByteUtils.u64be(b, p) : ByteUtils.u32be(b, p));
                }
            }
        }
        long[] a = new long[r.size()];
        for (int i = 0; i < a.length; i++) a[i] = r.get(i);
        return a;
    }
}
