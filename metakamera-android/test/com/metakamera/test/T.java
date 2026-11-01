package com.metakamera.test;

import com.metakamera.core.ByteArraySource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.FileChannelSource;
import com.metakamera.core.IccIdentity;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.MetadataEditor;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test helpers: assertions, external tools (exiftool, ffmpeg, convert, heif-enc), fixtures dir. */
public final class T {

    private T() {
    }

    // ------------------------------------------------------------------ assertions

    public static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    public static void eq(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void contains(String haystack, String needle, String what) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError(what + ": expected to contain <" + needle + "> but was <" + haystack + ">");
        }
    }

    public static void notContains(String haystack, String needle, String what) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError(what + ": must NOT contain <" + needle + ">");
        }
    }

    // ------------------------------------------------------------------ files

    private static File work;

    /** Per-run scratch directory (build/test-work/...). */
    public static synchronized File workDir() {
        if (work == null) {
            String base = System.getProperty("metakamera.work", "build/test-work");
            work = new File(base);
            work.mkdirs();
        }
        return work;
    }

    public static File file(String name) {
        return new File(workDir(), name);
    }

    public static byte[] read(File f) throws IOException {
        return Files.readAllBytes(f.toPath());
    }

    public static void write(File f, byte[] data) throws IOException {
        Files.write(f.toPath(), data);
    }

    // ------------------------------------------------------------------ external tools

    public static final class Result {
        public int exit;
        public String out;

        @Override
        public String toString() {
            return "exit=" + exit + "\n" + out;
        }
    }

    /** Runs a command (stdout+stderr merged). */
    public static Result exec(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        Result r = new Result();
        try {
            r.exit = p.waitFor();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        r.out = new String(bo.toByteArray(), "UTF-8");
        return r;
    }

    /** Runs a command and throws if exit != 0. */
    public static String run(String... cmd) throws IOException {
        Result r = exec(cmd);
        if (r.exit != 0) throw new IOException("Command failed: " + Arrays.toString(cmd) + "\n" + r.out);
        return r.out;
    }

    public static boolean hasTool(String name) {
        try {
            return exec("sh", "-c", "command -v " + name).exit == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * exiftool tags as map "Group1:Tag" -> value (uses -a -G1 -s -args, duplicates get "#2" suffix).
     * Example keys: "IFD0:Make", "ExifIFD:LensModel", "ICC-header:DeviceManufacturer",
     * "ICC_Profile:DeviceMfgDesc", "XMP-tiff:Model", "Keys:Make", "UserData:Make".
     */
    public static Map<String, String> exif(File f, String... extraArgs) throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("exiftool");
        cmd.add("-a");
        cmd.add("-G1");
        cmd.add("-s");
        cmd.add("-args");
        cmd.add("-charset");
        cmd.add("UTF8");
        cmd.addAll(Arrays.asList(extraArgs));
        cmd.add(f.getAbsolutePath());
        String out = run(cmd.toArray(new String[0]));
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String line : out.split("\n")) {
            if (!line.startsWith("-")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(1, eq);
            String v = line.substring(eq + 1);
            String key = k;
            int i = 2;
            while (m.containsKey(key)) key = k + "#" + (i++);
            m.put(key, v);
        }
        return m;
    }

    /** exiftool -validate -warning -a output (warnings/errors only). */
    public static String validate(File f) throws IOException {
        return exec("exiftool", "-validate", "-warning", "-error", "-a", "-G1", "-s", f.getAbsolutePath()).out;
    }

    /** Decodes every frame/sample with ffmpeg; returns error output ("" when clean). */
    public static String ffmpegDecodeErrors(File f) throws IOException {
        Result r = exec("ffmpeg", "-v", "error", "-i", f.getAbsolutePath(), "-f", "null", "-");
        return r.exit == 0 ? r.out.trim() : ("exit " + r.exit + ": " + r.out.trim());
    }

    // ------------------------------------------------------------------ editor shortcuts

    /** Typical spec: spoof an Apple iPhone 17 Pro Max. */
    public static EditSpec appleSpec() {
        EditSpec s = new EditSpec();
        s.make = "Apple";
        s.model = "iPhone 17 Pro Max";
        s.software = "26.0.1";
        s.hostComputer = "iPhone 17 Pro Max";
        s.lensMake = "Apple";
        s.lensModel = "iPhone 17 Pro Max back triple camera 6.765mm f/1.78";
        s.removeMakerNote = true;
        s.clearSerials = true;
        s.editIcc = true;
        IccIdentity icc = new IccIdentity();
        icc.manufacturerSig = "APPL";
        icc.modelSig = "";
        icc.cmmSig = "appl";
        icc.platformSig = "APPL";
        icc.creatorSig = "appl";
        icc.manufacturerText = "Apple";
        icc.modelText = "iPhone 17 Pro Max";
        s.icc = icc;
        s.editXmp = true;
        return s;
    }

    /** Edits {@code in} to {@code out} with the facade. */
    public static EditReport edit(File in, File out, EditSpec spec) throws IOException {
        FileChannelSource src = FileChannelSource.open(in);
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
            try {
                return MetadataEditor.edit(src, os, spec, null);
            } finally {
                os.close();
            }
        } finally {
            src.close();
        }
    }

    public static byte[] edit(byte[] in, EditSpec spec) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        MetadataEditor.edit(new ByteArraySource(in), bo, spec, null);
        return bo.toByteArray();
    }

    public static MediaInfo inspect(File f) throws IOException {
        FileChannelSource src = FileChannelSource.open(f);
        try {
            return MetadataEditor.inspect(src);
        } finally {
            src.close();
        }
    }
}
