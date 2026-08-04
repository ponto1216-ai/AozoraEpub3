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
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

		boolean shouldWritePng()
		{
			return png || colorDepth > 0;
		}
		public boolean needsProcessing()
		{
			return grayscale || shouldWritePng() || colorDepth > 0 || maxWidth > 0 || maxHeight > 0;
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

				for (ZipEntry sourceEntry : java.util.Collections.list(sourceZip.entries())) {
					if (sourceEntry.isDirectory()) continue;
					ImageEntry image = findImage(images, sourceEntry.getName());
					String outputName = image == null ? sourceEntry.getName() : image.outputName;
					if ("mimetype".equals(sourceEntry.getName())) {
						writeMimetype(sourceZip, sourceEntry, outputZip);
					} else {
						outputZip.putArchiveEntry(new ZipArchiveEntry(outputName));
						if (image != null) writeProcessedImage(sourceZip, sourceEntry, outputZip, image, options);
						else if (isTextEntry(sourceEntry.getName())) writeRewrittenText(sourceZip, sourceEntry, outputZip, nameMap);
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
			if (imageInfo == null) continue;
			String outputName = entry.getName();
			if (options.shouldWritePng()) {
				outputName = entry.getName().replaceFirst("(?i)\\.(png|jpe?g|gif|webp)$", ".png");
				if (!entry.getName().equals(outputName) && names.containsKey(outputName)) {
					throw new IOException("PNG化後の画像名が重複します: " + outputName);
				}
				imageInfo.setOutExt("png");
			}
			images.add(new ImageEntry(entry.getName(), outputName, imageInfo));
		}
		return images;
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

	private static void writeRewrittenText(ZipFile sourceZip, ZipEntry entry, ZipArchiveOutputStream outputZip, Map<String, String> nameMap) throws IOException
	{
		String text = new String(readEntry(sourceZip, entry), StandardCharsets.UTF_8);
		for (Map.Entry<String, String> name : nameMap.entrySet()) {
			String sourceReference = relativeReference(entry.getName(), name.getKey());
			String outputReference = relativeReference(entry.getName(), name.getValue());
			text = text.replace(sourceReference, outputReference).replace(name.getKey(), name.getValue());
		}
		if (entry.getName().toLowerCase().endsWith(".opf")) {
			text = text.replaceAll("(?s)(<item\\b(?=[^>]*\\bhref=['\"][^'\"]+\\.png['\"])[^>]*\\bmedia-type=['\"])image/(?:jpeg|gif|webp)(['\"])", "$1image/png$2");
		}
		outputZip.write(text.getBytes(StandardCharsets.UTF_8));
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
