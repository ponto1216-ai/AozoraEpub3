package com.github.hmdev.web;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Assert;
import org.junit.Test;

public class WebAozoraConverterTest
{
	@Test
	public void detectsOnlyRecognizedImageHeaders() throws Exception
	{
		File jpeg = File.createTempFile("web-image", ".jpg");
		File html = File.createTempFile("web-image-error", ".jpg");
		Files.write(jpeg.toPath(), new byte[] {(byte)0xff, (byte)0xd8, (byte)0xff, 0x00});
		Files.writeString(html.toPath(), "<html>301 Moved Permanently</html>", StandardCharsets.UTF_8);
		try {
			Assert.assertTrue(WebAozoraConverter.isValidImageFile(jpeg));
			Assert.assertFalse(WebAozoraConverter.isValidImageFile(html));
		} finally {
			Files.deleteIfExists(jpeg.toPath());
			Files.deleteIfExists(html.toPath());
		}
	}

	@Test
	public void pausesOnlyAfterEachConfiguredBatchOfDownloads()
	{
		Assert.assertFalse(WebAozoraConverter.shouldPauseAfterDownloads(0, 5, 60000));
		Assert.assertFalse(WebAozoraConverter.shouldPauseAfterDownloads(4, 5, 60000));
		Assert.assertTrue(WebAozoraConverter.shouldPauseAfterDownloads(5, 5, 60000));
		Assert.assertTrue(WebAozoraConverter.shouldPauseAfterDownloads(10, 5, 60000));
		Assert.assertFalse(WebAozoraConverter.shouldPauseAfterDownloads(5, 5, 0));
	}

	@Test
	public void recognizesHamelnInlineImageLinks()
	{
		Document document = Jsoup.parse("<a name=\"img\" href=\"https://img.syosetu.org/example.png\">挿絵表示</a>");
		Assert.assertTrue(WebAozoraConverter.isInlineImageLink(document.selectFirst("a")));
		Assert.assertFalse(WebAozoraConverter.isInlineImageLink(Jsoup.parse("<a href=\"https://example.com\">link</a>").selectFirst("a")));
	}

	@Test
	public void estimatesDownloadTimeIncludingConfiguredBatchPauses()
	{
		Assert.assertEquals(1_670_000L, WebAozoraConverter.estimateDownloadMillis(77, 10_000, 5, 60_000));
		Assert.assertEquals("約27分50秒", WebAozoraConverter.formatEstimatedDownloadTime(1_670_000L));
		Assert.assertEquals("約0秒", WebAozoraConverter.formatEstimatedDownloadTime(0));
	}

	@Test
	public void splitsConvertedTextAtMajorChapterHeadings() throws Exception
	{
		File source = File.createTempFile("web-chapter-split", ".txt");
		String text = "作品名\n著者\n"
			+ "\n［＃改ページ］\n［＃大見出し］第一部［＃大見出し終わり］\n第一話\n"
			+ "\n［＃改ページ］\n［＃大見出し］第二部［＃大見出し終わり］\n第二話\n"
			+ "\n［＃改ページ］\n底本： https://example.com/\n";
		Files.writeString(source.toPath(), text, StandardCharsets.UTF_8);
		try {
			List<WebAozoraConverter.ChapterTextFile> chapters = WebAozoraConverter.splitConvertedTextByChapters(source, Set.of(2));
			Assert.assertEquals(1, chapters.size());
			Assert.assertEquals("第二部", chapters.get(0).chapterTitle);
			String chapterText = Files.readString(chapters.get(0).file.toPath(), StandardCharsets.UTF_8);
			Assert.assertTrue(chapterText.contains("作品名"));
			Assert.assertTrue(chapterText.contains("第二話"));
			Assert.assertFalse(chapterText.contains("第一話"));
			Assert.assertTrue(chapterText.contains("底本："));
			Files.deleteIfExists(chapters.get(0).file.toPath());
		} finally {
			Files.deleteIfExists(source.toPath());
		}
	}

	@Test
	public void parsesSelectedChapterNumbers()
	{
		Assert.assertEquals(Set.of(1, 3, 4, 5), WebAozoraConverter.parseChapterNumbers("1, 3-5"));
	}

	@Test
	public void groupsChapterRangesIntoOneEpubSource() throws Exception
	{
		File source = File.createTempFile("web-chapter-group", ".txt");
		String text = "作品名\n著者\n"
			+ "\n［＃改ページ］\n［＃大見出し］第一部［＃大見出し終わり］\n第一話\n"
			+ "\n［＃改ページ］\n［＃大見出し］第二部［＃大見出し終わり］\n第二話\n";
		Files.writeString(source.toPath(), text, StandardCharsets.UTF_8);
		try {
			List<Set<Integer>> groups = WebAozoraConverter.parseChapterGroups("1-2");
			List<WebAozoraConverter.ChapterTextFile> chapters = WebAozoraConverter.splitConvertedTextByChapterGroups(source, groups);
			Assert.assertEquals(1, chapters.size());
			Assert.assertEquals("第一部 ～ 第二部", chapters.get(0).chapterTitle);
			String chapterText = Files.readString(chapters.get(0).file.toPath(), StandardCharsets.UTF_8);
			Assert.assertTrue(chapterText.contains("第一話"));
			Assert.assertTrue(chapterText.contains("第二話"));
			Files.deleteIfExists(chapters.get(0).file.toPath());
		} finally {
			Files.deleteIfExists(source.toPath());
		}
	}

	@Test
	public void parsesChapterGroupsSeparatedBySemicolons()
	{
		List<Set<Integer>> groups = WebAozoraConverter.parseChapterGroups("1-3;4-6");
		Assert.assertEquals(2, groups.size());
		Assert.assertEquals(Set.of(1, 2, 3), groups.get(0));
		Assert.assertEquals(Set.of(4, 5, 6), groups.get(1));
	}

	@Test
	public void fallsBackToEpisodeRangesWhenThereAreNoChapterHeadings() throws Exception
	{
		File source = File.createTempFile("web-episode-group", ".txt");
		String text = "作品名\n著者\n"
			+ "\n［＃改ページ］\n［＃中見出し］第一話［＃中見出し終わり］\n本文1\n"
			+ "\n［＃改ページ］\n［＃中見出し］第二話［＃中見出し終わり］\n本文2\n"
			+ "\n［＃改ページ］\n［＃中見出し］第三話［＃中見出し終わり］\n本文3\n";
		Files.writeString(source.toPath(), text, StandardCharsets.UTF_8);
		try {
			List<WebAozoraConverter.ChapterTextFile> episodes = WebAozoraConverter.splitConvertedTextByEpisodeGroups(source,
				WebAozoraConverter.parseChapterGroups("1-2;3"));
			Assert.assertEquals(2, episodes.size());
			Assert.assertEquals("第一話 ～ 第二話", episodes.get(0).chapterTitle);
			Assert.assertTrue(Files.readString(episodes.get(0).file.toPath(), StandardCharsets.UTF_8).contains("本文2"));
			Assert.assertTrue(Files.readString(episodes.get(1).file.toPath(), StandardCharsets.UTF_8).contains("本文3"));
			for (WebAozoraConverter.ChapterTextFile episode : episodes) Files.deleteIfExists(episode.file.toPath());
		} finally {
			Files.deleteIfExists(source.toPath());
		}
	}

	@Test
	public void keepsHamelnRelativeUrlsInTheNoUpdateCacheSet() throws Exception
	{
		WebAozoraConverter converter = createHamelnConverter();
		converter.baseUri = "https://syosetu.org";
		Document document = loadFixture("hameln-index.html");
		File updateInfo = File.createTempFile("hameln-update", ".txt");
		Files.writeString(updateInfo.toPath(), "./1.html\t2026/07/31\n./2.html\t2026/08/01\n", StandardCharsets.UTF_8);
		try {
			Assert.assertTrue(converter.createNoUpdateUrls(updateInfo, "https://syosetu.org/Novel/12345/",
				"https://syosetu.org/Novel/12345/", null,
				converter.getExtractElements(document, converter.queryMap.get(ExtractInfo.ExtractId.HREF)),
				converter.getExtractElements(document, converter.queryMap.get(ExtractInfo.ExtractId.SUB_UPDATE)))
				.contains("https://syosetu.org/Novel/12345/1.html"));
		} finally {
			Files.deleteIfExists(updateInfo.toPath());
		}
	}

    private WebAozoraConverter createHamelnConverter() throws Exception
    {
        return WebAozoraConverter.createWebAozoraConverter(
            "https://syosetu.org/Novel/12345/", new File("web"));
    }

    private Document loadFixture(String fixtureName) throws Exception
    {
        try (InputStream input = getClass().getResourceAsStream("/web/" + fixtureName)) {
            Assert.assertNotNull("Hameln fixture is missing", input);
            return Jsoup.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void extractsHamelnSeriesMetadataAndEpisodes() throws Exception
    {
        WebAozoraConverter converter = createHamelnConverter();
        Document document = loadFixture("hameln-index.html");
		converter.baseUri = "https://syosetu.org";

        Assert.assertNotNull("Hameln adapter must be configured", converter);
        Assert.assertEquals("テストシリーズ", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.TITLE)));
        Assert.assertEquals("テスト作者", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.AUTHOR)));
        Assert.assertEquals("作品説明", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.DESCRIPTION)));
        Assert.assertEquals(2, converter.getExtractElements(document,
            converter.queryMap.get(ExtractInfo.ExtractId.HREF)).size());
        Assert.assertEquals(2, converter.getExtractStrings(document,
            converter.queryMap.get(ExtractInfo.ExtractId.SUBTITLE_LIST), true).size());
        Assert.assertEquals("第一部", converter.getHamelnListChapterTitles(document,
            "https://syosetu.org/Novel/12345/").get("https://syosetu.org/Novel/12345/1.html"));
    }

    @Test
    public void extractsHamelnArticleSections() throws Exception
    {
        WebAozoraConverter converter = createHamelnConverter();
        Document document = loadFixture("hameln-chapter.html");

        Assert.assertEquals("第一話", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.CONTENT_SUBTITLE)));
        Assert.assertEquals("前書き", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.CONTENT_PREAMBLE)));
        Assert.assertEquals("本文一行目 本文二行目", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.CONTENT_ARTICLE)));
    }
}
