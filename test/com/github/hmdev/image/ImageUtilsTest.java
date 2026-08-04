package com.github.hmdev.image;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.Assert;
import org.junit.Test;

import com.github.hmdev.info.ImageInfo;

public class ImageUtilsTest
{
	@Test
	public void convertsColorImageToGrayscale()
	{
		BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		source.setRGB(0, 0, new Color(20, 100, 220, 128).getRGB());

		BufferedImage grayscale = ImageUtils.toGrayscale(source);

		Assert.assertEquals(BufferedImage.TYPE_BYTE_GRAY, grayscale.getType());
		Color pixel = new Color(grayscale.getRGB(0, 0));
		Assert.assertEquals(pixel.getRed(), pixel.getGreen());
		Assert.assertEquals(pixel.getGreen(), pixel.getBlue());
	}

	@Test
	public void writesPngWhenOutputFormatIsPng() throws Exception
	{
		BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		source.setRGB(0, 0, Color.RED.getRGB());
		ByteArrayOutputStream jpegBytes = new ByteArrayOutputStream();
		ImageIO.write(source, "jpeg", jpegBytes);
		ImageInfo imageInfo = ImageInfo.getImageInfo(new ByteArrayInputStream(jpegBytes.toByteArray()));
		imageInfo.setOutExt("png");
		Assert.assertEquals("image/png", imageInfo.getFormat());

		ByteArrayOutputStream archiveBytes = new ByteArrayOutputStream();
		try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(archiveBytes)) {
			archive.putArchiveEntry(new ZipArchiveEntry("image.png"));
			ImageUtils.writeImage(new ByteArrayInputStream(jpegBytes.toByteArray()), null, archive, imageInfo,
					0.8f, null, 0, 0, 0, 600, 800, 0, 0, 0, 0, 0, 0, false);
			archive.closeArchiveEntry();
		}

		java.util.zip.ZipInputStream archive = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archiveBytes.toByteArray()));
		archive.getNextEntry();
		byte[] signature = archive.readNBytes(8);
		archive.close();
		Assert.assertArrayEquals(new byte[] {(byte)0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, signature);
	}
}
