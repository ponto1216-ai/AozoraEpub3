package com.github.hmdev.image;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
	import java.util.zip.ZipFile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import com.github.hmdev.info.ImageInfo;

/** AozoraEpub3が出力したEPUBの画像を再処理する */
public class EpubImageProcessor
{
	public enum ImageRemovalTarget { ALL, ILLUSTRATIONS, COVER }

	public static class Options
	{
		public boolean grayscale;
		public boolean png;
		public int colorDepth;
		public int maxWidth;
		public int maxHeight;
		public float jpegQuality = 0.8f;
		public boolean removeImages;
		public boolean removeImageOnlyPages;
		public ImageRemovalTarget imageRemovalTarget = ImageRemovalTarget.ALL;
		/** 各本文ページの先頭画像を、シリーズ内の各話表紙として扱う。 */
		public boolean removeChapterLeadingImages;

		boolean shouldWritePng()
		{
			return png || colorDepth > 0;
		}
		public boolean needsProcessing()
		{
			return grayscale || shouldWritePng() || colorDepth > 0 || maxWidth > 0 || maxHeight > 0 || removeImages;
		}
	}

	static class ImageEntry
	{
		final String sourceName;
		final String outputName;
		final ImageInfo imageInfo;

		ImageEntry(String sourceName, String outputName, ImageInfo imageInfo)
		{
			this.sourceName = sourceName;
			this.outputName = outputName;
			this.imageInfo = imageInfo;
		}
	}

	static class CoverReferences
	{
		final Set<String> imageNames = new HashSet<String>();
		final Set<String> pageNames = new HashSet<String>();

		boolean isEmpty()
		{
			return imageNames.isEmpty() && pageNames.isEmpty();
		}
	}

	public static void process(File sourceFile, File outputFile, Options options) throws IOException
	{
		if (!sourceFile.isFile()) throw new IOException("EPUBファイルがありません: " + sourceFile.getPath());
		if (!options.needsProcessing()) throw new IOException("実行する画像処理を選択してください");

		File outputParent = outputFile.getAbsoluteFile().getParentFile();
		File temporaryFile = File.createTempFile("AozoraEpub3-epub-image-", ".epub", outputParent);
		try {
			try (ZipFile sourceZip = new ZipFile(sourceFile); ZipArchiveOutputStream outputZip = new ZipArchiveOutputStream(temporaryFile)) {
				List<ImageEntry> images = collectImageEntries(sourceZip, options);
				Map<String, String> nameMap = new HashMap<String, String>();
				for (ImageEntry image : images) nameMap.put(image.sourceName, image.outputName);
				CoverReferences coverReferences = options.removeImages ? findCoverReferences(sourceZip, images, options.removeChapterLeadingImages) : new CoverReferences();
				if (options.removeImages && options.imageRemovalTarget != ImageRemovalTarget.ALL && coverReferences.isEmpty()) {
					throw new IOException("表紙情報を検出できないため、挿絵／表紙だけの削除は実行しませんでした");
				}
				List<ImageEntry> removedImages = options.removeImages ? selectRemovedImages(images, coverReferences, options) : new ArrayList<ImageEntry>();
				List<ImageEntry> rewrittenImages = options.removeImages ? removedImages : images;
				Set<String> removedPages = options.removeImages && options.removeImageOnlyPages ? findImageOnlyPages(sourceZip, removedImages) : new HashSet<String>();
				if (options.removeImages && options.imageRemovalTarget == ImageRemovalTarget.COVER) removedPages.addAll(coverReferences.pageNames);

				for (ZipEntry sourceEntry : java.util.Collections.list(sourceZip.entries())) {
					if (sourceEntry.isDirectory() || removedPages.contains(sourceEntry.getName())) continue;
					ImageEntry image = findImage(images, sourceEntry.getName());
					String outputName = image == null ? sourceEntry.getName() : image.outputName;
					if ("mimetype".equals(sourceEntry.getName())) {
						writeMimetype(sourceZip, sourceEntry, outputZip);
					} else if (image != null && containsImage(removedImages, sourceEntry.getName())) {
						continue;
					} else {
						outputZip.putArchiveEntry(new ZipArchiveEntry(outputName));
						if (image != null) writeProcessedImage(sourceZip, sourceEntry, outputZip, image, options);
						else if (isTextEntry(sourceEntry.getName())) writeRewrittenText(sourceZip, sourceEntry, outputZip, nameMap, rewrittenImages, removedPages, options.removeImages);
						else copyEntry(sourceZip, sourceEntry, outputZip);
						outputZip.closeArchiveEntry();
					}
				}
			}
			Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporaryFile.toPath());
		}
	}

	private static List<ImageEntry> selectRemovedImages(List<ImageEntry> images, CoverReferences coverReferences, Options options)
	{
		List<ImageEntry> removed = new ArrayList<ImageEntry>();
		for (ImageEntry image : images) {
			boolean cover = coverReferences.imageNames.contains(image.sourceName);
			if (options.imageRemovalTarget == ImageRemovalTarget.ALL ||
					(options.imageRemovalTarget == ImageRemovalTarget.ILLUSTRATIONS && !cover) ||
					(options.imageRemovalTarget == ImageRemovalTarget.COVER && cover)) removed.add(image);
		}
		return removed;
	}

	private static boolean containsImage(List<ImageEntry> images, String name)
	{
		return findImage(images, name) != null;
	}

	/**
	 * 既存の画像変換で、実体だけPNG化され参照がJPGのままになったEPUBも扱えるようにする。
	 */
	private static ImageEntry findReferencedImage(List<ImageEntry> images, String name)
	{
		ImageEntry exact = findImage(images, name);
		if (exact != null) return exact;
		String stem = imageStem(name);
		if (stem == null) return null;
		for (ImageEntry image : images) if (stem.equals(imageStem(image.sourceName))) return image;
		return null;
	}

	private static String imageStem(String name)
	{
		return name.matches("(?i).*\\.(png|jpe?g|gif|webp)$") ? name.replaceFirst("(?i)\\.(png|jpe?g|gif|webp)$", "") : null;
	}

	private static List<ImageEntry> collectImageEntries(ZipFile sourceZip, Options options) throws IOException
	{
		List<ImageEntry> images = new ArrayList<ImageEntry>();
		Map<String, Boolean> names = new HashMap<String, Boolean>();
		for (ZipEntry entry : java.util.Collections.list(sourceZip.entries())) names.put(entry.getName(), Boolean.TRUE);
		for (ZipEntry entry : java.util.Collections.list(sourceZip.entries())) {
			if (entry.isDirectory() || !isRasterImageEntry(entry.getName())) continue;
			ImageInfo imageInfo;
			try (InputStream input = sourceZip.getInputStream(entry)) {
				imageInfo = ImageInfo.getImageInfo(input);
			}
			if (imageInfo == null && !options.removeImages) continue;
			String outputName = entry.getName();
			if (options.shouldWritePng()) {
				outputName = entry.getName().replaceFirst("(?i)\\.(png|jpe?g|gif|webp)$", ".png");
				if (!entry.getName().equals(outputName) && names.containsKey(outputName)) {
					throw new IOException("PNG化後の画像名が重複します: " + outputName);
				}
				if (imageInfo != null) imageInfo.setOutExt("png");
			}
			images.add(new ImageEntry(entry.getName(), outputName, imageInfo));
		}
		return images;
	}

	private static CoverReferences findCoverReferences(ZipFile sourceZip, List<ImageEntry> images, boolean includeChapterLeadingImages) throws IOException
	{
		CoverReferences references = new CoverReferences();
		for (ZipEntry entry : java.util.Collections.list(sourceZip.entries())) {
			if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".opf")) continue;
			String opf = new String(readEntry(sourceZip, entry), StandardCharsets.UTF_8);
			Map<String, String> manifest = new HashMap<String, String>();
			Matcher itemMatcher = Pattern.compile("(?is)<item\\b[^>]*>").matcher(opf);
			while (itemMatcher.find()) {
				String item = itemMatcher.group();
				String id = attribute(item, "id");
				String href = attribute(item, "href");
				if (id != null && href != null) {
					String target = resolveReference(entry.getName(), href);
					manifest.put(id, target);
					String properties = attribute(item, "properties");
					if (properties != null && Pattern.compile("(?:^|\\s)cover-image(?:\\s|$)").matcher(properties).find()) references.imageNames.add(target);
				}
			}
			Matcher legacyCover = Pattern.compile("(?is)<meta\\b(?=[^>]*\\bname=['\"]cover['\"])[^>]*>").matcher(opf);
			while (legacyCover.find()) {
				String id = attribute(legacyCover.group(), "content");
				if (id != null && manifest.containsKey(id)) references.imageNames.add(manifest.get(id));
			}
			Matcher guideCover = Pattern.compile("(?is)<reference\\b(?=[^>]*\\btype=['\"]cover['\"])[^>]*>").matcher(opf);
			while (guideCover.find()) {
				String href = attribute(guideCover.group(), "href");
				if (href != null) references.pageNames.add(resolveReference(entry.getName(), href));
			}
			if (includeChapterLeadingImages) addSpineLeadingImages(opf, entry.getName(), manifest, sourceZip, images, references.imageNames);
		}
		for (String pageName : new HashSet<String>(references.pageNames)) {
			ZipEntry page = sourceZip.getEntry(pageName);
			if (page != null) references.imageNames.addAll(findReferencedImages(pageName, new String(readEntry(sourceZip, page), StandardCharsets.UTF_8), images));
		}
		return references;
	}

	private static void addSpineLeadingImages(String opf, String opfName, Map<String, String> manifest, ZipFile sourceZip, List<ImageEntry> images, Set<String> coverImages) throws IOException
	{
		Matcher spineMatcher = Pattern.compile("(?is)<spine\\b[^>]*>(.*?)</spine>").matcher(opf);
		if (!spineMatcher.find()) return;
		Matcher itemRefMatcher = Pattern.compile("(?is)<itemref\\b[^>]*>").matcher(spineMatcher.group(1));
		while (itemRefMatcher.find()) {
			String id = attribute(itemRefMatcher.group(), "idref");
			String pageName = id == null ? null : manifest.get(id);
			if (pageName == null || !pageName.matches("(?i).*\\.(xhtml|html)$")) continue;
			ZipEntry page = sourceZip.getEntry(pageName);
			if (page == null) continue;
			String text = new String(readEntry(sourceZip, page), StandardCharsets.UTF_8);
			Matcher imageMatcher = Pattern.compile("(?is)<(img|image)\\b[^>]*>").matcher(text);
			if (!imageMatcher.find()) continue;
			String tag = imageMatcher.group();
			String reference = attribute(tag, "src");
			if (reference == null) reference = attribute(tag, "href");
			if (reference == null) reference = attribute(tag, "xlink:href");
			if (reference != null) {
				String target = resolveReference(pageName, reference);
				if (containsImage(images, target)) coverImages.add(target);
			}
		}
	}

	private static Set<String> findImageOnlyPages(ZipFile sourceZip, List<ImageEntry> images) throws IOException
	{
		Set<String> pages = new HashSet<String>();
		for (ZipEntry entry : java.util.Collections.list(sourceZip.entries())) {
			if (!entry.isDirectory() && entry.getName().matches("(?i).*\\.(xhtml|html)$")) {
				String text = new String(readEntry(sourceZip, entry), StandardCharsets.UTF_8);
				if (findReferencedImages(entry.getName(), text, images).isEmpty()) continue;
				String body = removeImageTags(entry.getName(), text, images).replaceAll("(?is)<[^>]+>", "").replace("&nbsp;", "").trim();
				if (body.isEmpty()) pages.add(entry.getName());
			}
		}
		return pages;
	}

	private static Set<String> findReferencedImages(String entryName, String text, List<ImageEntry> images)
	{
		Set<String> referenced = new HashSet<String>();
		Matcher matcher = Pattern.compile("(?is)<(img|image)\\b[^>]*>").matcher(text);
		while (matcher.find()) {
			String tag = matcher.group();
			String reference = attribute(tag, "src");
			if (reference == null) reference = attribute(tag, "href");
			if (reference == null) reference = attribute(tag, "xlink:href");
			if (reference != null) {
				String target = resolveReference(entryName, reference);
				ImageEntry image = findReferencedImage(images, target);
				if (image != null) referenced.add(image.sourceName);
			}
		}
		return referenced;
	}

	private static String removeImageTags(String entryName, String text, List<ImageEntry> images)
	{
		Matcher matcher = Pattern.compile("(?is)<(img|image)\\b[^>]*>").matcher(text);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String tag = matcher.group();
			String reference = attribute(tag, "src");
			if (reference == null) reference = attribute(tag, "href");
			if (reference == null) reference = attribute(tag, "xlink:href");
			if (reference != null && findReferencedImage(images, resolveReference(entryName, reference)) != null) matcher.appendReplacement(output, "");
			else matcher.appendReplacement(output, Matcher.quoteReplacement(tag));
		}
		matcher.appendTail(output);
		return output.toString();
	}

	private static ImageEntry findImage(List<ImageEntry> images, String name)
	{
		for (ImageEntry image : images) if (image.sourceName.equals(name)) return image;
		return null;
	}

	private static boolean isRasterImageEntry(String name)
	{
		return name.matches("(?i).*\\.(png|jpe?g|gif|webp)$");
	}

	private static boolean isTextEntry(String name)
	{
		return name.matches("(?i).*\\.(xhtml|html|opf|ncx|css)$");
	}

	private static void writeMimetype(ZipFile sourceZip, ZipEntry entry, ZipArchiveOutputStream outputZip) throws IOException
	{
		byte[] bytes = readEntry(sourceZip, entry);
		ZipArchiveEntry outputEntry = new ZipArchiveEntry("mimetype");
		outputEntry.setMethod(ZipArchiveEntry.STORED);
		outputEntry.setSize(bytes.length);
		CRC32 crc = new CRC32();
		crc.update(bytes);
		outputEntry.setCrc(crc.getValue());
		outputZip.putArchiveEntry(outputEntry);
		outputZip.write(bytes);
		outputZip.closeArchiveEntry();
	}

	private static void writeProcessedImage(ZipFile sourceZip, ZipEntry entry, ZipArchiveOutputStream outputZip, ImageEntry image, Options options) throws IOException
	{
		byte[] bytes = readEntry(sourceZip, entry);
		ImageUtils.writeImage(new ByteArrayInputStream(bytes), null, outputZip, image.imageInfo,
				options.jpegQuality, null, 0, options.maxWidth, options.maxHeight, 600, 800,
				0, 0, 0, 0, 0, 0, options.grayscale, options.colorDepth);
	}

	private static void writeRewrittenText(ZipFile sourceZip, ZipEntry entry, ZipArchiveOutputStream outputZip, Map<String, String> nameMap, List<ImageEntry> images, Set<String> removedPages, boolean removeImages) throws IOException
	{
		String text = new String(readEntry(sourceZip, entry), StandardCharsets.UTF_8);
		if (removeImages) {
			text = removeImageTags(entry.getName(), text, images);
		} else {
			for (Map.Entry<String, String> name : nameMap.entrySet()) {
				String sourceReference = relativeReference(entry.getName(), name.getKey());
				String outputReference = relativeReference(entry.getName(), name.getValue());
				text = text.replace(sourceReference, outputReference).replace(name.getKey(), name.getValue());
			}
		}
		if (entry.getName().toLowerCase().endsWith(".opf")) {
			if (removeImages || !removedPages.isEmpty()) text = removeManifestItems(text, entry.getName(), images, removedPages, removeImages);
			else text = text.replaceAll("(?s)(<item\\b(?=[^>]*\\bhref=['\"][^'\"]+\\.png['\"])[^>]*\\bmedia-type=['\"])image/(?:jpeg|gif|webp)(['\"])", "$1image/png$2");
		}
		outputZip.write(text.getBytes(StandardCharsets.UTF_8));
	}

	private static String removeManifestItems(String text, String opfName, List<ImageEntry> images, Set<String> removedPages, boolean removeImages)
	{
		Set<String> removedIds = new HashSet<String>();
		Matcher matcher = Pattern.compile("(?is)<item\\b[^>]*>").matcher(text);
		StringBuffer output = new StringBuffer();
		while (matcher.find()) {
			String item = matcher.group();
			String href = attribute(item, "href");
			String id = attribute(item, "id");
			String target = href == null ? "" : resolveReference(opfName, href);
			boolean remove = removedPages.contains(target);
			if (removeImages && findReferencedImage(images, target) != null) remove = true;
			if (remove) {
				if (id != null) removedIds.add(id);
				matcher.appendReplacement(output, "");
			} else matcher.appendReplacement(output, Matcher.quoteReplacement(item));
		}
		matcher.appendTail(output);
		for (String id : removedIds) output = new StringBuffer(output.toString().replaceAll("(?is)<itemref\\b(?=[^>]*\\bidref=['\"]" + Pattern.quote(id) + "['\"])[^>]*>", ""));
		return output.toString();
	}

	private static String attribute(String element, String name)
	{
		Matcher matcher = Pattern.compile("(?is)\\b" + name + "\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(element);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static String resolveReference(String fromEntry, String reference)
	{
		Path parent = Path.of(fromEntry).getParent();
		Path target = parent == null ? Path.of(reference) : parent.resolve(reference);
		return target.normalize().toString().replace('\\', '/');
	}

	private static String relativeReference(String fromEntry, String targetEntry)
	{
		Path parent = Path.of(fromEntry).getParent();
		Path target = Path.of(targetEntry);
		return (parent == null ? target : parent.relativize(target)).toString().replace('\\', '/');
	}

	private static void copyEntry(ZipFile sourceZip, ZipEntry entry, ZipArchiveOutputStream outputZip) throws IOException
	{
		try (InputStream input = sourceZip.getInputStream(entry)) {
			input.transferTo(outputZip);
		}
	}

	private static byte[] readEntry(ZipFile sourceZip, ZipEntry entry) throws IOException
	{
		try (InputStream input = sourceZip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			input.transferTo(output);
			return output.toByteArray();
		}
	}
}
