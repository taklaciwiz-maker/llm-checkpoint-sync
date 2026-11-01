package com.metakamera.test;

import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.xmp.XmpEditor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tests for {@link XmpEditor}: Adobe-style packets (attribute and element forms, rdf:Alt),
 * custom prefixes, packets written by exiftool, special characters, removal, padding (packet
 * length) preservation, no-op identity and malformed input. exiftool is the reference reader;
 * Python's XML parser checks well-formedness of every edited packet.
 */
public final class XmpEditorTest {

    private static final String BOM = "﻿";
    private static final String PAD = padding(20);

    /** Adobe attribute form (double quotes), as Lightroom/Photoshop and many phones write it. */
    static final String ADOBE_ATTR = "<?xpacket begin=\"" + BOM + "\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.6-c140 79.160451, 2017/05/06-01:08:21        \">\n"
            + "   <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "      <rdf:Description rdf:about=\"\"\n"
            + "            xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n"
            + "            xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\"\n"
            + "            xmlns:exifEX=\"http://cipa.jp/exif/1.0/\"\n"
            + "            xmlns:aux=\"http://ns.adobe.com/exif/1.0/aux/\"\n"
            + "            xmlns:photoshop=\"http://ns.adobe.com/photoshop/1.0/\"\n"
            + "            xmp:CreatorTool=\"ELS-NX9 10.1.0.150(C432E3R1P1)\"\n"
            + "            xmp:ModifyDate=\"2024-05-01T10:00:00+03:00\"\n"
            + "            tiff:Make=\"HUAWEI\"\n"
            + "            tiff:Model=\"ELS-NX9\"\n"
            + "            exifEX:LensMake=\"HUAWEI\"\n"
            + "            exifEX:LensModel=\"HUAWEI P40 Pro Rear Main Camera\"\n"
            + "            aux:Lens=\"HUAWEI P40 Pro Rear Main Camera\"\n"
            + "            aux:SerialNumber=\"ABC123\"\n"
            + "            photoshop:DateCreated=\"2024-05-01T10:00:00\"/>\n"
            + "   </rdf:RDF>\n"
            + "</x:xmpmeta>\n"
            + PAD
            + "<?xpacket end=\"w\"?>";

    /** Element form with an rdf:Alt list, comments and CDATA. */
    static final String ELEMENT_FORM = "<?xpacket begin='" + BOM + "' id='W5M0MpCehiHzreSzNTczkc9d'?>\n"
            + "<x:xmpmeta xmlns:x='adobe:ns:meta/'>\n"
            + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>\n"
            + " <rdf:Description rdf:about=''\n"
            + "  xmlns:tiff='http://ns.adobe.com/tiff/1.0/'\n"
            + "  xmlns:exifEX='http://cipa.jp/exif/1.0/'\n"
            + "  xmlns:aux='http://ns.adobe.com/exif/1.0/aux/'\n"
            + "  xmlns:xmp='http://ns.adobe.com/xap/1.0/'>\n"
            + "  <!-- written by a phone -->\n"
            + "  <tiff:Make>samsung</tiff:Make>\n"
            + "  <tiff:Model><![CDATA[SM-S928B]]></tiff:Model>\n"
            + "  <tiff:Software>S928BXXU1AXB5</tiff:Software>\n"
            + "  <exifEX:LensModel>\n"
            + "   <rdf:Alt>\n"
            + "    <rdf:li xml:lang='x-default'>Samsung Wide</rdf:li>\n"
            + "    <rdf:li xml:lang='de-DE'>Samsung Weitwinkel</rdf:li>\n"
            + "   </rdf:Alt>\n"
            + "  </exifEX:LensModel>\n"
            + "  <aux:Lens>Samsung Wide</aux:Lens>\n"
            + "  <aux:SerialNumber>R5CX</aux:SerialNumber>\n"
            + "  <xmp:CreatorTool>S928BXXU1AXB5</xmp:CreatorTool>\n"
            + "  <xmp:Rating>5</xmp:Rating>\n"
            + " </rdf:Description>\n"
            + "</rdf:RDF>\n"
            + "</x:xmpmeta>\n"
            + PAD
            + "<?xpacket end='w'?>";

    /** Custom prefixes for the real namespaces, and the conventional "tiff" prefix bound to another URI. */
    static final String CUSTOM_PREFIX = "<?xpacket begin=\"" + BOM + "\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
            + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
            + "  <rdf:Description rdf:about=\"\" xmlns:t=\"http://ns.adobe.com/tiff/1.0/\" xmlns:tiff=\"http://example.com/fake/\"\n"
            + "    xmlns:cx=\"http://cipa.jp/exif/1.0/\" t:Make='Xiaomi' tiff:Model=\"Decoy\">\n"
            + "   <t:Model>23127PN0CG</t:Model>\n"
            + "   <tiff:Make>DecoyMake</tiff:Make>\n"
            + "   <cx:LensModel>Xiaomi Leica</cx:LensModel>\n"
            + "  </rdf:Description>\n"
            + "  <rdf:Description rdf:about=\"\" xmlns=\"http://ns.adobe.com/xap/1.0/\">\n"
            + "   <CreatorTool>MIUI Camera</CreatorTool>\n"
            + "  </rdf:Description>\n"
            + " </rdf:RDF>\n"
            + "</x:xmpmeta>\n"
            + PAD
            + "<?xpacket end=\"w\"?>";

    // ================================================================== editing

    public static void testAttributeFormAdobe() throws Exception {
        EditSpec spec = T.appleSpec();
        EditReport r = new EditReport();
        String out = XmpEditor.edit(ADOBE_ATTR, spec, r);
        T.check(out != ADOBE_ATTR, "changed");
        T.eq(utf8(ADOBE_ATTR), utf8(out), "packet length preserved");
        Map<String, String> m = exif(out, "adobe-attr");
        T.eq("Apple", m.get("XMP-tiff:Make"), "Make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "Model");
        T.eq("Apple", m.get("XMP-exifEX:LensMake"), "LensMake");
        T.eq(spec.lensModel, m.get("XMP-exifEX:LensModel"), "LensModel");
        T.eq(spec.lensModel, m.get("XMP-aux:Lens"), "aux:Lens");
        T.eq("26.0.1", m.get("XMP-xmp:CreatorTool"), "CreatorTool");
        T.eq(null, m.get("XMP-aux:SerialNumber"), "serial removed");
        T.eq("2024:05:01 10:00:00+03:00", m.get("XMP-xmp:ModifyDate"), "other attr kept");
        T.eq("2024:05:01 10:00:00", m.get("XMP-photoshop:DateCreated"), "other attr kept");
        T.check(r.changes.contains("XMP tiff:Make: HUAWEI → Apple"), "report " + r.changes);
        T.check(r.changes.contains("XMP tiff:Model: ELS-NX9 → iPhone 17 Pro Max"), "report " + r.changes);
        T.check(r.changes.contains("XMP xmp:CreatorTool: ELS-NX9 10.1.0.150(C432E3R1P1) → 26.0.1"), "report " + r.changes);
        T.check(r.changes.contains("XMP aux:SerialNumber kaldırıldı"), "report " + r.changes);
        T.eq(7, r.changes.size(), "number of changes " + r.changes);
        T.check(r.warnings.isEmpty(), "warnings " + r.warnings);
        // untouched lines stay byte-identical
        T.contains(out, "            xmp:ModifyDate=\"2024-05-01T10:00:00+03:00\"\n            tiff:Make=\"Apple\"\n", "layout");
        T.contains(out, "x:xmptk=\"Adobe XMP Core 5.6-c140 79.160451, 2017/05/06-01:08:21        \"", "toolkit kept");
        T.contains(out, "aux:Lens=\"" + spec.lensModel + "\"\n            photoshop:DateCreated", "serial attr removed cleanly");
        T.check(out.startsWith("<?xpacket begin=\"" + BOM + "\""), "header kept");
        T.check(out.endsWith("<?xpacket end=\"w\"?>"), "trailer kept");
        checkWellFormed(out);
        checkNoNewValidateWarnings(ADOBE_ATTR, out, "adobe-attr");
        assertIdempotent(out, spec);
    }

    public static void testElementFormWithAltCdataAndComments() throws Exception {
        EditSpec spec = T.appleSpec();
        EditReport r = new EditReport();
        String out = XmpEditor.edit(ELEMENT_FORM, spec, r);
        T.eq(utf8(ELEMENT_FORM), utf8(out), "packet length preserved");
        Map<String, String> m = exif(out, "element");
        T.eq("Apple", m.get("XMP-tiff:Make"), "Make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "Model (CDATA replaced)");
        T.eq("26.0.1", m.get("XMP-tiff:Software"), "tiff:Software");
        T.eq("26.0.1", m.get("XMP-xmp:CreatorTool"), "CreatorTool");
        T.eq(spec.lensModel, m.get("XMP-exifEX:LensModel"), "Alt x-default");
        T.eq(spec.lensModel, m.get("XMP-exifEX:LensModel#2"), "Alt second li");
        T.eq(spec.lensModel, m.get("XMP-aux:Lens"), "aux:Lens");
        T.eq(null, m.get("XMP-aux:SerialNumber"), "serial removed");
        T.eq("5", m.get("XMP-xmp:Rating"), "rating kept");
        T.eq(null, m.get("XMP-exifEX:LensMake"), "LensMake not created");
        T.contains(out, "<!-- written by a phone -->", "comment kept");
        T.contains(out, "<rdf:li xml:lang='de-DE'>" + XmlEsc.text(spec.lensModel) + "</rdf:li>", "li replaced in place");
        T.contains(out, "  <aux:Lens>" + spec.lensModel + "</aux:Lens>\n  <xmp:CreatorTool>", "serial element removed with its line");
        T.check(r.changes.contains("XMP exifEX:LensModel: Samsung Wide → " + spec.lensModel), "report " + r.changes);
        T.check(r.changes.contains("XMP tiff:Model: SM-S928B → iPhone 17 Pro Max"), "report " + r.changes);
        checkWellFormed(out);
        checkNoNewValidateWarnings(ELEMENT_FORM, out, "element");
        assertIdempotent(out, spec);
    }

    public static void testCustomPrefixesAndDecoys() throws Exception {
        EditSpec spec = T.appleSpec();
        EditReport r = new EditReport();
        String out = XmpEditor.edit(CUSTOM_PREFIX, spec, r);
        T.contains(out, "t:Make='Apple'", "custom prefix attribute, single quotes kept");
        T.contains(out, "<t:Model>iPhone 17 Pro Max</t:Model>", "custom prefix element");
        T.contains(out, "<cx:LensModel>" + spec.lensModel + "</cx:LensModel>", "custom exifEX prefix");
        T.contains(out, "<CreatorTool>26.0.1</CreatorTool>", "default namespace");
        T.contains(out, "tiff:Model=\"Decoy\"", "decoy attribute untouched");
        T.contains(out, "<tiff:Make>DecoyMake</tiff:Make>", "decoy element untouched");
        T.eq(utf8(CUSTOM_PREFIX), utf8(out), "length");
        Map<String, String> m = exif(out, "custom");
        T.eq("Apple", m.get("XMP-tiff:Make"), "exiftool Make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "exiftool Model");
        // exiftool does not resolve default (unprefixed) namespaces: it reports the tag without a namespace group
        T.eq("26.0.1", m.containsKey("XMP-xmp:CreatorTool") ? m.get("XMP-xmp:CreatorTool") : m.get("XMP:CreatorTool"), "exiftool CreatorTool");
        T.check(r.changes.contains("XMP tiff:Make: Xiaomi → Apple"), "canonical label " + r.changes);
        checkWellFormed(out);
        assertIdempotent(out, spec);
    }

    /** Packets written by exiftool itself (element form, single quotes, &#39; references, 2KB+ padding). */
    public static void testExiftoolGeneratedPackets() throws Exception {
        if (!T.hasTool("exiftool") || !T.hasTool("convert")) return;
        File jpg = T.file("xmp-src.jpg");
        T.run("convert", "-size", "32x32", "gradient:red-blue", jpg.getPath());
        T.run("exiftool", "-q", "-overwrite_original",
                "-XMP-tiff:Make=HUAWEI", "-XMP-tiff:Model=ALN-AL00 & <Pura>",
                "-XMP-exifEX:LensMake=HUAWEI", "-XMP-exifEX:LensModel=Huawei 'Leica' \"VARIO\"",
                "-XMP-xmp:CreatorTool=ALN-AL00 4.0.0", "-XMP-aux:Lens=Leica", "-XMP-aux:SerialNumber=12345",
                "-XMP-exifEX:SerialNumber=BODY1", "-XMP-exifEX:OwnerName=Ali", "-XMP-exif:ImageUniqueID=U1",
                "-XMP-dc:Title=Tatil", jpg.getPath());
        String xmp = new String(T.read(extractXmp(jpg)), StandardCharsets.UTF_8);
        T.contains(xmp, "&#39;Leica&#39;", "fixture uses numeric refs");
        MediaInfo info = new MediaInfo();
        XmpEditor.inspect(xmp, info);
        T.eq("ALN-AL00 & <Pura>", info.model, "inspect unescapes");
        T.eq("Huawei 'Leica' \"VARIO\"", info.lensModel, "inspect numeric refs");

        EditSpec spec = T.appleSpec();
        EditReport r = new EditReport();
        String out = XmpEditor.edit(xmp, spec, r);
        T.eq(utf8(xmp), utf8(out), "length preserved");
        Map<String, String> m = exif(out, "exiftool-gen");
        T.eq("Apple", m.get("XMP-tiff:Make"), "Make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "Model");
        T.eq(spec.lensModel, m.get("XMP-exifEX:LensModel"), "LensModel");
        T.eq("Apple", m.get("XMP-exifEX:LensMake"), "LensMake");
        T.eq(spec.lensModel, m.get("XMP-aux:Lens"), "aux:Lens");
        T.eq("26.0.1", m.get("XMP-xmp:CreatorTool"), "CreatorTool");
        T.eq("Tatil", m.get("XMP-dc:Title"), "title kept");
        for (String k : new String[]{"XMP-aux:SerialNumber", "XMP-exifEX:SerialNumber", "XMP-exifEX:OwnerName",
                "XMP-exif:ImageUniqueID"}) {
            T.eq(null, m.get(k), k + " removed");
        }
        T.check(r.changes.contains("XMP tiff:Model: ALN-AL00 & <Pura> → iPhone 17 Pro Max"), "report " + r.changes);
        checkWellFormed(out);
        checkNoNewValidateWarnings(xmp, out, "exiftool-gen");
        assertIdempotent(out, spec);

        // put it back into the JPEG with exiftool and read it from there
        File edited = T.file("xmp-edited.xmp");
        T.write(edited, out.getBytes(StandardCharsets.UTF_8));
        File jpg2 = T.file("xmp-dst.jpg");
        T.write(jpg2, T.read(jpg));
        T.run("exiftool", "-q", "-overwrite_original", "-XMP<=" + edited.getPath(), jpg2.getPath());
        Map<String, String> mj = T.exif(jpg2);
        T.eq("Apple", mj.get("XMP-tiff:Make"), "Make in JPEG");
        T.eq("iPhone 17 Pro Max", mj.get("XMP-tiff:Model"), "Model in JPEG");

        // clearSerials=false keeps the identifiers
        EditSpec keep = T.appleSpec();
        keep.clearSerials = false;
        Map<String, String> mk = exif(XmpEditor.edit(xmp, keep, new EditReport()), "keep-serials");
        T.eq("12345", mk.get("XMP-aux:SerialNumber"), "aux serial kept");
        T.eq("BODY1", mk.get("XMP-exifEX:SerialNumber"), "body serial kept");
    }

    /** &, <, >, ", ', non-ASCII and astral characters round-trip in both forms; control chars are dropped. */
    public static void testSpecialCharacters() throws Exception {
        String value = "Ünlü & <Şirket> \"Pro\" 'Max' 📷 ✓";
        String[] packets = {ADOBE_ATTR, ELEMENT_FORM, CUSTOM_PREFIX};
        int k = 0;
        for (String p : packets) {
            EditSpec s = new EditSpec();
            s.clearSerials = false;
            s.make = value;
            s.model = "Model\u0001\u0000X";
            EditReport r = new EditReport();
            String out = XmpEditor.edit(p, s, r);
            T.eq(utf8(p), utf8(out), "length " + k);
            Map<String, String> m = exif(out, "special-" + k);
            T.eq(value, m.get("XMP-tiff:Make"), "exiftool value " + k);
            T.eq("ModelX", m.get("XMP-tiff:Model"), "control chars dropped " + k);
            MediaInfo info = new MediaInfo();
            XmpEditor.inspect(out, info);
            T.eq(value, info.make, "inspect value " + k);
            T.notContains(out, "<Şirket>", "escaped < > " + k);
            checkWellFormed(out);
            assertIdempotent(out, s);
            k++;
        }
        // values that only differ by entity spelling are "equal": no change
        String pk = ADOBE_ATTR.replace("tiff:Make=\"HUAWEI\"", "tiff:Make=\"A&#39;B &amp; &#x43;\"");
        EditSpec same = new EditSpec();
        same.clearSerials = false;
        same.make = "A'B & C";
        T.check(XmpEditor.edit(pk, same, new EditReport()) == pk, "entity-equal value is unchanged");
        // a value made only of characters XML cannot carry is ignored (it must not become a removal)
        EditSpec ctrl = new EditSpec();
        ctrl.clearSerials = false;
        ctrl.make = "\u0001\u0002";
        T.check(XmpEditor.edit(ADOBE_ATTR, ctrl, new EditReport()) == ADOBE_ATTR, "control-only value ignored");
    }

    /** "" removes attribute and element forms (including rdf:Alt) cleanly. */
    public static void testRemoval() throws Exception {
        EditSpec s = new EditSpec();
        s.make = "";
        s.model = "";
        s.lensModel = "";
        s.software = "";
        s.lensMake = "";
        String[] packets = {ADOBE_ATTR, ELEMENT_FORM, CUSTOM_PREFIX};
        int k = 0;
        for (String p : packets) {
            EditReport r = new EditReport();
            String out = XmpEditor.edit(p, s, r);
            T.eq(utf8(p), utf8(out), "length " + k);
            Map<String, String> m = exif(out, "removal-" + k);
            for (String tag : new String[]{"XMP-tiff:Make", "XMP-tiff:Model", "XMP-exifEX:LensModel", "XMP-aux:Lens",
                    "XMP-xmp:CreatorTool", "XMP-tiff:Software", "XMP-exifEX:LensMake", "XMP-aux:SerialNumber"}) {
                T.eq(null, m.get(tag), tag + " removed (" + k + ")");
            }
            T.check(r.changes.contains("XMP tiff:Make kaldırıldı"), "report " + r.changes);
            checkWellFormed(out);
            assertIdempotent(out, s);
            k++;
        }
        String out = XmpEditor.edit(ELEMENT_FORM, s, new EditReport());
        T.notContains(out, "rdf:Alt", "Alt list removed with its property");
        T.contains(out, "  <!-- written by a phone -->\n  <xmp:Rating>5</xmp:Rating>\n </rdf:Description>", "clean removal");
        T.eq("5", exif(out, "removal-rating").get("XMP-xmp:Rating"), "rating kept");
        String outCustom = XmpEditor.edit(CUSTOM_PREFIX, s, new EditReport());
        T.contains(outCustom, "<tiff:Make>DecoyMake</tiff:Make>", "decoy kept on removal");
    }

    /** Padding absorbs growth and shrinkage; without enough padding (or without a wrapper) the packet length changes. */
    public static void testPaddingPreservation() throws Exception {
        EditSpec grow = new EditSpec();
        grow.clearSerials = false;
        grow.model = "A very long model name with ünïcödé ✓ 📷 ".repeat(10);
        String out = XmpEditor.edit(ADOBE_ATTR, grow, new EditReport());
        T.eq(utf8(ADOBE_ATTR), utf8(out), "grow absorbed by padding");
        T.check(out.endsWith("\n<?xpacket end=\"w\"?>"), "trailer kept");
        T.check(out.contains("</x:xmpmeta>\n"), "root end kept");

        EditSpec shrink = new EditSpec();
        shrink.clearSerials = true;
        shrink.model = "X";
        shrink.make = "";
        String out2 = XmpEditor.edit(ELEMENT_FORM, shrink, new EditReport());
        T.eq(utf8(ELEMENT_FORM), utf8(out2), "shrink compensated");
        checkWellFormed(out2);

        // tiny padding: cannot absorb the growth -> grows, still well-formed
        String small = ADOBE_ATTR.replace(PAD, "  \n");
        String out3 = XmpEditor.edit(small, grow, new EditReport());
        T.check(utf8(out3) > utf8(small), "grows when padding is too small");
        T.check(out3.contains("</x:xmpmeta>\n  \n<?xpacket end"), "padding untouched when insufficient");
        checkWellFormed(out3);

        // no xpacket wrapper: no padding adjustment
        String bare = ADOBE_ATTR.substring(ADOBE_ATTR.indexOf("<x:xmpmeta"), ADOBE_ATTR.indexOf("</x:xmpmeta>") + 12);
        String out4 = XmpEditor.edit(bare, grow, new EditReport());
        T.check(out4.startsWith("<x:xmpmeta") && out4.endsWith("</x:xmpmeta>"), "bare packet edited");
        T.contains(out4, "tiff:Model=\"A very long", "bare value");

        // no padding at all before the end PI: shrinkage creates padding
        String noPad = ADOBE_ATTR.replace(PAD, "");
        String out5 = XmpEditor.edit(noPad, shrink, new EditReport());
        T.eq(utf8(noPad), utf8(out5), "shrink without padding inserts padding");
        checkWellFormed(out5);
    }

    /** All-null spec, equal values, editXmp=false, null spec/packet: the SAME instance and an empty report. */
    public static void testNoOpReturnsSameInstance() throws Exception {
        String[] packets = {ADOBE_ATTR, ELEMENT_FORM, CUSTOM_PREFIX, "", "not xml at all"};
        for (String p : packets) {
            EditSpec none = new EditSpec();
            none.clearSerials = false;
            EditReport r = new EditReport();
            T.check(XmpEditor.edit(p, none, r) == p, "all-null spec");
            EditSpec off = T.appleSpec();
            off.editXmp = false;
            T.check(XmpEditor.edit(p, off, r) == p, "editXmp=false");
            T.check(XmpEditor.edit(p, null, r) == p, "null spec");
            T.check(r.changes.isEmpty() && r.warnings.isEmpty(), "empty report " + r);
        }
        T.check(XmpEditor.edit(null, T.appleSpec(), new EditReport()) == null, "null packet");
        EditSpec same = new EditSpec();
        same.clearSerials = false;
        same.make = "HUAWEI";
        same.model = "ELS-NX9";
        same.lensModel = "HUAWEI P40 Pro Rear Main Camera";
        EditReport r = new EditReport();
        T.check(XmpEditor.edit(ADOBE_ATTR, same, r) == ADOBE_ATTR, "equal values");
        T.check(r.changes.isEmpty(), "no report");
        // report == null is allowed
        T.check(XmpEditor.edit(ADOBE_ATTR, T.appleSpec(), null) != ADOBE_ATTR, "null report");
    }

    /** Properties that do not exist are never created. */
    public static void testAbsentPropertiesNotCreated() throws Exception {
        String p = ADOBE_ATTR.replace("            tiff:Make=\"HUAWEI\"\n", "").replace("            aux:Lens=\"HUAWEI P40 Pro Rear Main Camera\"\n", "");
        EditSpec s = T.appleSpec();
        String out = XmpEditor.edit(p, s, new EditReport());
        T.notContains(out, "tiff:Make", "Make not created");
        T.notContains(out, "aux:Lens", "aux:Lens not created");
        Map<String, String> m = exif(out, "absent");
        T.eq(null, m.get("XMP-tiff:Make"), "exiftool Make absent");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "Model changed");
        String minimal = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:format>image/jpeg</dc:format>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        EditReport r = new EditReport();
        T.check(XmpEditor.edit(minimal, s, r) == minimal, "nothing to change");
        T.check(r.changes.isEmpty() && r.warnings.isEmpty(), "report " + r);
    }

    /** Structures that cannot be edited safely are left alone with a warning (but can still be removed). */
    public static void testUnsupportedStructures() throws Exception {
        String p = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:tiff='http://ns.adobe.com/tiff/1.0/'>"
                + "<tiff:Make rdf:parseType='Resource'><rdf:value>HUAWEI</rdf:value></tiff:Make>"
                + "<tiff:Model rdf:resource='http://example.com/model'/>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        EditSpec s = new EditSpec();
        s.clearSerials = false;
        s.make = "Apple";
        s.model = "iPhone";
        EditReport r = new EditReport();
        T.check(XmpEditor.edit(p, s, r) == p, "unchanged");
        T.eq(2, r.warnings.size(), "warnings " + r.warnings);
        T.contains(r.warnings.get(0), "tiff:Make yapısı desteklenmiyor", "warning text");
        s.make = "";
        s.model = "";
        String out = XmpEditor.edit(p, s, new EditReport());
        T.notContains(out, "tiff:Make", "removed");
        T.notContains(out, "tiff:Model", "removed");
        checkWellFormed(out);
        // self-closing empty element gets a value
        String e = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:tiff='http://ns.adobe.com/tiff/1.0/'><tiff:Make />"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        s.make = "Apple";
        s.model = null;
        String out2 = XmpEditor.edit(e, s, new EditReport());
        T.contains(out2, "<tiff:Make>Apple</tiff:Make>", "self-closing filled");
        checkWellFormed(out2);
    }

    // ================================================================== inspect

    public static void testInspect() throws Exception {
        MediaInfo info = new MediaInfo();
        XmpEditor.inspect(ELEMENT_FORM, info);
        T.check(info.hasXmp, "hasXmp");
        T.eq("samsung", info.make, "make");
        T.eq("SM-S928B", info.model, "model from CDATA");
        T.eq("Samsung Wide", info.lensModel, "x-default li");
        T.eq("S928BXXU1AXB5", info.software, "CreatorTool");
        T.eq(null, info.lensMake, "absent");

        MediaInfo pre = new MediaInfo();
        pre.make = "FromExif";
        pre.lensModel = "ExifLens";
        XmpEditor.inspect(ADOBE_ATTR, pre);
        T.eq("FromExif", pre.make, "existing value kept");
        T.eq("ExifLens", pre.lensModel, "existing lens kept");
        T.eq("ELS-NX9", pre.model, "null filled");
        T.eq("HUAWEI", pre.lensMake, "lens make");
        T.eq("ELS-NX9 10.1.0.150(C432E3R1P1)", pre.software, "software");

        MediaInfo cust = new MediaInfo();
        XmpEditor.inspect(CUSTOM_PREFIX, cust);
        T.eq("Xiaomi", cust.make, "custom prefix attr (decoy ignored)");
        T.eq("23127PN0CG", cust.model, "custom prefix element (decoy attr ignored)");
        T.eq("MIUI Camera", cust.software, "default ns");

        String auxOnly = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:aux='http://ns.adobe.com/exif/1.0/aux/' xmlns:tiff='http://ns.adobe.com/tiff/1.0/'"
                + " aux:Lens='AuxLens' tiff:Software='SW'/></rdf:RDF></x:xmpmeta>";
        MediaInfo a = new MediaInfo();
        XmpEditor.inspect(auxOnly, a);
        T.eq("AuxLens", a.lensModel, "aux:Lens fallback");
        T.eq("SW", a.software, "tiff:Software fallback");

        MediaInfo bad = new MediaInfo();
        XmpEditor.inspect("<x:xmpmeta><broken", bad);
        T.check(bad.hasXmp && bad.make == null, "malformed inspect");
        XmpEditor.inspect(null, new MediaInfo());
        XmpEditor.inspect(ADOBE_ATTR, null);
    }

    // ================================================================== robustness

    public static void testMalformedPackets() throws Exception {
        String[] bad = {
                "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF><tiff:Make>HUAWEI</tiff:Make>",       // unclosed
                "<a xmlns:tiff='http://ns.adobe.com/tiff/1.0/'><tiff:Make>HUAWEI</tiff:Model></a>",  // mismatched
                "<a xmlns:tiff='http://ns.adobe.com/tiff/1.0/' tiff:Make=HUAWEI/>",                   // unquoted
                "<a xmlns:tiff='http://ns.adobe.com/tiff/1.0/' tiff:Make='HUA<WEI'/>",                // < in attr
                "<a xmlns:tiff='http://ns.adobe.com/tiff/1.0/' tiff:Make='HUAWEI",                    // unterminated
                "<!-- never closed <a/>",
                "<![CDATA[ never closed",
                "<?xpacket begin='' ",
                "<!DOCTYPE x [ <!ENTITY a 'b'> ",
                "</a>",
                "<a></b>",
                "<>",
                "< a/>",
                "<a b='1'c='2'/>",
        };
        EditSpec s = T.appleSpec();
        for (String b : bad) {
            EditReport r = new EditReport();
            String out = XmpEditor.edit(b, s, r);
            T.check(out == b, "unchanged: " + b);
            T.check(r.warnings.size() == 1 && r.warnings.get(0).contains("XMP paketi bozuk"), "warning for " + b + ": " + r.warnings);
            MediaInfo info = new MediaInfo();
            XmpEditor.inspect(b, info);
        }
        // tolerated: junk/NULs after the packet trailer, DOCTYPE, PIs
        String junk = ADOBE_ATTR + "\u0000\u0000\u0000";
        String out = XmpEditor.edit(junk, s, new EditReport());
        T.check(out != junk && out.endsWith("\u0000\u0000\u0000"), "trailing NULs kept");
        T.eq(utf8(junk), utf8(out), "length with NUL tail");
    }

    public static void testFuzzNeverThrows() throws Exception {
        String[] seeds = {ADOBE_ATTR, ELEMENT_FORM, CUSTOM_PREFIX};
        Random rnd = new Random(4242);
        EditSpec s = T.appleSpec();
        EditSpec rm = new EditSpec();
        rm.make = "";
        rm.model = "";
        rm.lensModel = "";
        String alphabet = "<>/='\"&;:!?[]-x \n#é€📷";
        int n = 0;
        for (String seed : seeds) {
            for (int it = 0; it < 700; it++) {
                StringBuilder sb = new StringBuilder(seed);
                int mode = rnd.nextInt(3);
                if (mode == 0) {
                    sb.setLength(rnd.nextInt(seed.length() + 1));
                } else {
                    int muts = 1 + rnd.nextInt(5);
                    for (int m = 0; m < muts && sb.length() > 0; m++) {
                        int pos = rnd.nextInt(sb.length());
                        char c = alphabet.charAt(rnd.nextInt(alphabet.length()));
                        if (mode == 1) sb.setCharAt(pos, c);
                        else if (rnd.nextBoolean()) sb.insert(pos, c);
                        else sb.deleteCharAt(pos);
                    }
                }
                String in = sb.toString();
                EditReport r = new EditReport();
                String out;
                try {
                    out = XmpEditor.edit(in, (it & 1) == 0 ? s : rm, r);
                    XmpEditor.inspect(in, new MediaInfo());
                    if (out != in) XmpEditor.inspect(out, new MediaInfo());
                } catch (Throwable t) {
                    throw new AssertionError("fuzz #" + n + " threw " + t + " for:\n" + in, t);
                }
                T.check(out != null, "non-null");
                T.check(!r.warnings.contains(XmpEditor.INTERNAL_ERROR_WARNING), "internal error #" + n + ":\n" + in);
                if (out != in) {
                    // an edited packet must still be well-formed for our own scanner (re-edit is a no-op)
                    EditReport r2 = new EditReport();
                    String again = XmpEditor.edit(out, (it & 1) == 0 ? s : rm, r2);
                    T.check(again == out, "re-edit no-op #" + n + " " + r2 + "\n" + out);
                }
                n++;
            }
        }
    }

    // ================================================================== helpers

    private static void assertIdempotent(String out, EditSpec spec) {
        EditReport r = new EditReport();
        T.check(XmpEditor.edit(out, spec, r) == out, "second edit must return the same instance: " + r);
        T.check(r.changes.isEmpty(), "second edit report " + r.changes);
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String padding(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) sb.append(" ".repeat(99)).append('\n');
        return sb.toString();
    }

    /** Multi-megabyte attribute values (e.g. GDepth/GImage base64 data) stay linear-time and untouched. */
    public static void testLargePacket() throws Exception {
        StringBuilder data = new StringBuilder(6_000_000);
        Random rnd = new Random(7);
        String b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 6_000_000; i++) data.append(b64.charAt(rnd.nextInt(64)));
        StringBuilder attrs = new StringBuilder();
        for (int i = 0; i < 200; i++) attrs.append("\n   GDepth:Extra").append(i).append("=\"").append(i).append('"');
        String p = "<?xpacket begin=\"" + BOM + "\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "<rdf:Description rdf:about=\"\" xmlns:GDepth=\"http://ns.google.com/photos/1.0/depthmap/\""
                + " xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" tiff:Make=\"Google\""
                + attrs + "\n   GDepth:Data=\"" + data + "\"" + attrs.toString().replace("Extra", "More") + "/>\n"
                + "</rdf:RDF></x:xmpmeta>\n" + PAD + "<?xpacket end=\"w\"?>";
        long t0 = System.nanoTime();
        EditSpec s = T.appleSpec();
        EditReport r = new EditReport();
        String out = XmpEditor.edit(p, s, r);
        MediaInfo info = new MediaInfo();
        XmpEditor.inspect(out, info);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        T.check(ms < 5000, "too slow: " + ms + " ms");
        T.eq("Apple", info.make, "edited");
        T.eq(utf8(p), utf8(out), "length");
        T.contains(out, "GDepth:Data=\"" + data + "\"", "data untouched");
        T.check(r.changes.contains("XMP tiff:Make: Google → Apple"), "report " + r.changes);
    }

    private static Map<String, String> exif(String packet, String name) throws IOException {
        File f = T.file("xmp-" + name + ".xmp");
        T.write(f, packet.getBytes(StandardCharsets.UTF_8));
        return T.exif(f);
    }

    private static File extractXmp(File jpg) throws IOException {
        File out = T.file(jpg.getName() + ".xmp");
        T.run("sh", "-c", "exiftool -xmp -b '" + jpg.getPath() + "' > '" + out.getPath() + "'");
        return out;
    }

    private static int wfCounter;

    /** Strict XML parse with Python's expat (skipped when python3 is missing). */
    private static void checkWellFormed(String xml) throws IOException {
        if (!T.hasTool("python3")) return;
        File f = T.file("wf-" + (wfCounter++) + ".xml");
        T.write(f, xml.getBytes(StandardCharsets.UTF_8));
        T.Result r = T.exec("python3", "-I", "-c",
                "import sys, xml.dom.minidom\nxml.dom.minidom.parse(sys.argv[1])\nprint('OK')", f.getPath());
        T.check(r.exit == 0 && r.out.trim().equals("OK"), "not well-formed XML: " + r.out + "\n" + xml);
    }

    /** exiftool -validate on .xmp files: the edited packet must not add warnings. */
    private static void checkNoNewValidateWarnings(String before, String after, String name) throws IOException {
        File a = T.file("val-a-" + name + ".xmp");
        File b = T.file("val-b-" + name + ".xmp");
        T.write(a, before.getBytes(StandardCharsets.UTF_8));
        T.write(b, after.getBytes(StandardCharsets.UTF_8));
        List<String> wa = warningLines(T.validate(a));
        List<String> wb = warningLines(T.validate(b));
        for (String w : wb) T.check(wa.contains(w), "new exiftool warning: " + w + " (before: " + wa + ")");
    }

    private static List<String> warningLines(String out) {
        List<String> l = new ArrayList<String>();
        for (String line : out.split("\n")) {
            if (line.trim().isEmpty() || line.contains("Validate") || line.startsWith("========")) continue;
            l.add(line.replaceAll("\\s+", " ").trim());
        }
        return l;
    }

    /** Test-side XML text escaping (mirrors what a correct writer may produce for plain text). */
    static final class XmlEsc {
        static String text(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
        }
    }
}
