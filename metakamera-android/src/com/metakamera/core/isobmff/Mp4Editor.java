package com.metakamera.core.isobmff;

import com.metakamera.core.ByteSource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;
import java.io.OutputStream;

/** MP4/MOV/3GP metadata editor. STUB — to be implemented. */
public final class Mp4Editor {

    private Mp4Editor() {
    }

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        throw new UnsupportedFormatException("MP4/MOV/3GP düzenleme henüz uygulanmadı");
    }

    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
    }
}
