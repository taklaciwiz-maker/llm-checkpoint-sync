package com.metakamera.core.tiff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** One image file directory: its entries plus the next-IFD pointer. */
final class TiffIfd {

    static final Comparator<TiffEntry> BY_TAG = new Comparator<TiffEntry>() {
        @Override
        public int compare(TiffEntry x, TiffEntry y) {
            return x.tag < y.tag ? -1 : (x.tag == y.tag ? 0 : 1);
        }
    };

    /** exiftool group name, e.g. "IFD0", "ExifIFD", "GPS", "InteropIFD", "IFD1". */
    final String name;
    /** Source position, -1 for an IFD created by an edit. */
    int pos = -1;
    int srcCount;
    /** The source had room for the 4-byte next-IFD pointer. */
    boolean hasNextField;
    long next;

    final List<TiffEntry> entries = new ArrayList<TiffEntry>();

    // ------------------------------------------------------------- edit state

    /** Entries were added/removed: a complete new copy must be written. */
    boolean structural;
    /** The next pointer changed (written in place when the IFD is not re-written). */
    boolean nextDirty;
    /** Position of the re-written copy (append commit), -1 when not moved. */
    int newPos = -1;

    TiffIfd(String name) {
        this.name = name;
    }

    /** End (exclusive) of the source structure: count + entries + next pointer. */
    int srcEnd() {
        return pos + 2 + 12 * srcCount + (hasNextField ? 4 : 0);
    }

    TiffEntry find(int tag) {
        for (int i = 0; i < entries.size(); i++) {
            TiffEntry e = entries.get(i);
            if (e.tag == tag) return e;
        }
        return null;
    }

    List<TiffEntry> findAll(int tag) {
        List<TiffEntry> r = new ArrayList<TiffEntry>(1);
        for (int i = 0; i < entries.size(); i++) {
            TiffEntry e = entries.get(i);
            if (e.tag == tag) r.add(e);
        }
        return r;
    }

    List<TiffEntry> sortedEntries() {
        List<TiffEntry> l = new ArrayList<TiffEntry>(entries);
        Collections.sort(l, BY_TAG); // stable
        return l;
    }
}
