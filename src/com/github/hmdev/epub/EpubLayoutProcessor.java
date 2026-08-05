package com.github.hmdev.epub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/** Adds a small, late-loading stylesheet to switch the writing direction of a reflowable EPUB. */
public final class EpubLayoutProcessor {
    private static final Pattern ROOTFILE = Pattern.compile("(?is)<rootfile\\b[^>]*\\bfull-path\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern MANIFEST_END = Pattern.compile("(?is)</manifest\\s*>");
    private static final Pattern HEAD_END = Pattern.compile("(?is)</head\\s*>");
    private static final Pattern HEAD_START = Pattern.compile("(?is)<head\\b[^>]*>");
    private static final Pattern PRIOR_LINK = Pattern.compile("(?is)\\s*<link\\b(?=[^>]*\\bdata-aozoraepub3-layout=['\"]true['\"])[^>]*/?\\s*>");

    private EpubLayoutProcessor() { }

    public enum Direction {
        HORIZONTAL("横書き", "horizontal"), VERTICAL("縦書き", "vertical");
        public final String label;
        public final String suffix;
        Direction(String label, String suffix) { this.label = label; this.suffix = suffix; }
    }

    public static final class Preview {
        public String title;
        public int contentDocumentCount;
        public boolean fixedLayout;
        public String warning;
    }

    public static Preview preview(File source) throws IOException {
        if (!source.isFile()) throw new IOException("EPUBファイルがありません: " + source.getPath());
        try (ZipFile zip = new ZipFile(source)) {
            String opfName = rootfile(zip);
            String opf = readText(zip, opfName);
            Preview preview = new Preview();
            preview.title = titleOf(opf);
            if (preview.title.isEmpty()) preview.title = source.getName().replaceFirst("(?i)\\.epub$", "");
            preview.fixedLayout = isFixedLayout(opf);
            for (ZipEntry entry : Collections.list(zip.entries())) if (!entry.isDirectory() && isHtml(entry.getName())) preview.contentDocumentCount++;
            if (preview.fixedLayout) preview.warning = "固定レイアウトEPUBはレイアウト変換できません";
            else if (preview.contentDocumentCount == 0) preview.warning = "本文XHTMLを検出できません";
            return preview;
        }
    }

    public static void process(File source, File output, Direction direction) throws IOException {
        if (source.getCanonicalFile().equals(output.getCanonicalFile())) throw new IOException("元のEPUBとは別の保存先を指定してください");
        Preview preview = preview(source);
        if (preview.fixedLayout) throw new IOException("固定レイアウトEPUBはレイアウト変換できません");
        if (preview.contentDocumentCount == 0) throw new IOException("本文XHTMLを検出できません");
        File parent = output.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("保存先フォルダーを作成できません: " + parent);
        File temporary = File.createTempFile("AozoraEpub3-layout-", ".epub", parent);
        try {
            try (ZipFile input = new ZipFile(source); ZipArchiveOutputStream zip = new ZipArchiveOutputStream(temporary)) {
                String opfName = rootfile(input);
                String cssName = createCssName(opfName, input);
                String cssHref = relative(opfName, cssName);
                ZipEntry mimetype = input.getEntry("mimetype");
                if (mimetype == null) throw new IOException("EPUBではありません: mimetype がありません");
                writeMimetype(input, mimetype, zip);
                for (ZipEntry entry : Collections.list(input.entries())) {
                    if (entry.isDirectory() || "mimetype".equals(entry.getName()) || cssName.equals(entry.getName())) continue;
                    zip.putArchiveEntry(new ZipArchiveEntry(entry.getName()));
                    if (entry.getName().equals(opfName)) zip.write(rewriteOpf(readText(input, entry.getName()), cssHref).getBytes(StandardCharsets.UTF_8));
                    else if (isHtml(entry.getName())) zip.write(rewriteHtml(readText(input, entry.getName()), relative(entry.getName(), cssName)).getBytes(StandardCharsets.UTF_8));
                    else copy(input, entry, zip);
                    zip.closeArchiveEntry();
                }
                zip.putArchiveEntry(new ZipArchiveEntry(cssName));
                zip.write(stylesheet(direction).getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static String rootfile(ZipFile zip) throws IOException {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) throw new IOException("EPUBではありません: container.xml がありません");
        Matcher matcher = ROOTFILE.matcher(readText(zip, container.getName()));
        if (!matcher.find() || zip.getEntry(matcher.group(1)) == null) throw new IOException("EPUBのOPFを検出できません");
        return matcher.group(1);
    }

    private static boolean isFixedLayout(String opf) {
        String normalized = opf.toLowerCase();
        return normalized.contains("pre-paginated")
                || normalized.matches("(?is).*<meta\\b(?=[^>]*(?:name|property)\\s*=\\s*['\"]fixed-layout['\"])(?=[^>]*content\\s*=\\s*['\"](?:true|yes|1)['\"])[^>]*>.*");
    }

    private static String titleOf(String opf) {
        Matcher matcher = Pattern.compile("(?is)<[^:>]*:?title\\b[^>]*>(.*?)</[^:>]*:?title\\s*>").matcher(opf);
        return matcher.find() ? matcher.group(1).replaceAll("(?is)<[^>]+>", "").trim() : "";
    }

    private static String createCssName(String opfName, ZipFile zip) {
        Path parent = Path.of(opfName).getParent();
        String base = parent == null ? "aozoraepub3-layout.css" : parent.resolve("aozoraepub3-layout.css").toString().replace('\\', '/');
        if (zip.getEntry(base) == null) return base;
        return (parent == null ? "" : parent.toString().replace('\\', '/') + "/") + "aozoraepub3-layout-" + UUID.randomUUID() + ".css";
    }

    private static String rewriteOpf(String opf, String cssHref) throws IOException {
        String id = "aozoraepub3-layout";
        while (Pattern.compile("(?is)\\bid\\s*=\\s*['\"]" + Pattern.quote(id) + "['\"]").matcher(opf).find()) id += "-x";
        String item = "\n    <item id=\"" + id + "\" href=\"" + cssHref + "\" media-type=\"text/css\"/>";
        Matcher matcher = MANIFEST_END.matcher(opf);
        if (!matcher.find()) throw new IOException("EPUBのmanifestを検出できません");
        return matcher.replaceFirst(Matcher.quoteReplacement(item + "\n  </manifest>"));
    }

    private static String rewriteHtml(String html, String cssHref) throws IOException {
        html = PRIOR_LINK.matcher(html).replaceAll("");
        String link = "\n<link rel=\"stylesheet\" type=\"text/css\" href=\"" + cssHref + "\" data-aozoraepub3-layout=\"true\"/>";
        Matcher end = HEAD_END.matcher(html);
        if (end.find()) return end.replaceFirst(Matcher.quoteReplacement(link + "\n</head>"));
        Matcher start = HEAD_START.matcher(html);
        if (start.find()) return start.replaceFirst(Matcher.quoteReplacement(start.group() + link));
        throw new IOException("XHTMLのheadを検出できません");
    }

    private static String stylesheet(Direction direction) {
        String mode = direction == Direction.VERTICAL ? "vertical-rl" : "horizontal-tb";
        String orientation = "mixed";
        String combine = direction == Direction.HORIZONTAL ? "\n.aozoraepub3-layout-root .tcy { text-combine-upright: none !important; -webkit-text-combine: none !important; }" : "";
        return "@charset \"utf-8\";\n"
                + "/* Added by AozoraEpub3: writing direction override. */\n"
                + "html, body, html.vrtl, html.hltr, .vrtl, .hltr {\n"
                + "  writing-mode: " + mode + " !important;\n"
                + "  -webkit-writing-mode: " + mode + " !important;\n"
                + "  -epub-writing-mode: " + mode + " !important;\n"
                + "  text-orientation: " + orientation + " !important;\n"
                + "}\n"
                + "html { direction: ltr !important; }\n"
                + ".main, .p-text, .p-titlepage { writing-mode: inherit !important; }\n"
                + combine + "\n";
    }

    private static boolean isHtml(String name) { return name.matches("(?i).*\\.(xhtml|html|htm)$"); }
    private static String relative(String from, String to) {
        Path parent = Path.of(from).getParent();
        return (parent == null ? Path.of(to) : parent.relativize(Path.of(to))).toString().replace('\\', '/');
    }
    private static String readText(ZipFile zip, String name) throws IOException { return new String(read(zip, zip.getEntry(name)), StandardCharsets.UTF_8); }
    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null) throw new IOException("EPUB内のファイルを読めません");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) { in.transferTo(out); return out.toByteArray(); }
    }
    private static void copy(ZipFile in, ZipEntry entry, ZipArchiveOutputStream out) throws IOException { try (InputStream stream = in.getInputStream(entry)) { stream.transferTo(out); } }
    private static void writeMimetype(ZipFile in, ZipEntry entry, ZipArchiveOutputStream out) throws IOException {
        byte[] bytes = read(in, entry); ZipArchiveEntry target = new ZipArchiveEntry("mimetype"); target.setMethod(ZipArchiveEntry.STORED); target.setSize(bytes.length);
        CRC32 crc = new CRC32(); crc.update(bytes); target.setCrc(crc.getValue()); out.putArchiveEntry(target); out.write(bytes); out.closeArchiveEntry();
    }
}
