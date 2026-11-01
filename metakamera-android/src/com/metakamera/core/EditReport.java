package com.metakamera.core;

import java.util.ArrayList;
import java.util.List;

/** Human-readable (Turkish) log of what an edit did. */
public final class EditReport {

    public MediaFormat format = MediaFormat.UNKNOWN;
    /** e.g. "EXIF Make: HUAWEI → Apple", "ICC üretici: HUAW → APPL" */
    public final List<String> changes = new ArrayList<String>();
    /** Non-fatal problems, e.g. "XMP bulunamadı" or "MakerNote temizlendi". */
    public final List<String> warnings = new ArrayList<String>();
    /** Bytes written to the output. */
    public long outputLength;

    public void change(String s) {
        changes.add(s);
    }

    public void warn(String s) {
        warnings.add(s);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("format=").append(format).append('\n');
        for (String c : changes) sb.append("  + ").append(c).append('\n');
        for (String w : warnings) sb.append("  ! ").append(w).append('\n');
        return sb.toString();
    }
}
