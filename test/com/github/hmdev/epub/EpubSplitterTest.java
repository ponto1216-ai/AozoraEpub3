package com.github.hmdev.epub;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class EpubSplitterTest {
    @Test public void parsesRangesAndCreatesFixedRanges() throws Exception {
        List<EpubSplitter.Range> parsed=EpubSplitter.parseRanges(" 1 - 2, 3- ", 4);
        assertEquals("1-2", parsed.get(0).toString()); assertEquals("3-4", parsed.get(1).toString());
        assertEquals("1-3", EpubSplitter.createFixedRanges(3,7).get(0).toString()); assertEquals("7-7", EpubSplitter.createFixedRanges(3,7).get(2).toString());
        try { EpubSplitter.parseRanges("1-2,2-3",3); fail(); } catch (java.io.IOException expected) { }
        try { EpubSplitter.parseRanges("0-2",3); fail(); } catch (java.io.IOException expected) { }
        try { EpubSplitter.parseRanges("2-4",3); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void splitsEpub2AndRebuildsNavigation() throws Exception {
        File input=File.createTempFile("split-source", ".epub"), output=File.createTempFile("split-output", ".epub"), noCommon=File.createTempFile("split-no-common", ".epub");
        try { makeEpub(input); EpubSplitter.Analysis a=EpubSplitter.analyze(input); assertEquals("Book",a.title); assertEquals(3,a.chapters.size()); assertEquals(2,a.commonSpineCount);
            assertEquals(2,a.sections.size()); assertEquals("Part A",a.sections.get(0).title); assertEquals(1,a.sections.get(0).startChapter); assertEquals(2,a.sections.get(0).endChapter); assertEquals(3,a.sections.get(1).startChapter);
            EpubSplitter.split(input,output,new EpubSplitter.Range(1,2),true);
            try(ZipFile z=new ZipFile(output)) {
                assertEquals(ZipEntry.STORED,z.getEntry("mimetype").getMethod()); assertNotNull(z.getEntry("OEBPS/text/ch1.xhtml")); assertNotNull(z.getEntry("OEBPS/text/ch2.xhtml")); assertNull(z.getEntry("OEBPS/text/ch3.xhtml"));
                String opf=text(z,"OEBPS/book.opf"); assertTrue(opf.contains("Book（1-2）")); assertTrue(opf.contains("urn:uuid:")); assertFalse(opf.contains("original-book-id")); assertTrue(opf.contains("idref=\"ch1\"")); assertFalse(opf.contains("idref=\"ch3\"")); assertFalse(opf.contains("href=\"text/ch3.xhtml\""));
                String ncx=text(z,"OEBPS/toc.ncx"); assertTrue(ncx.contains("<!DOCTYPE ncx PUBLIC")); assertTrue(ncx.contains("Book（1-2）")); assertTrue(ncx.contains("urn:uuid:")); assertFalse(ncx.contains("original-book-id")); assertTrue(ncx.contains("ch1.xhtml")); assertTrue(ncx.contains("ch2.xhtml")); assertFalse(ncx.contains("ch3.xhtml")); assertTrue(ncx.contains("playOrder=\"1\"")); assertTrue(ncx.contains("playOrder=\"2\""));
                String nav=text(z,"OEBPS/nav.xhtml"); assertTrue(nav.contains("<!DOCTYPE html>")); assertTrue(nav.contains("ch1.xhtml")); assertFalse(nav.contains("ch3.xhtml"));
                assertEquals("<html><head><title>Cover</title></head><body/></html>", text(z,"OEBPS/cover.xhtml"));
            }
            try { EpubSplitter.split(input,input,new EpubSplitter.Range(1,1),true); fail(); } catch (java.io.IOException expected) { }
            assertEquals(3, EpubSplitter.analyze(input).chapters.size());
            EpubSplitter.split(input,noCommon,new EpubSplitter.Range(2,3),false);
            try(ZipFile z=new ZipFile(noCommon)) {
                String opf=text(z,"OEBPS/book.opf"); assertNull(z.getEntry("OEBPS/cover.xhtml")); assertNotNull(z.getEntry("OEBPS/nav.xhtml")); assertTrue(opf.contains("href=\"nav.xhtml\"")); assertFalse(opf.contains("idref=\"nav\""));
                String nav=text(z,"OEBPS/nav.xhtml"); assertFalse(nav.contains("ch1.xhtml")); assertTrue(nav.contains("ch2.xhtml")); assertTrue(nav.contains("ch3.xhtml"));
            }
        } finally { Files.deleteIfExists(input.toPath()); Files.deleteIfExists(output.toPath()); Files.deleteIfExists(noCommon.toPath()); }
    }
    private static void makeEpub(File f)throws Exception { try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(f.toPath()))){
        put(z,"mimetype","application/epub+zip"); put(z,"META-INF/container.xml","<container><rootfiles><rootfile full-path=\"OEBPS/book.opf\"/></rootfiles></container>");
        put(z,"OEBPS/book.opf","<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\" unique-identifier=\"book-id\"><metadata><dc:title>Book</dc:title><dc:identifier id=\"book-id\">original-book-id</dc:identifier></metadata><manifest><item id=\"cover\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/><item id=\"ch1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"ch2\" href=\"text/ch2.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"ch3\" href=\"text/ch3.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine toc=\"ncx\"><itemref idref=\"cover\"/><itemref idref=\"nav\"/><itemref idref=\"ch1\"/><itemref idref=\"ch2\"/><itemref idref=\"ch3\"/></spine><guide><reference type=\"cover\" href=\"cover.xhtml\"/><reference type=\"toc\" href=\"nav.xhtml\"/></guide></package>");
        put(z,"OEBPS/cover.xhtml","<html><head><title>Cover</title></head><body/></html>"); put(z,"OEBPS/nav.xhtml","<!DOCTYPE html><html xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol><li><a href=\"text/ch1.xhtml\">Part A</a><ol><li><a href=\"text/ch2.xhtml\">Two</a></li></ol></li><li><a href=\"text/ch3.xhtml\">Part B</a></li></ol></nav></body></html>");
        put(z,"OEBPS/toc.ncx","<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\"><ncx><head><meta name=\"dtb:uid\" content=\"original-book-id\"/></head><docTitle><text>Book</text></docTitle><navMap><navPoint id=\"n1\" playOrder=\"9\"><navLabel><text>Part A</text></navLabel><content src=\"text/ch1.xhtml\"/><navPoint id=\"n2\" playOrder=\"8\"><navLabel><text>Two</text></navLabel><content src=\"text/ch2.xhtml\"/></navPoint></navPoint><navPoint id=\"n3\" playOrder=\"7\"><navLabel><text>Part B</text></navLabel><content src=\"text/ch3.xhtml\"/></navPoint></navMap></ncx>");
        for(int i=1;i<=3;i++)put(z,"OEBPS/text/ch"+i+".xhtml","<html><head><title>Chapter "+i+"</title></head><body>"+i+"</body></html>");
    }}
    private static void put(ZipOutputStream z,String n,String s)throws Exception{z.putNextEntry(new ZipEntry(n));z.write(s.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    private static String text(ZipFile z,String n)throws Exception{return new String(z.getInputStream(z.getEntry(n)).readAllBytes(),StandardCharsets.UTF_8);}
}
