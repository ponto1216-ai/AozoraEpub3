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
				Set<String> removedPages = options.removeImages && options.removeImageOnlyPages ? findImageOnlyPages(sourceZip, images) : new HashSet<String>();

				for (ZipEntry sourceEntry : java.util.Collections.list(sourceZip.entries())) {
					if (sourceEntry.isDirectory() || removedPages.contains(sourceEntry.getName())) continue;
					ImageEntry image = findImage(images, sourceEntry.getName());
					String outputName = image == null ? sourceEntry.getName() : image.outputName;
					if ("mimetype".equals(sourceEntry.getName())) {
						writeMimetype(sourceZip, sourceEntry, outputZip);
					} else if (image != null && options.removeImages) {
						continue;
					} else {
						outputZip.putArchiveEntry(new ZipArchiveEntry(outputName));
						if (image != null) writeProcessedImage(sourceZip, sourceEntry, outputZip, image, options);
						else if (isTextEntry(sourceEntry.getName())) writeRewrittenText(sourceZip, sourceEntry, outputZip, nameMap, images, removedPages, options.removeImages);
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

	private static Set<String> findImageOnlyPages(ZipFile sourceZip, List<ImageEntry> images) throws IOException
	{
		Set<String> pages = new HashSet<String>();
		for (ZipEntry entry : java.util.Collections.list(sourceZip.entries())) {
			if (!entry.isDirectory() && entry.getName().matches("(?i).*\\.(xhtml|html)$")) {
				String text = new String(readEntry(sourceZip, entry), StandardCharsets.UTF_8);
				if (!text.matches("(?is).*<(img|image)\\b.*")) continue;
				String body = text.replaceAll("(?is)<(img|image)\\b[^>]*>", "").replaceAll("(?is)<[^>]+>", "").replace("&nbsp;", "").trim();
				if (body.isEmpty()) pages.add(entry.getName());
			}
		}
		return pages;
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
			text = text.replaceAll("(?is)<(img|image)\\b[^>]*>", "");
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
			if (removeImages) for (ImageEntry image : images) if (image.sourceName.equals(target)) remove = true;
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
