package com.metakamera.core;

import com.metakamera.core.isobmff.HeifEditor;
import com.metakamera.core.isobmff.Mp4Editor;
import com.metakamera.core.jpeg.JpegEditor;
import com.metakamera.core.png.PngEditor;
import com.metakamera.core.webp.WebpEditor;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Facade: detects the container and dispatches to the format editor.
 *
 * Every format editor follows the same contract:
 * <pre>
 *   static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
 *                    ProgressListener listener) throws IOException
 *   static void inspect(ByteSource src, MediaInfo info) throws IOException
 * </pre>
 * Editors must change ONLY device-identity metadata and copy every other byte verbatim
 * (pixel/sample data, trailers such as motion-photo videos, unknown boxes/segments...).
 */
public final class MetadataEditor {

    private MetadataEditor() {
    }

    public static EditReport edit(ByteSource src, OutputStream out, EditSpec spec, ProgressListener listener)
            throws IOException {
        if (spec == null) throw new IllegalArgumentException("spec == null");
        if (spec.editIcc && spec.icc == null) {
            EditSpec s = spec.copy();
            s.editIcc = false;
            spec = s;
        }
        MediaFormat fmt = FormatDetector.detect(src);
        EditReport report = new EditReport();
        report.format = fmt;
        CountingOutputStream cout = new CountingOutputStream(out);
        switch (fmt) {
            case JPEG:
                JpegEditor.edit(src, cout, spec, report, listener);
                break;
            case HEIF:
            case AVIF:
                HeifEditor.edit(src, cout, spec, report, listener);
                break;
            case PNG:
                PngEditor.edit(src, cout, spec, report, listener);
                break;
            case WEBP:
                WebpEditor.edit(src, cout, spec, report, listener);
                break;
            case MP4:
            case MOV:
                Mp4Editor.edit(src, cout, spec, report, listener);
                break;
            default:
                throw new UnsupportedFormatException(
                        "Desteklenmeyen dosya biçimi. Desteklenenler: JPEG, HEIC/HEIF, AVIF, PNG, WebP, MP4, MOV, 3GP.");
        }
        cout.flush();
        report.outputLength = cout.count;
        return report;
    }

    public static MediaInfo inspect(ByteSource src) throws IOException {
        MediaInfo info = new MediaInfo();
        info.format = FormatDetector.detect(src);
        info.length = src.length();
        switch (info.format) {
            case JPEG:
                JpegEditor.inspect(src, info);
                break;
            case HEIF:
            case AVIF:
                HeifEditor.inspect(src, info);
                break;
            case PNG:
                PngEditor.inspect(src, info);
                break;
            case WEBP:
                WebpEditor.inspect(src, info);
                break;
            case MP4:
            case MOV:
                Mp4Editor.inspect(src, info);
                break;
            default:
                break;
        }
        return info;
    }

    /** Counts bytes written (for {@link EditReport#outputLength}). */
    static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
