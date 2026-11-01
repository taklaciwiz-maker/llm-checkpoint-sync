package com.metakamera.test;

import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;

import java.io.File;

/**
 * Manual testing CLI.
 * <pre>
 *   java com.metakamera.test.Cli inspect FILE
 *   java com.metakamera.test.Cli apple IN OUT          # spoof iPhone 17 Pro Max (T.appleSpec())
 * </pre>
 */
public final class Cli {
    public static void main(String[] a) throws Exception {
        if (a.length >= 2 && a[0].equals("inspect")) {
            MediaInfo i = T.inspect(new File(a[1]));
            System.out.println("format=" + i.format + " make=" + i.make + " model=" + i.model
                    + " software=" + i.software + " host=" + i.hostComputer + " lensMake=" + i.lensMake
                    + " lensModel=" + i.lensModel + " date=" + i.dateTimeOriginal + " ctime=" + i.creationTimeMillis
                    + " exif=" + i.hasExif + " makernote=" + i.hasMakerNote + " gps=" + i.gpsStatus
                    + " icc=" + i.hasIcc + " iccMfr=" + i.iccManufacturer + " iccModel=" + i.iccModel
                    + " iccDesc=" + i.iccDescription + " dmnd=" + i.iccManufacturerText + " dmdd=" + i.iccModelText
                    + " xmp=" + i.hasXmp + " size=" + i.width + "x" + i.height);
            return;
        }
        if (a.length >= 3 && a[0].equals("apple")) {
            EditSpec s = T.appleSpec();
            EditReport r = T.edit(new File(a[1]), new File(a[2]), s);
            System.out.println(r);
            return;
        }
        System.err.println("usage: Cli inspect FILE | Cli apple IN OUT");
        System.exit(2);
    }
}
