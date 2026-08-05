package com.github.hmdev.web;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Assert;
import org.junit.Test;

public class WebAozoraConverterTest
{
	@Test
	public void hamelnChapterTitlePrefixIsRemovedFromEpisodeTitle()
	{
		Assert.assertEquals("#1　”銀河鉄道ってそりゃあアナタ”",
				WebAozoraConverter.removeHamelnChapterTitlePrefix("1. Episode of \"M\" #1　”銀河鉄道ってそりゃあアナタ”", "1. Episode of \"M\""));
		Assert.assertEquals("1. Episode of \"M\"", WebAozoraConverter.removeHamelnChapterTitlePrefix("1. Episode of \"M\"", "1. Episode of \"M\""));
	}
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
	public void treatsImageCdnLinksAsImagesAndHandlesMissingLargeImageMarkup()
	{
		Assert.assertFalse(WebAozoraConverter.isLargeImagePageUrl("https://img1.mitemin.net/ic/example.jpg"));
		Assert.assertTrue(WebAozoraConverter.isLargeImagePageUrl("https://example.mitemin.net/userpageimage/viewimagebig/icode/123"));
		Assert.assertNull(WebAozoraConverter.getLargeImageHref(Jsoup.parse("<html><body>image CDN response</body></html>")));
		Assert.assertEquals("https://img.example/large.jpg", WebAozoraConverter.getLargeImageHref(
				Jsoup.parse("<div class=\"imageview\"><a href=\"https://img.example/large.jpg\"><img/></a></div>")));
	}

	@Test
	public void skipsUnexpectedPagedIndexWithoutStoppingTheWholeNovel() throws Exception
	{
		WebAozoraConverter converter = WebAozoraConverter.createWebAozoraConverter(
				"https://ncode.syosetu.com/n4764du/", new File("web"));
		org.jsoup.select.Elements baseIndex = Jsoup.parse("<div class=\"p-eplist\"><div>第1話</div></div>").select(".p-eplist");
		Assert.assertFalse(converter.appendPagedIndex(baseIndex, Jsoup.parse("<html><body>temporary response</body></html>")));
		Assert.assertEquals(1, baseIndex.first().childrenSize());
		Assert.assertTrue(converter.appendPagedIndex(baseIndex, Jsoup.parse("<div class=\"p-eplist\"><div>第101話</div></div>")));
		Assert.assertEquals(2, baseIndex.first().childrenSize());
	}

	@Test
	public void estimatesDownloadTimeIncludingConfiguredBatchPauses()
	{
		Assert.assertEquals(1_670_000L, WebAozoraConverter.estimateDownloadMillis(77, 10_000, 5, 60_000));
		Assert.assertEquals("約27分50秒", WebAozoraConverter.formatEstimatedDownloadTime(1_670_000L));
		Assert.assertEquals("約0秒", WebAozoraConverter.formatEstimatedDownloadTime(0));
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
    public void extractsUnlinkedHamelnAuthorFromItemprop() throws Exception
    {
        WebAozoraConverter converter = createHamelnConverter();
        Document document = Jsoup.parse("<main id=\"maind\"><div align=\"right\">作者：<span itemprop=\"author\">HLNF会長</span></div><div class=\"ss\"><a>作品タグ</a></div></main>");
        Assert.assertEquals("HLNF会長", converter.getExtractText(document,
            converter.queryMap.get(ExtractInfo.ExtractId.AUTHOR)));
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
