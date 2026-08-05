package com.github.hmdev.epub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class EpubNormalizerTest {
    @Test public void removesInvalidRenditionPropertyAndBrokenLandmark() throws Exception {
        File input = createEpub();
        EpubNormalizer.Preview preview = EpubNormalizer.preview(input);
        assertEquals(1, preview.removedRenditionWritingMode);
        assertEquals(1, preview.removedBrokenNavigationLinks);
        assertEquals(1, preview.addedManifestItems);
        assertEquals(1, preview.removedUnmanifestedResources);
        assertEquals("OEBPS/cover.xhtml", preview.brokenNavigationTargets.get(0));

        File output = File.createTempFile("normalized-", ".epub"); output.delete();
        assertTrue(EpubNormalizer.process(input, output));
        try (ZipFile zip = new ZipFile(output)) {
            assertFalse(read(zip, "OEBPS/content.opf").contains("rendition:writing-mode"));
            assertTrue(read(zip, "OEBPS/content.opf").contains("extra.css"));
            assertTrue(zip.getEntry("OEBPS/extra.css") != null);
            assertTrue(zip.getEntry("OEBPS/orphan.json") == null);
            String nav = read(zip, "OEBPS/nav.xhtml");
            assertFalse(nav.contains("cover.xhtml"));
            assertFalse(nav.matches("(?is).*<li\\s*/>.*"));
            assertTrue(nav.contains("chapter.xhtml"));
            assertTrue(nav.contains("<!DOCTYPE html>"));
        }
        File untouchedOutput = File.createTempFile("normalized-untouched-", ".epub");
        Files.deleteIfExists(untouchedOutput.toPath());
        assertFalse(EpubNormalizer.process(output, untouchedOutput));
        assertFalse(untouchedOutput.exists());
    }

    private static File createEpub() throws Exception {
        File file = File.createTempFile("normalizer-input-", ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>");
            put(zip, "OEBPS/content.opf", "<?xml version=\"1.0\"?><package><metadata><dc:title xmlns:dc=\"x\">Test</dc:title><meta property=\"rendition:writing-mode\">vertical-rl</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>");
            put(zip, "OEBPS/nav.xhtml", "<?xml version=\"1.0\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>nav</title></head><body><nav><ol><li><a href=\"cover.xhtml\">cover</a></li><li><a href=\"chapter.xhtml\">chapter</a></li></ol></nav></body></html>");
            put(zip, "OEBPS/chapter.xhtml", "<html><head><title>chapter</title><link href=\"extra.css\" rel=\"stylesheet\"/></head><body>text</body></html>");
            put(zip, "OEBPS/extra.css", "body { color: black; }");
            put(zip, "OEBPS/orphan.json", "{}");
        }
        return file;
    }
    private static void put(ZipOutputStream zip, String name, String value) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private static String read(ZipFile zip, String name) throws Exception { return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8); }
}
