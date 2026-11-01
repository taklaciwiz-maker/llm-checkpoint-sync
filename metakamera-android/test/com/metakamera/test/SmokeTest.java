package com.metakamera.test;

import com.metakamera.core.ByteArraySource;
import com.metakamera.core.FormatDetector;
import com.metakamera.core.MediaFormat;

/** Sanity checks for the shared foundation. */
public final class SmokeTest {
    public static void testDetectJpegPngWebp() throws Exception {
        T.eq(MediaFormat.JPEG, FormatDetector.detect(new ByteArraySource(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 0, 0, 0, 0, 0, 0, 0})), "jpeg");
        T.eq(MediaFormat.PNG, FormatDetector.detect(new ByteArraySource(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0})), "png");
        T.eq(MediaFormat.WEBP, FormatDetector.detect(new ByteArraySource(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})), "webp");
    }

    public static void testDetectMp4AndMov() throws Exception {
        if (!T.hasTool("ffmpeg")) return;
        java.io.File mp4 = T.file("smoke.mp4");
        java.io.File mov = T.file("smoke.mov");
        T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=5", "-t", "1", "-pix_fmt", "yuv420p", mp4.getPath());
        T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=5", "-t", "1", "-pix_fmt", "yuv420p", mov.getPath());
        T.eq(MediaFormat.MP4, FormatDetector.detect(new ByteArraySource(T.read(mp4))), "mp4");
        T.eq(MediaFormat.MOV, FormatDetector.detect(new ByteArraySource(T.read(mov))), "mov");
    }

    public static void testDetectHeic() throws Exception {
        if (!T.hasTool("heif-enc") || !T.hasTool("convert")) return;
        java.io.File png = T.file("smoke-src.png");
        java.io.File heic = T.file("smoke.heic");
        T.run("convert", "-size", "64x64", "gradient:red-blue", png.getPath());
        T.run("heif-enc", "-q", "50", "-o", heic.getPath(), png.getPath());
        T.eq(MediaFormat.HEIF, FormatDetector.detect(new ByteArraySource(T.read(heic))), "heic");
    }
}
