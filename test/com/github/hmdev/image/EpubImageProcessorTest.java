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

	private void writeEntry(ZipOutputStream epub, String name, byte[] bytes) throws Exception
	{
		epub.putNextEntry(new ZipEntry(name));
		epub.write(bytes);
		epub.closeEntry();
	}
}
