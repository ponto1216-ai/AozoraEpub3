import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

public class AozoraEpub3AppletTest
{
	@Test
	public void mergesTextFilesInSelectionOrderWithFileNameChapters() throws Exception
	{
		File directory = Files.createTempDirectory("aozora-merge-test").toFile();
		File first = new File(directory, "第一話.txt");
		File second = new File(directory, "第二話.txt");
		Files.writeString(first.toPath(), "最初の本文\n", StandardCharsets.UTF_8);
		Files.writeString(second.toPath(), "次の本文\n", StandardCharsets.UTF_8);
		File merged = null;
		try {
			merged = AozoraEpub3Applet.createMergedTextFile(new File[]{first, second}, directory);
			String text = Files.readString(merged.toPath(), StandardCharsets.UTF_8);
			Assert.assertTrue(text.contains("［＃大見出し］第一話［＃大見出し終わり］\n最初の本文"));
			Assert.assertTrue(text.contains("［＃大見出し］第二話［＃大見出し終わり］\n次の本文"));
			Assert.assertTrue(text.indexOf("第一話") < text.indexOf("第二話"));
		} finally {
			if (merged != null) Files.deleteIfExists(merged.toPath());
			Files.deleteIfExists(first.toPath());
			Files.deleteIfExists(second.toPath());
			Files.deleteIfExists(directory.toPath());
		}
	}
}
