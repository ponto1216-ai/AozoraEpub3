package com.github.hmdev.web;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Assert;
import org.junit.Test;

public class WebAozoraConverterTest
{
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
