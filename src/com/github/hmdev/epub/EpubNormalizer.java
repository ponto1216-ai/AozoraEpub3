package com.github.hmdev.epub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Repairs common EPUB metadata and navigation errors without changing the book content. */
public final class EpubNormalizer {
    private static final Pattern ROOTFILE = Pattern.compile("(?is)<rootfile\\b[^>]*\\bfull-path\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern RENDITION_WRITING_MODE = Pattern.compile("(?is)\\s*<meta\\b(?=[^>]*\\bproperty\\s*=\\s*['\"]rendition:writing-mode['\"])[^>]*(?:/>|>.*?</meta\\s*>)");
    private static final Pattern LOCAL_REFERENCE = Pattern.compile("(?is)(?:\\b(?:src|href)\\s*=\\s*['\"]|url\\(\\s*['\"]?)([^'\"\\s)#]+)");

    private EpubNormalizer() { }

    public static final class Preview {
        public String title;
        public int removedRenditionWritingMode;
        public int removedBrokenNavigationLinks;
        public int addedManifestItems;
        public int removedUnmanifestedResources;
        public final List<String> brokenNavigationTargets = new ArrayList<String>();
        public final List<String> unmanifestedResources = new ArrayList<String>();
        public final List<String> removedResourceNames = new ArrayList<String>();
        public boolean needsNormalization() { return removedRenditionWritingMode > 0 || removedBrokenNavigationLinks > 0 || addedManifestItems > 0 || removedUnmanifestedResources > 0; }
    }

    public static Preview preview(File source) throws IOException {
        if (!source.isFile()) throw new IOException("EPUBファイルがありません: " + source.getPath());
        try (ZipFile zip = new ZipFile(source)) {
            String opfName = rootfile(zip);
            String opf = text(zip, opfName);
            Preview preview = new Preview();
            preview.title = titleOf(opf);
            if (preview.title.isEmpty()) preview.title = source.getName().replaceFirst("(?i)\\.epub$", "");
            Matcher rendition = RENDITION_WRITING_MODE.matcher(opf);
            while (rendition.find()) preview.removedRenditionWritingMode++;
            preview.unmanifestedResources.addAll(findUnmanifestedResources(zip, opfName, opf));
            List<String> referenced = findReferencedUnmanifestedResources(zip, opfName, opf, preview.unmanifestedResources);
            preview.addedManifestItems = referenced.size();
            for (String entry : preview.unmanifestedResources) if (!referenced.contains(entry)) preview.removedResourceNames.add(entry);
            preview.removedUnmanifestedResources = preview.removedResourceNames.size();
            for (String navName : findNavigationEntries(opfName, opf)) {
                ZipEntry navEntry = zip.getEntry(navName);
                if (navEntry != null) findBrokenNavigationLinks(zip, navName, parse(read(zip, navEntry)), preview.brokenNavigationTargets);
            }
            preview.removedBrokenNavigationLinks = preview.brokenNavigationTargets.size();
            return preview;
        }
    }

    /** @return true if an output EPUB was written; false when no repair was necessary. */
    public static boolean process(File source, File output) throws IOException {
        if (source.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("元のEPUBとは別の保存先を指定してください");
        if (!preview(source).needsNormalization()) return false;
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("保存先フォルダーを作成できません: " + parent);
        File temporary = File.createTempFile("AozoraEpub3-normalize-", ".epub", parent);
        try (ZipFile input = new ZipFile(source); ZipArchiveOutputStream zip = new ZipArchiveOutputStream(temporary)) {
            String opfName = rootfile(input);
            String opf = text(input, opfName);
            List<String> navEntries = findNavigationEntries(opfName, opf);
            List<String> unmanifested = findUnmanifestedResources(input, opfName, opf);
            List<String> referencedUnmanifested = findReferencedUnmanifestedResources(input, opfName, opf, unmanifested);
            List<String> removedResources = new ArrayList<String>();
            for (String entry : unmanifested) if (!referencedUnmanifested.contains(entry)) removedResources.add(entry);
            ZipEntry mimetype = input.getEntry("mimetype");
            if (mimetype == null) throw new IOException("EPUBではありません: mimetype がありません");
            writeMimetype(input, mimetype, zip);
            for (ZipEntry entry : Collections.list(input.entries())) {
                if (entry.isDirectory() || "mimetype".equals(entry.getName()) || removedResources.contains(entry.getName())) continue;
                zip.putArchiveEntry(new ZipArchiveEntry(entry.getName()));
                if (entry.getName().equals(opfName)) zip.write(rewriteOpf(opfName, opf, referencedUnmanifested).getBytes(StandardCharsets.UTF_8));
                else if (navEntries.contains(entry.getName())) zip.write(serialize(removeBrokenNavigationLinks(input, entry.getName(), parse(read(input, entry)))));
                else copy(input, entry, zip);
                zip.closeArchiveEntry();
            }
        } catch (Exception e) {
            Files.deleteIfExists(temporary.toPath());
            if (e instanceof IOException io) throw io;
            throw new IOException("EPUB正規化に失敗しました: " + e.getMessage(), e);
        }
        try { Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary.toPath()); }
        return true;
    }

    private static String rootfile(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) throw new IOException("EPUBではありません: container.xml がありません");
        Matcher matcher = ROOTFILE.matcher(text(zip, container.getName()));
        if (!matcher.find() || zip.getEntry(matcher.group(1)) == null) throw new IOException("EPUBのOPFを検出できません");
        return matcher.group(1);
    }

    private static List<String> findNavigationEntries(String opfName, String opf) {
        List<String> result = new ArrayList<String>();
        Matcher items = Pattern.compile("(?is)<item\\b[^>]*>").matcher(opf);
        while (items.find()) {
            String item = items.group();
            String href = attribute(item, "href");
            String properties = attribute(item, "properties");
            if (href != null && properties != null && Pattern.compile("(?:^|\\s)nav(?:\\s|$)").matcher(properties).find()) result.add(resolve(opfName, href));
        }
        return result;
    }

    private static List<String> findUnmanifestedResources(ZipFile zip, String opfName, String opf) {
        List<String> result = new ArrayList<String>();
        List<String> manifestEntries = new ArrayList<String>();
        Matcher items = Pattern.compile("(?is)<item\\b[^>]*>").matcher(opf);
        while (items.find()) { String href = attribute(items.group(), "href"); if (href != null) manifestEntries.add(resolve(opfName, href)); }
        for (ZipEntry entry : Collections.list(zip.entries())) {
            String name = entry.getName();
            if (entry.isDirectory() || "mimetype".equals(name) || name.equals(opfName) || name.startsWith("META-INF/") || manifestEntries.contains(name)) continue;
            result.add(name);
        }
        return result;
    }

    private static String rewriteOpf(String opfName, String opf, List<String> missing) throws IOException {
        String normalized = RENDITION_WRITING_MODE.matcher(opf).replaceAll("");
        if (missing.isEmpty()) return normalized;
        StringBuilder items = new StringBuilder();
        int number = 1;
        for (String entry : missing) {
            String id = "aozoraepub3-resource-" + number++;
            while (Pattern.compile("(?is)\\bid\\s*=\\s*['\"]" + Pattern.quote(id) + "['\"]").matcher(normalized).find()) id += "-x";
            items.append("\n    <item id=\"").append(id).append("\" href=\"").append(relative(opfName, entry)).append("\" media-type=\"").append(mediaType(entry)).append("\"/>");
        }
        Matcher end = Pattern.compile("(?is)</manifest\\s*>").matcher(normalized);
        if (!end.find()) throw new IOException("EPUBのmanifestを検出できません");
        return end.replaceFirst(Matcher.quoteReplacement(items + "\n  </manifest>"));
    }

    private static List<String> findReferencedUnmanifestedResources(ZipFile zip, String opfName, String opf, List<String> unmanifested) throws IOException {
        List<String> result = new ArrayList<String>();
        List<String> manifestEntries = new ArrayList<String>();
        Matcher items = Pattern.compile("(?is)<item\\b[^>]*>").matcher(opf);
        while (items.find()) { String href = attribute(items.group(), "href"); if (href != null) manifestEntries.add(resolve(opfName, href)); }
        for (String entryName : manifestEntries) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || !entryName.matches("(?i).*\\.(xhtml|html|htm|css|svg)$")) continue;
            Matcher references = LOCAL_REFERENCE.matcher(text(zip, entryName));
            while (references.find()) {
                String reference = references.group(1);
                if (reference.startsWith("#") || reference.startsWith("//") || reference.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) continue;
                String target = resolve(entryName, reference);
                if (unmanifested.contains(target) && !result.contains(target)) result.add(target);
            }
        }
        return result;
    }

    private static String mediaType(String entry) {
        String lower = entry.toLowerCase();
        if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) return "application/xhtml+xml";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static void findBrokenNavigationLinks(ZipFile zip, String navName, Document document, List<String> targets) {
        for (Element anchor : descendants(document, "a")) {
            String href = anchor.getAttribute("href");
            if (href.isEmpty() || href.startsWith("#") || href.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) continue;
            String target = resolve(navName, href.split("#", 2)[0]);
            if (zip.getEntry(target) == null && !targets.contains(target)) targets.add(target);
        }
    }

    private static Document removeBrokenNavigationLinks(ZipFile zip, String navName, Document document) {
        List<Element> broken = new ArrayList<Element>();
        for (Element anchor : descendants(document, "a")) {
            String href = anchor.getAttribute("href");
            if (href.isEmpty() || href.startsWith("#") || href.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) continue;
            if (zip.getEntry(resolve(navName, href.split("#", 2)[0])) == null) broken.add(anchor);
        }
        for (Element anchor : broken) {
            Node remove = anchor;
            for (Node candidate = anchor.getParentNode(); candidate instanceof Element parent; candidate = candidate.getParentNode()) {
                if ("li".equalsIgnoreCase(parent.getLocalName()) || "li".equalsIgnoreCase(parent.getNodeName())) { remove = parent; break; }
            }
            Node parent = remove.getParentNode();
            if (parent != null) parent.removeChild(remove);
        }
        return document;
    }

    private static List<Element> descendants(Document document, String localName) {
        List<Element> result = new ArrayList<Element>();
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element e) result.add(e);
        if (result.isEmpty()) {
            nodes = document.getElementsByTagName(localName);
            for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element e) result.add(e);
        }
        return result;
    }

    private static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) { throw new IOException("XMLを解析できません: " + e.getMessage(), e); }
    }

    private static byte[] serialize(Document document) throws IOException {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            DocumentType type = document.getDoctype();
            if (type != null) {
                if (type.getPublicId() != null) transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, type.getPublicId());
                if (type.getSystemId() != null) transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, type.getSystemId());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            String text = out.toString(StandardCharsets.UTF_8);
            if (type != null && type.getSystemId() == null && type.getPublicId() == null) {
                int declarationEnd = text.indexOf("?>");
                String declaration = declarationEnd < 0 ? "" : text.substring(0, declarationEnd + 2);
                String body = declarationEnd < 0 ? text : text.substring(declarationEnd + 2);
                text = declaration + "\n<!DOCTYPE " + type.getName() + ">" + body;
            }
            return text.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IOException("XMLを書き出せません: " + e.getMessage(), e); }
    }

    private static String titleOf(String opf) { Matcher m = Pattern.compile("(?is)<[^:>]*:?title\\b[^>]*>(.*?)</[^:>]*:?title\\s*>").matcher(opf); return m.find() ? m.group(1).replaceAll("(?is)<[^>]+>", "").trim() : ""; }
    private static String attribute(String tag, String name) { Matcher m = Pattern.compile("(?is)\\b" + Pattern.quote(name) + "\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(tag); return m.find() ? m.group(1) : null; }
    private static String resolve(String from, String href) { Path parent = Path.of(from).getParent(); return (parent == null ? Path.of(href) : parent.resolve(href)).normalize().toString().replace('\\', '/'); }
    private static String relative(String from, String to) { Path parent = Path.of(from).getParent(); return (parent == null ? Path.of(to) : parent.relativize(Path.of(to))).toString().replace('\\', '/'); }
    private static String text(ZipFile zip, String name) throws IOException { return new String(read(zip, zip.getEntry(name)), StandardCharsets.UTF_8); }
    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException { if (entry == null) throw new IOException("EPUB内のファイルを読めません"); try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) { in.transferTo(out); return out.toByteArray(); } }
    private static void copy(ZipFile in, ZipEntry entry, ZipArchiveOutputStream out) throws IOException { try (InputStream stream = in.getInputStream(entry)) { stream.transferTo(out); } }
    private static void writeMimetype(ZipFile in, ZipEntry entry, ZipArchiveOutputStream out) throws IOException { byte[] b = read(in, entry); ZipArchiveEntry e = new ZipArchiveEntry("mimetype"); e.setMethod(ZipArchiveEntry.STORED); e.setSize(b.length); CRC32 c = new CRC32(); c.update(b); e.setCrc(c.getValue()); out.putArchiveEntry(e); out.write(b); out.closeArchiveEntry(); }
}
