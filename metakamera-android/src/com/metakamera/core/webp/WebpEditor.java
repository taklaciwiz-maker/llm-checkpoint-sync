package com.metakamera.core.webp;

import com.metakamera.core.ByteSource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;
import java.io.OutputStream;

/** WebP metadata editor. STUB — to be implemented. */
public final class WebpEditor {

    private WebpEditor() {
    }

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        throw new UnsupportedFormatException("WebP düzenleme henüz uygulanmadı");
    }

    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
    }
}
