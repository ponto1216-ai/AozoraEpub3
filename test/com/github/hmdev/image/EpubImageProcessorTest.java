package com.github.hmdev.image;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.Assert;
import org.junit.Test;

public class EpubImageProcessorTest
{
	@Test
	public void previewsSelectiveImageRemoval() throws Exception
	{
		File source = File.createTempFile("epub-image-preview-source", ".epub");
		try {
			writeCoverAndIllustrationEpub(source);
			EpubImageProcessor.Options options = new EpubImageProcessor.Options();
			options.removeImages = true;
			options.removeImageOnlyPages = true;
			options.imageRemovalTarget = EpubImageProcessor.ImageRemovalTarget.ILLUSTRATIONS;

			EpubImageProcessor.Preview preview = EpubImageProcessor.preview(source, options);

			Assert.assertEquals(2, preview.totalImages);
			Assert.assertEquals(1, preview.coverImages);
			Assert.assertEquals(1, preview.illustrationImages);
			Assert.assertEquals(1, preview.chapterLeadingImages);
			Assert.assertEquals(1, preview.targetImages);
			Assert.assertEquals(1, preview.removedImageOnlyPages);
			Assert.assertTrue(preview.coverDetected);
			Assert.assertNull(preview.warning);
			Assert.assertEquals(java.util.List.of("OEBPS/images/illustration.jpg"), preview.targetImageNames);
		} finally {
			Files.deleteIfExists(source.toPath());
		}
	}

	@Test
	public void convertsExistingEpubImagesAndUpdatesReferences() throws Exception
	{
		File source = File.createTempFile("epub-image-source", ".epub");
		File output = File.createTempFile("epub-image-output", ".epub");
		try {
			writeSourceEpub(source);
			EpubImageProcessor.Options options = new EpubImageProcessor.Options();
			options.png = true;
			options.grayscale = true;
			EpubImageProcessor.process(source, output, options);

			try (ZipFile epub = new ZipFile(output)) {
				Assert.assertNull(epub.getEntry("OEBPS/images/test.jpg"));
				ZipEntry image = epub.getEntry("OEBPS/images/test.png");
				Assert.assertNotNull(image);
				byte[] signature = epub.getInputStream(image).readNBytes(8);
				Assert.assertArrayEquals(new byte[] {(byte)0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, signature);
				String xhtml = new String(epub.getInputStream(epub.getEntry("OEBPS/text/chapter.xhtml")).readAllBytes(), StandardCharsets.UTF_8);
				Assert.assertTrue(xhtml.contains("../images/test.png"));
				String opf = new String(epub.getInputStream(epub.getEntry("OEBPS/package.opf")).readAllBytes(), StandardCharsets.UTF_8);
				Assert.assertTrue(opf.contains("images/test.png"));
				Assert.assertTrue(opf.contains("media-type=\"image/png\""));
			}
		} finally {
			Files.deleteIfExists(source.toPath());
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void removesImagesAndImageOnlyPages() throws Exception
	{
		File source = File.createTempFile("epub-image-remove-source", ".epub");
		File output = File.createTempFile("epub-image-remove-output", ".epub");
		try {
			writeSourceEpub(source);
			EpubImageProcessor.Options options = new EpubImageProcessor.Options();
			options.removeImages = true;
			options.removeImageOnlyPages = true;
			EpubImageProcessor.process(source, output, options);

			try (ZipFile epub = new ZipFile(output)) {
				Assert.assertNull(epub.getEntry("OEBPS/images/test.jpg"));
				Assert.assertNull(epub.getEntry("OEBPS/text/chapter.xhtml"));
				String opf = new String(epub.getInputStream(epub.getEntry("OEBPS/package.opf")).readAllBytes(), StandardCharsets.UTF_8);
				Assert.assertFalse(opf.contains("images/test.jpg"));
			}
		} finally {
			Files.deleteIfExists(source.toPath());
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void removesImagesWhenPackageFileIsAtEpubRoot() throws Exception
	{
		File source = File.createTempFile("epub-root-package-source", ".epub");
		File output = File.createTempFile("epub-root-package-output", ".epub");
		try {
			BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
			ImageIO.write(image, "jpeg", imageBytes);
			try (ZipOutputStream epub = new ZipOutputStream(Files.newOutputStream(source.toPath()))) {
				writeEntry(epub, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
				writeEntry(epub, "package.opf", "<package><manifest><item id=\"image\" href=\"image.jpg\" media-type=\"image/jpeg\"/></manifest></package>".getBytes(StandardCharsets.UTF_8));
				writeEntry(epub, "image.jpg", imageBytes.toByteArray());
			}
			EpubImageProcessor.Options options = new EpubImageProcessor.Options();
			options.removeImages = true;
			EpubImageProcessor.process(source, output, options);
			try (ZipFile epub = new ZipFile(output)) {
				Assert.assertNull(epub.getEntry("image.jpg"));
			}
		} finally {
			Files.deleteIfExists(source.toPath());
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void removesOnlyIllustrationsOrOnlyCover() throws Exception
	{
		File source = File.createTempFile("epub-image-selective-source", ".epub");
		File illustrationsOutput = File.createTempFile("epub-image-illustrations", ".epub");
		File coverOutput = File.createTempFile("epub-image-cover", ".epub");
		File chapterCoverOutput = File.createTempFile("epub-image-chapter-cover", ".epub");
		try {
			writeCoverAndIllustrationEpub(source);
			EpubImageProcessor.Options illustrations = new EpubImageProcessor.Options();
			illustrations.removeImages = true;
			illustrations.removeImageOnlyPages = true;
			illustrations.imageRemovalTarget = EpubImageProcessor.ImageRemovalTarget.ILLUSTRATIONS;
			EpubImageProcessor.process(source, illustrationsOutput, illustrations);
			try (ZipFile epub = new ZipFile(illustrationsOutput)) {
				Assert.assertNotNull(epub.getEntry("OEBPS/images/cover.jpg"));
				Assert.assertNull(epub.getEntry("OEBPS/images/illustration.jpg"));
				Assert.assertNotNull(epub.getEntry("OEBPS/text/cover.xhtml"));
				Assert.assertNull(epub.getEntry("OEBPS/text/chapter.xhtml"));
			}

			EpubImageProcessor.Options cover = new EpubImageProcessor.Options();
			cover.removeImages = true;
			cover.imageRemovalTarget = EpubImageProcessor.ImageRemovalTarget.COVER;
			EpubImageProcessor.process(source, coverOutput, cover);
			try (ZipFile epub = new ZipFile(coverOutput)) {
				Assert.assertNull(epub.getEntry("OEBPS/images/cover.jpg"));
				Assert.assertNotNull(epub.getEntry("OEBPS/images/illustration.jpg"));
				Assert.assertNull(epub.getEntry("OEBPS/text/cover.xhtml"));
				Assert.assertNotNull(epub.getEntry("OEBPS/text/chapter.xhtml"));
			}

			EpubImageProcessor.Options chapterCovers = new EpubImageProcessor.Options();
			chapterCovers.removeImages = true;
			chapterCovers.imageRemovalTarget = EpubImageProcessor.ImageRemovalTarget.COVER;
			chapterCovers.removeChapterLeadingImages = true;
			EpubImageProcessor.process(source, chapterCoverOutput, chapterCovers);
			try (ZipFile epub = new ZipFile(chapterCoverOutput)) {
				Assert.assertNull(epub.getEntry("OEBPS/images/cover.jpg"));
				Assert.assertNull(epub.getEntry("OEBPS/images/illustration.jpg"));
			}
		} finally {
			Files.deleteIfExists(source.toPath());
			Files.deleteIfExists(illustrationsOutput.toPath());
			Files.deleteIfExists(coverOutput.toPath());
			Files.deleteIfExists(chapterCoverOutput.toPath());
		}
	}

	@Test
	public void removesBrokenImageReferencesAfterPriorPngConversion() throws Exception
	{
		File source = File.createTempFile("epub-image-mismatch-source", ".epub");
		File output = File.createTempFile("epub-image-mismatch-output", ".epub");
		try {
			writeMismatchedImageReferenceEpub(source);
			EpubImageProcessor.Options options = new EpubImageProcessor.Options();
			options.removeImages = true;
			EpubImageProcessor.process(source, output, options);
			try (ZipFile epub = new ZipFile(output)) {
				Assert.assertNull(epub.getEntry("OEBPS/images/test.png"));
				String xhtml = new String(epub.getInputStream(epub.getEntry("OEBPS/text/chapter.xhtml")).readAllBytes(), StandardCharsets.UTF_8);
				Assert.assertFalse(xhtml.contains("<img"));
				String opf = new String(epub.getInputStream(epub.getEntry("OEBPS/package.opf")).readAllBytes(), StandardCharsets.UTF_8);
				Assert.assertFalse(opf.contains("images/test.jpg"));
			}
		} finally {
			Files.deleteIfExists(source.toPath());
			Files.deleteIfExists(output.toPath());
		}
	}

	private void writeSourceEpub(File epubFile) throws Exception
	{
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, Color.RED.getRGB());
		ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", imageBytes);
		try (ZipOutputStream epub = new ZipOutputStream(Files.newOutputStream(epubFile.toPath()))) {
			writeEntry(epub, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
			writeEntry(epub, "OEBPS/package.opf", "<package><manifest><item id=\"image\" href=\"images/test.jpg\" media-type=\"image/jpeg\"/></manifest></package>".getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/text/chapter.xhtml", "<html><body><img src=\"../images/test.jpg\"/></body></html>".getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/images/test.jpg", imageBytes.toByteArray());
		}
	}

	private void writeCoverAndIllustrationEpub(File epubFile) throws Exception
	{
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
		ImageIO.write(image, "jpeg", imageBytes);
		try (ZipOutputStream epub = new ZipOutputStream(Files.newOutputStream(epubFile.toPath()))) {
			writeEntry(epub, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
			writeEntry(epub, "OEBPS/package.opf", ("<package><metadata><meta name=\"cover\" content=\"cover-image\"/></metadata>"
					+ "<manifest><item id=\"cover-image\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>"
					+ "<item id=\"illustration\" href=\"images/illustration.jpg\" media-type=\"image/jpeg\"/>"
					+ "<item id=\"cover-page\" href=\"text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
					+ "<item id=\"chapter\" href=\"text/chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
					+ "<spine><itemref idref=\"cover-page\"/><itemref idref=\"chapter\"/></spine>"
					+ "<guide><reference type=\"cover\" href=\"text/cover.xhtml\"/></guide></package>").getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/text/cover.xhtml", "<html><body><img src=\"../images/cover.jpg\"/></body></html>".getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/text/chapter.xhtml", "<html><body><img src=\"../images/illustration.jpg\"/></body></html>".getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/images/cover.jpg", imageBytes.toByteArray());
			writeEntry(epub, "OEBPS/images/illustration.jpg", imageBytes.toByteArray());
		}
	}

	private void writeMismatchedImageReferenceEpub(File epubFile) throws Exception
	{
		BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", imageBytes);
		try (ZipOutputStream epub = new ZipOutputStream(Files.newOutputStream(epubFile.toPath()))) {
			writeEntry(epub, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
			writeEntry(epub, "OEBPS/package.opf", "<package><manifest><item id=\"image\" href=\"images/test.jpg\" media-type=\"image/jpeg\"/></manifest></package>".getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/text/chapter.xhtml", "<html><body><img src=\"../images/test.jpg\"/></body></html>".getBytes(StandardCharsets.UTF_8));
			writeEntry(epub, "OEBPS/images/test.png", imageBytes.toByteArray());
		}
	}

	private void writeEntry(ZipOutputStream epub, String name, byte[] bytes) throws Exception
	{
		epub.putNextEntry(new ZipEntry(name));
		epub.write(bytes);
		epub.closeEntry();
	}
}
