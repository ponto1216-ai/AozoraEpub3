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

public class EpubLayoutProcessorTest {
    @Test public void addsLateStylesheetAndLinksEveryHtmlDocument() throws Exception {
        File input = createEpub(false);
        File output = File.createTempFile("layout-output-", ".epub");
        output.delete();
        EpubLayoutProcessor.Preview preview = EpubLayoutProcessor.preview(input);
        assertFalse(preview.fixedLayout);
        assertEquals(2, preview.contentDocumentCount);

        EpubLayoutProcessor.process(input, output, EpubLayoutProcessor.Direction.VERTICAL);
        try (ZipFile zip = new ZipFile(output)) {
            assertTrue(zip.getEntry("OEBPS/aozoraepub3-layout.css") != null);
            String opf = read(zip, "OEBPS/content.opf");
            assertTrue(opf.contains("aozoraepub3-layout.css"));
            String text = read(zip, "OEBPS/text/one.xhtml");
            assertTrue(text.contains("data-aozoraepub3-layout=\"true\""));
            assertTrue(text.contains("../aozoraepub3-layout.css"));
            assertTrue(read(zip, "OEBPS/nav.xhtml").contains("aozoraepub3-layout.css"));
            assertTrue(read(zip, "OEBPS/aozoraepub3-layout.css").contains("vertical-rl"));
        }
    }

    @Test public void rejectsFixedLayoutEpub() throws Exception {
        File input = createEpub(true);
        assertTrue(EpubLayoutProcessor.preview(input).fixedLayout);
        File output = File.createTempFile("layout-output-", ".epub");
        output.delete();
        try {
            EpubLayoutProcessor.process(input, output, EpubLayoutProcessor.Direction.HORIZONTAL);
            throw new AssertionError("fixed-layout EPUB must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("固定レイアウト"));
        }
    }

    private static File createEpub(boolean fixed) throws Exception {
        File file = File.createTempFile("layout-input-", ".epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>");
            put(zip, "OEBPS/content.opf", "<?xml version=\"1.0\"?><package><metadata><dc:title xmlns:dc=\"x\">Test</dc:title>" + (fixed ? "<meta property=\"rendition:layout\">pre-paginated</meta>" : "") + "</metadata><manifest><item id=\"one\" href=\"text/one.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/></manifest><spine><itemref idref=\"one\"/></spine></package>");
            put(zip, "OEBPS/text/one.xhtml", "<html><head><title>one</title></head><body><p>本文</p></body></html>");
            put(zip, "OEBPS/nav.xhtml", "<html><head><title>nav</title></head><body><nav>目次</nav></body></html>");
        }
        return file;
    }
    private static void put(ZipOutputStream zip, String name, String value) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private static String read(ZipFile zip, String name) throws Exception { return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8); }
}
