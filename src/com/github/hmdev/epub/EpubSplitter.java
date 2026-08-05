package com.github.hmdev.epub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

/** Splits ordinary reflowable EPUBs at their spine-document boundaries. */
public final class EpubSplitter {
    private EpubSplitter() { }

    public static final class Range {
        public final int start;
        public final int end;
        public Range(int start, int end) {
            if (start < 1 || end < start) throw new IllegalArgumentException("Invalid chapter range: " + start + "-" + end);
            this.start = start; this.end = end;
        }
        @Override public String toString() { return start + "-" + end; }
    }

    public static final class Chapter {
        public final int number;
        public final String title;
        public final String entryName;
        private final String id;
        private Chapter(int number, String title, String entryName, String id) {
            this.number = number; this.title = title; this.entryName = entryName; this.id = id;
        }
    }

    public static final class Section {
        public final int number;
        public final String title;
        public final int startChapter;
        public final int endChapter;
        private Section(int number, String title, int startChapter, int endChapter) {
            this.number = number; this.title = title; this.startChapter = startChapter; this.endChapter = endChapter;
        }
    }

    public static final class Analysis {
        public final String title;
        public final String opfEntryName;
        public final int commonSpineCount;
        public final List<Chapter> chapters;
        public final List<Section> sections;
        private final Map<String, Item> manifest;
        private final List<SpineRef> spine;
        private final Set<String> commonIds;
        private final Set<String> tocEntries;
        private final String identifier;
        private Analysis(String title, String opfEntryName, int commonSpineCount, List<Chapter> chapters,
                List<Section> sections, Map<String, Item> manifest, List<SpineRef> spine, Set<String> commonIds,
                Set<String> tocEntries, String identifier) {
            this.title = title; this.opfEntryName = opfEntryName; this.commonSpineCount = commonSpineCount;
            this.chapters = Collections.unmodifiableList(chapters); this.sections = Collections.unmodifiableList(sections);
            this.manifest = manifest; this.spine = spine;
            this.commonIds = commonIds; this.tocEntries = tocEntries; this.identifier = identifier;
        }
    }

    private static final class Item {
        final String id, href, entryName, mediaType, properties;
        Item(String id, String href, String entryName, String mediaType, String properties) {
            this.id=id; this.href=href; this.entryName=entryName; this.mediaType=mediaType; this.properties=properties == null ? "" : properties;
        }
    }
    private static final class SpineRef { final String id; SpineRef(String id) { this.id=id; } }
    private static final class NavSection {
        final String title;
        final Set<String> targets = new HashSet<String>();
        final List<NavSection> children = new ArrayList<NavSection>();
        NavSection(String title) { this.title = title == null ? "" : title.trim(); }
    }

    public static Analysis analyze(File source) throws IOException {
        if (!source.isFile()) throw new IOException("EPUB file does not exist: " + source);
        try (ZipFile zip = new ZipFile(source)) {
            String opfName = readRootfile(zip);
            Document opf = parse(read(zip, opfName));
            Map<String, Item> manifest = new LinkedHashMap<String, Item>();
            for (Element e : descendants(opf, "item")) {
                String id = attr(e, "id"), href = attr(e, "href");
                if (!id.isEmpty() && !href.isEmpty()) manifest.put(id, new Item(id, href, resolve(opfName, href), attr(e, "media-type"), attr(e, "properties")));
            }
            String title = textOfFirst(opf, "title");
            if (title.isEmpty()) title = source.getName().replaceFirst("(?i)\\.epub$", "");
            String identifier = findUniqueIdentifier(opf);
            List<SpineRef> spine = new ArrayList<SpineRef>();
            Element spineElement = first(opf, "spine");
            if (spineElement != null) for (Element e : childElements(spineElement, "itemref")) {
                String id = attr(e, "idref"); if (manifest.containsKey(id)) spine.add(new SpineRef(id));
            }
            Set<String> commonIds = new HashSet<String>();
            Set<String> tocEntries = new HashSet<String>();
            for (Item item : manifest.values()) {
                String mark = (item.id + " " + item.href + " " + item.properties).toLowerCase();
                boolean navigation = item.properties.matches("(?is).*\\bnav\\b.*")
                        || item.mediaType.toLowerCase().contains("ncx")
                        || mark.matches(".*\\b(toc|nav|contents?)\\b.*");
                if (navigation) tocEntries.add(item.entryName);
                if (mark.matches(".*\\b(cover|title|toc|nav|contents?)\\b.*")) commonIds.add(item.id);
            }
            for (Element reference : descendants(opf, "reference")) {
                String type = attr(reference, "type").toLowerCase();
                String target = resolve(opfName, attr(reference, "href"));
                if (type.contains("cover") || type.contains("title") || type.contains("toc")) {
                    for (Item item : manifest.values()) if (item.entryName.equals(target)) commonIds.add(item.id);
                    if (type.contains("toc")) tocEntries.add(target);
                }
            }
            Map<String, String> navTitles = readNavigationTitles(zip, opfName, manifest, tocEntries);
            List<Chapter> chapters = new ArrayList<Chapter>();
            int commonCount = 0;
            for (SpineRef ref : spine) {
                Item item = manifest.get(ref.id);
                if (commonIds.contains(ref.id)) { commonCount++; continue; }
                String chapterTitle = navTitles.get(item.entryName);
                if (chapterTitle == null || chapterTitle.trim().isEmpty()) chapterTitle = documentTitle(zip, item.entryName);
                if (chapterTitle == null || chapterTitle.trim().isEmpty()) chapterTitle = item.href.substring(item.href.lastIndexOf('/') + 1);
                chapters.add(new Chapter(chapters.size() + 1, chapterTitle.trim(), item.entryName, item.id));
            }
            List<Section> sections = readSections(zip, manifest, tocEntries, chapters, title);
            return new Analysis(title, opfName, commonCount, chapters, sections, manifest, spine, commonIds, tocEntries, identifier);
        }
    }

    public static List<Range> parseRanges(String specification, int total) throws IOException {
        if (total < 1) throw new IOException("The EPUB has no splitable chapters");
        if (specification == null || specification.trim().isEmpty()) throw new IOException("Enter a chapter range, for example 1-50,51-");
        List<Range> result = new ArrayList<Range>();
        boolean[] used = new boolean[total + 1];
        for (String part : specification.split(",")) {
            String value = part.trim().replaceAll("\\s+", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*-\\s*(\\d*)").matcher(value);
            if (!m.matches()) throw new IOException("Invalid range: " + part);
            int start = Integer.parseInt(m.group(1));
            int end = m.group(2).isEmpty() ? total : Integer.parseInt(m.group(2));
            if (start < 1 || end > total || end < start) throw new IOException("Range is outside 1-" + total + ": " + part);
            for (int i=start; i<=end; i++) { if (used[i]) throw new IOException("Overlapping range at chapter " + i); used[i]=true; }
            result.add(new Range(start, end));
        }
        return result;
    }

    public static List<Range> createFixedRanges(int size, int total) {
        if (size < 1 || total < 1) throw new IllegalArgumentException("Chapter count and split size must be positive");
        List<Range> ranges = new ArrayList<Range>();
        for (int start=1; start<=total; start += size) ranges.add(new Range(start, Math.min(total, start + size - 1)));
        return ranges;
    }

    public static void split(File source, File output, Range range, boolean includeCommonPages) throws IOException {
        if (source.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("Output must be different from the source EPUB");
        Analysis analysis = analyze(source);
        if (range.end > analysis.chapters.size()) throw new IOException("Range is outside 1-" + analysis.chapters.size());
        Set<String> retainedSpineIds = new HashSet<String>();
        for (Chapter c : analysis.chapters) if (c.number >= range.start && c.number <= range.end) retainedSpineIds.add(c.id);
        if (includeCommonPages) retainedSpineIds.addAll(analysis.commonIds);
        Set<String> removedIds = new HashSet<String>();
        Set<String> removedEntries = new HashSet<String>();
        for (SpineRef ref : analysis.spine) if (!retainedSpineIds.contains(ref.id)) {
            Item item = analysis.manifest.get(ref.id);
            // EPUB 3 navigation and EPUB 2 NCX remain package resources even when their visible spine page is omitted.
            if (!analysis.tocEntries.contains(item.entryName)) {
                removedIds.add(ref.id);
                removedEntries.add(item.entryName);
            }
        }
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create output folder: " + parent);
        File temporary = File.createTempFile("AozoraEpub3-split-", ".epub", parent);
        try {
            try (ZipFile input = new ZipFile(source); ZipArchiveOutputStream zip = new ZipArchiveOutputStream(temporary)) {
                ZipEntry mimetype = input.getEntry("mimetype");
                if (mimetype == null) throw new IOException("Not an EPUB: mimetype entry is missing");
                // EPUB requires this to be the first, uncompressed ZIP entry even when the source ZIP is not ordered.
                writeMimetype(input, mimetype, zip);
                for (ZipEntry entry : Collections.list(input.entries())) {
                    if (entry.isDirectory() || "mimetype".equals(entry.getName()) || removedEntries.contains(entry.getName())) continue;
                    zip.putArchiveEntry(new ZipArchiveEntry(entry.getName()));
                    if (entry.getName().equals(analysis.opfEntryName)) zip.write(serialize(rewriteOpf(parse(read(input, entry.getName())), analysis, retainedSpineIds, removedIds, range)));
                    else if (entry.getName().toLowerCase().endsWith(".ncx")) zip.write(serialize(rewriteNcx(parse(read(input, entry.getName())), entry.getName(), removedEntries, splitTitle(analysis, range), splitIdentifier(analysis, range))));
                    else if (analysis.tocEntries.contains(entry.getName()) && isHtml(entry.getName())) zip.write(serialize(rewriteNav(parse(read(input, entry.getName())), entry.getName(), removedEntries)));
                    else copy(input, entry, zip);
                    zip.closeArchiveEntry();
                }
            }
            try { Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Could not split EPUB", e);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static Document rewriteOpf(Document doc, Analysis a, Set<String> retained, Set<String> removed, Range range) {
        for (Element item : new ArrayList<Element>(descendants(doc, "item"))) if (removed.contains(attr(item, "id"))) item.getParentNode().removeChild(item);
        Element spine = first(doc, "spine");
        if (spine != null) for (Element ref : new ArrayList<Element>(childElements(spine, "itemref"))) if (!retained.contains(attr(ref, "idref"))) spine.removeChild(ref);
        for (Element ref : new ArrayList<Element>(descendants(doc, "reference"))) {
            String target = resolve(a.opfEntryName, attr(ref, "href"));
            if (removed.contains(idForEntry(a.manifest, target))) ref.getParentNode().removeChild(ref);
        }
        Element title = first(doc, "title");
        if (title != null) title.setTextContent(splitTitle(a, range));
        Element identifier = uniqueIdentifierElement(doc);
        if (identifier != null) identifier.setTextContent(splitIdentifier(a, range));
        return doc;
    }

    private static Document rewriteNcx(Document doc, String ncxName, Set<String> removedEntries, String title, String identifier) {
        List<Element> points = new ArrayList<Element>(descendants(doc, "navPoint"));
        Collections.reverse(points); // children first, so an empty parent is removed too
        for (Element navPoint : points) if (navPoint.getParentNode() != null && !filterNcx(navPoint, ncxName, removedEntries)) navPoint.getParentNode().removeChild(navPoint);
        int order = 1;
        for (Element navPoint : descendants(doc, "navPoint")) navPoint.setAttribute("playOrder", Integer.toString(order++));
        Element docTitle = first(doc, "docTitle");
        Element docTitleText = docTitle == null ? null : first(docTitle, "text");
        if (docTitleText != null) docTitleText.setTextContent(title);
        for (Element meta : descendants(doc, "meta")) if ("dtb:uid".equalsIgnoreCase(attr(meta, "name"))) meta.setAttribute("content", identifier);
        return doc;
    }

    private static String splitTitle(Analysis analysis, Range range) {
        return analysis.title + "（" + range.start + "-" + range.end + "）";
    }

    private static String splitIdentifier(Analysis analysis, Range range) {
        String seed = (analysis.identifier.isEmpty() ? analysis.opfEntryName + "\n" + analysis.title : analysis.identifier)
                + "\nAozoraEpub3-split\n" + range;
        return "urn:uuid:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String findUniqueIdentifier(Document opf) {
        Element identifier = uniqueIdentifierElement(opf);
        return identifier == null ? "" : identifier.getTextContent().trim();
    }

    private static Element uniqueIdentifierElement(Document opf) {
        Element packageElement = first(opf, "package");
        String uniqueId = attr(packageElement, "unique-identifier");
        if (!uniqueId.isEmpty()) for (Element identifier : descendants(opf, "identifier")) if (uniqueId.equals(attr(identifier, "id"))) return identifier;
        return first(opf, "identifier");
    }
    private static boolean filterNcx(Element point, String name, Set<String> removed) {
        for (Element child : new ArrayList<Element>(childElements(point, "navPoint"))) if (!filterNcx(child, name, removed)) point.removeChild(child);
        Element content = firstChild(point, "content");
        boolean gone = content != null && removed.contains(resolve(name, attr(content, "src")));
        boolean children = !childElements(point, "navPoint").isEmpty();
        if (gone && children) point.removeChild(content);
        return !gone || children;
    }
    private static Document rewriteNav(Document doc, String name, Set<String> removed) {
        List<Element> items = new ArrayList<Element>(descendants(doc, "li"));
        Collections.reverse(items); // nested items must be decided before their parent
        for (Element li : items) if (li.getParentNode() != null && !filterLi(li, name, removed)) li.getParentNode().removeChild(li);
        return doc;
    }
    private static boolean filterLi(Element li, String name, Set<String> removed) {
        for (Element child : new ArrayList<Element>(childElements(li, "li"))) if (!filterLi(child, name, removed)) child.getParentNode().removeChild(child);
        Element link = firstChild(li, "a");
        boolean gone = link != null && removed.contains(resolve(name, attr(link, "href")));
        boolean children = !descendantsDirectOrNested(li, "li").isEmpty();
        if (gone && children) li.removeChild(link);
        return !gone || children;
    }

    private static List<Section> readSections(ZipFile zip, Map<String, Item> manifest, Set<String> tocEntries,
            List<Chapter> chapters, String bookTitle) throws IOException {
        Map<String, Integer> chapterNumbers = new HashMap<String, Integer>();
        for (Chapter chapter : chapters) chapterNumbers.put(chapter.entryName, chapter.number);
        for (Item item : manifest.values()) {
            if (!tocEntries.contains(item.entryName) || !zipHas(zip, item.entryName)) continue;
            Document navigation = parse(read(zip, item.entryName));
            List<NavSection> roots = item.mediaType.toLowerCase().contains("ncx")
                    ? readNcxSections(navigation, item.entryName) : readHtmlSections(navigation, item.entryName);
            List<Section> sections = convertSections(roots, chapterNumbers, bookTitle);
            if (!sections.isEmpty()) return sections;
        }
        return Collections.emptyList();
    }

    private static List<NavSection> readNcxSections(Document document, String entryName) {
        Element navMap = first(document, "navMap");
        if (navMap == null) return Collections.emptyList();
        List<NavSection> result = new ArrayList<NavSection>();
        for (Element point : childElements(navMap, "navPoint")) result.add(readNcxSection(point, entryName));
        return result;
    }

    private static NavSection readNcxSection(Element point, String entryName) {
        Element label = firstChild(point, "navLabel");
        NavSection section = new NavSection(label == null ? "" : textOfFirst(label, "text"));
        Element content = firstChild(point, "content");
        if (content != null) {
            String target = resolve(entryName, attr(content, "src"));
            if (!target.isEmpty()) section.targets.add(target);
        }
        for (Element child : childElements(point, "navPoint")) {
            NavSection nested = readNcxSection(child, entryName);
            section.children.add(nested);
            section.targets.addAll(nested.targets);
        }
        return section;
    }

    private static List<NavSection> readHtmlSections(Document document, String entryName) {
        Element list = null;
        for (Element nav : descendants(document, "nav")) {
            String type = (attr(nav, "epub:type") + " " + attr(nav, "type") + " " + attr(nav, "role")).toLowerCase();
            if (type.matches(".*\\b(toc|doc-toc)\\b.*")) { list = first(nav, "ol"); break; }
        }
        if (list == null) list = first(document, "ol");
        if (list == null) return Collections.emptyList();
        List<NavSection> result = new ArrayList<NavSection>();
        for (Element item : childElements(list, "li")) result.add(readHtmlSection(item, entryName));
        return result;
    }

    private static NavSection readHtmlSection(Element item, String entryName) {
        Element link = null;
        for (Element child : childElements(item, null)) {
            if ("ol".equals(local(child))) break;
            if ("a".equals(local(child))) { link = child; break; }
        }
        NavSection section = new NavSection(link == null ? directTextBeforeList(item) : link.getTextContent());
        if (link != null) {
            String target = resolve(entryName, attr(link, "href"));
            if (!target.isEmpty()) section.targets.add(target);
        }
        for (Element list : childElements(item, "ol")) for (Element child : childElements(list, "li")) {
            NavSection nested = readHtmlSection(child, entryName);
            section.children.add(nested);
            section.targets.addAll(nested.targets);
        }
        return section;
    }

    private static String directTextBeforeList(Element item) {
        StringBuilder text = new StringBuilder();
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "ol".equals(local((Element)child))) break;
            if (child.getNodeType() == Node.TEXT_NODE) text.append(child.getNodeValue());
            else if (child instanceof Element) text.append(child.getTextContent());
        }
        return text.toString().trim();
    }

    private static List<Section> convertSections(List<NavSection> roots, Map<String, Integer> chapterNumbers, String bookTitle) {
        List<NavSection> candidates = sectionsWithChapters(roots, chapterNumbers);
        if (candidates.size() == 1 && candidates.get(0).children.size() > 1
                && normalizeTitle(candidates.get(0).title).equals(normalizeTitle(bookTitle))) {
            List<NavSection> children = sectionsWithChapters(candidates.get(0).children, chapterNumbers);
            if (children.size() > 1) candidates = children;
        }
        List<Integer> starts = new ArrayList<Integer>();
        List<Integer> targetEnds = new ArrayList<Integer>();
        List<String> titles = new ArrayList<String>();
        for (NavSection candidate : candidates) {
            int start = Integer.MAX_VALUE, end = 0;
            for (String target : candidate.targets) {
                Integer number = chapterNumbers.get(target);
                if (number != null) { start = Math.min(start, number); end = Math.max(end, number); }
            }
            if (end == 0) continue;
            if (!starts.isEmpty() && start <= starts.get(starts.size() - 1)) return Collections.emptyList();
            starts.add(start);
            targetEnds.add(end);
            titles.add(candidate.title);
        }
        List<Section> result = new ArrayList<Section>();
        for (int i = 0; i < starts.size(); i++) {
            int start = i == 0 ? 1 : starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : chapterNumbers.size();
            if (targetEnds.get(i) > end) return Collections.emptyList();
            String title = titles.get(i).isEmpty() ? "第" + (i + 1) + "章" : titles.get(i);
            result.add(new Section(i + 1, title, start, end));
        }
        return result;
    }

    private static List<NavSection> sectionsWithChapters(List<NavSection> sections, Map<String, Integer> chapterNumbers) {
        List<NavSection> result = new ArrayList<NavSection>();
        for (NavSection section : sections) for (String target : section.targets) if (chapterNumbers.containsKey(target)) {
            result.add(section);
            break;
        }
        return result;
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.replaceAll("[\\s　]+", "").toLowerCase();
    }

    private static Map<String, String> readNavigationTitles(ZipFile zip, String opf, Map<String, Item> manifest, Set<String> tocEntries) throws IOException {
        Map<String, String> titles = new HashMap<String, String>();
        for (Item item : manifest.values()) {
            if (!zipHas(zip, item.entryName)) continue;
            if (item.mediaType.toLowerCase().contains("ncx")) {
                Document ncx = parse(read(zip, item.entryName));
                for (Element point : descendants(ncx, "navPoint")) {
                    Element content = firstChild(point, "content");
                    String target = content == null ? "" : resolve(item.entryName, attr(content, "src"));
                    String text = textOfFirst(point, "text"); if (!target.isEmpty() && !text.isEmpty()) titles.put(target, text.trim());
                }
            } else if (tocEntries.contains(item.entryName) && isHtml(item.entryName)) {
                Document nav = parse(read(zip, item.entryName));
                for (Element link : descendants(nav, "a")) {
                    String target = resolve(item.entryName, attr(link, "href"));
                    if (!target.isEmpty() && !link.getTextContent().trim().isEmpty()) titles.put(target, link.getTextContent().trim());
                }
            }
        }
        return titles;
    }
    private static String documentTitle(ZipFile zip, String entry) {
        try {
            if (!zipHas(zip, entry) || !isHtml(entry)) return "";
            Document doc = parse(read(zip, entry));
            String title = textOfFirst(doc, "title");
            if (title.isEmpty()) title = textOfFirst(doc, "h1");
            if (title.isEmpty()) title = textOfFirst(doc, "h2");
            return title;
        } catch (Exception e) { return ""; }
    }
    private static String readRootfile(ZipFile zip) throws IOException {
        if (!zipHas(zip, "META-INF/container.xml")) throw new IOException("Not an EPUB: META-INF/container.xml is missing");
        Element root = first(parse(read(zip, "META-INF/container.xml")), "rootfile");
        if (root == null || attr(root, "full-path").isEmpty()) throw new IOException("container.xml has no rootfile");
        return attr(root, "full-path");
    }
    private static Document parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) { throw new IOException("Invalid EPUB XML/XHTML", e); }
    }
    private static byte[] serialize(Document doc) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            DocumentType doctype = doc.getDoctype();
            if (doctype != null) {
                if (doctype.getPublicId() != null) t.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId());
                if (doctype.getSystemId() != null) t.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId());
            }
            t.transform(new DOMSource(doc), new StreamResult(out));
            byte[] bytes = out.toByteArray();
            boolean htmlNeedsDoctype = doctype == null && doc.getDocumentElement() != null && "html".equalsIgnoreCase(local(doc.getDocumentElement()));
            if (htmlNeedsDoctype || (doctype != null && doctype.getPublicId() == null && doctype.getSystemId() == null)) {
                String xml = new String(bytes, StandardCharsets.UTF_8);
                int declarationEnd = xml.indexOf("?>");
                String declaration = declarationEnd < 0 ? "" : xml.substring(0, declarationEnd + 2);
                String body = declarationEnd < 0 ? xml : xml.substring(declarationEnd + 2);
                String doctypeName = htmlNeedsDoctype ? "html" : doctype.getName();
                return (declaration + "\n<!DOCTYPE " + doctypeName + ">" + body).getBytes(StandardCharsets.UTF_8);
            }
            return bytes;
        } catch (Exception e) { throw new IOException("Could not write EPUB XML", e); }
    }
    private static List<Element> descendants(Node node, String local) { List<Element> r=new ArrayList<Element>(); collect(node,local,r); return r; }
    private static void collect(Node node,String local,List<Element> out) { NodeList c=node.getChildNodes(); for(int i=0;i<c.getLength();i++){ Node n=c.item(i); if(n instanceof Element){Element e=(Element)n;if(local.equals(local(e)))out.add(e);collect(e,local,out);} } }
    private static Element first(Node n,String local){ List<Element> all=descendants(n,local); return all.isEmpty()?null:all.get(0); }
    private static Element firstChild(Element n,String local){ for(Element e:childElements(n,local))return e; return null; }
    private static List<Element> childElements(Element n,String local){List<Element> r=new ArrayList<Element>();NodeList c=n.getChildNodes();for(int i=0;i<c.getLength();i++)if(c.item(i) instanceof Element && (local==null || local.equals(local((Element)c.item(i)))))r.add((Element)c.item(i));return r;}
    private static List<Element> descendantsDirectOrNested(Element n,String local){ return descendants(n,local); }
    private static String local(Element e){ return e.getLocalName()==null ? e.getNodeName().replaceFirst("^.*:","") : e.getLocalName(); }
    private static String attr(Element e,String name){ return e==null?"":e.getAttribute(name); }
    private static String textOfFirst(Node n,String local){ Element e=first(n,local); return e==null?"":e.getTextContent().trim(); }
    private static String idForEntry(Map<String,Item> manifest,String entry){for(Item i:manifest.values())if(i.entryName.equals(entry))return i.id;return "";}
    private static boolean isHtml(String name){return name.toLowerCase().matches(".*\\.(xhtml|html|htm)$");}
    private static boolean zipHas(ZipFile z,String name){return !name.isEmpty() && z.getEntry(name)!=null;}
    private static String resolve(String from,String href) {
        if (href == null || href.isEmpty()) return ""; String clean=href.replaceFirst("[?#].*$", "");
        try { Path parent=Path.of(from).getParent(); Path target=parent==null?Path.of(clean):parent.resolve(clean); return target.normalize().toString().replace('\\','/'); }
        catch (Exception e) { return clean.replace('\\','/'); }
    }
    private static byte[] read(ZipFile z,String name)throws IOException { ZipEntry e=z.getEntry(name);if(e==null)throw new IOException("EPUB entry is missing: "+name);return read(z,e); }
    private static byte[] read(ZipFile z,ZipEntry e)throws IOException{try(InputStream in=z.getInputStream(e);ByteArrayOutputStream out=new ByteArrayOutputStream()){in.transferTo(out);return out.toByteArray();}}
    private static void copy(ZipFile in,ZipEntry e,ZipArchiveOutputStream out)throws IOException{try(InputStream s=in.getInputStream(e)){s.transferTo(out);}}
    private static void writeMimetype(ZipFile in,ZipEntry e,ZipArchiveOutputStream out)throws IOException{byte[] b=read(in,e);ZipArchiveEntry x=new ZipArchiveEntry("mimetype");x.setMethod(ZipArchiveEntry.STORED);x.setSize(b.length);CRC32 crc=new CRC32();crc.update(b);x.setCrc(crc.getValue());out.putArchiveEntry(x);out.write(b);out.closeArchiveEntry();}
}
