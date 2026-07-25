package kr.co.manty.documentconverterapi.service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class HwpxDocumentPostProcessor {

    private static final String CODE_LINE_BREAK_MARKER = "\u241E";
    private static final int DEFAULT_BODY_WIDTH = 42520;
    private static final int DEFAULT_BODY_HEIGHT = 74268;
    private static final int IMAGE_FALLBACK_HEIGHT = millimetersToHwp(40.0);
    private static final long CODE_LINE_HEIGHT = 1105L;
    private static final long CODE_TABLE_VERTICAL_PADDING = 720L;
    private static final Pattern SECTION_XML_ENTRY = Pattern.compile("Contents/section\\d+\\.xml");
    private static final Pattern TEXT_ELEMENT = Pattern.compile("<hp:t>(.*?)</hp:t>", Pattern.DOTALL);
    private static final Pattern TABLE_ELEMENT = Pattern.compile("<hp:tbl\\b.*?</hp:tbl>", Pattern.DOTALL);
    private static final Pattern SIMPLE_LIST_PARAGRAPH = Pattern.compile(
            "(<hp:p\\b[^>]*)(>\\s*<hp:run\\b[^>]*>\\s*<hp:t>)( *)(?:-|\\*)\\s+([^<]*)(</hp:t>\\s*</hp:run>\\s*</hp:p>)",
            Pattern.DOTALL
    );
    private static final Pattern PARA_PR_ID = Pattern.compile("<hh:paraPr\\s+id=\"(\\d+)\"");
    private static final Pattern BULLET_PARA_PR = Pattern.compile(
            "<hh:paraPr\\s+id=\"(\\d+)\"[^>]*>.*?<hh:heading type=\"BULLET\"[^>]*/>.*?</hh:paraPr>",
            Pattern.DOTALL
    );
    private static final Pattern PARA_PROPERTIES_OPEN = Pattern.compile("<hh:paraProperties itemCnt=\"(\\d+)\">");
    private static final Pattern IMAGE_ENTRY_NAME = Pattern.compile("BinData/image(\\d+)\\.");
    private static final Pattern IMAGE_ITEM_ID = Pattern.compile("id=\"image(\\d+)\"");
    private static final HttpClient IMAGE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HwpxDocumentPostProcessor() {
    }

    static void apply(Path hwpxPath) throws IOException {
        LinkedHashMap<String, byte[]> entries = readZipEntries(hwpxPath);
        ProcessingState state = new ProcessingState(nextImageIndex(entries));

        byte[] headerBytes = entries.get("Contents/header.xml");
        if (headerBytes != null) {
            HeaderResult header = ensureBulletParagraphStyle(xmlString(headerBytes));
            state.bulletParaPrId(header.bulletParaPrId());
            entries.put("Contents/header.xml", xmlBytes(header.xml()));
        }

        for (Map.Entry<String, byte[]> entry : new ArrayList<>(entries.entrySet())) {
            if (!SECTION_XML_ENTRY.matcher(entry.getKey()).matches()) {
                continue;
            }
            String sectionXml = xmlString(entry.getValue());
            sectionXml = normalizeCodeTables(sectionXml);
            sectionXml = replaceMarkdownImageMarkers(sectionXml, state);
            sectionXml = normalizeListParagraphs(sectionXml, state.bulletParaPrId());
            entries.put(entry.getKey(), xmlBytes(sectionXml));
        }

        if (!state.images().isEmpty()) {
            byte[] contentHpfBytes = entries.get("Contents/content.hpf");
            if (contentHpfBytes != null) {
                entries.put("Contents/content.hpf", xmlBytes(addImageManifestItems(xmlString(contentHpfBytes), state.images())));
            }
            for (EmbeddedImage image : state.images()) {
                entries.put(image.entryName(), image.bytes());
            }
        }

        writeZipEntries(hwpxPath, entries);
    }

    private static LinkedHashMap<String, byte[]> readZipEntries(Path path) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zis.transferTo(bytes);
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }
        return entries;
    }

    private static void writeZipEntries(Path path, LinkedHashMap<String, byte[]> entries) throws IOException {
        Path tempPath = Files.createTempFile(path.getParent(), "hwpx-postprocess-", ".hwpx");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempPath))) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static HeaderResult ensureBulletParagraphStyle(String headerXml) {
        Matcher existingBulletParaPr = BULLET_PARA_PR.matcher(headerXml);
        if (existingBulletParaPr.find()) {
            return new HeaderResult(headerXml, Integer.parseInt(existingBulletParaPr.group(1)));
        }

        int bulletId = 1;
        String updated = headerXml;
        if (!updated.contains("<hh:bullets")) {
            updated = insertBulletDefinition(updated, bulletId);
        }

        int bulletParaPrId = nextParaPrId(updated);
        updated = insertBulletParaPr(updated, bulletParaPrId, bulletId);
        return new HeaderResult(updated, bulletParaPrId);
    }

    private static String insertBulletDefinition(String headerXml, int bulletId) {
        String bulletXml = """
                <hh:bullets itemCnt="1"><hh:bullet id="%d" char="&#x2022;" useImage="0"><hh:paraHead level="0" align="LEFT" useInstWidth="0" autoIndent="1" widthAdjust="0" textOffsetType="PERCENT" textOffset="50" numFormat="DIGIT" charPrIDRef="4294967295" checkable="0"/></hh:bullet></hh:bullets>"""
                .formatted(bulletId);

        int insertAfterNumberings = headerXml.indexOf("</hh:numberings>");
        if (insertAfterNumberings >= 0) {
            int insertPosition = insertAfterNumberings + "</hh:numberings>".length();
            return headerXml.substring(0, insertPosition) + bulletXml + headerXml.substring(insertPosition);
        }

        int insertBeforeParaProperties = headerXml.indexOf("<hh:paraProperties");
        if (insertBeforeParaProperties >= 0) {
            return headerXml.substring(0, insertBeforeParaProperties) + bulletXml + headerXml.substring(insertBeforeParaProperties);
        }

        return headerXml;
    }

    private static String insertBulletParaPr(String headerXml, int bulletParaPrId, int bulletId) {
        String bulletParaPr = """
                <hh:paraPr id="%d" tabPrIDRef="0" condense="0" fontLineHeight="0" snapToGrid="1" suppressLineNumbers="0" checked="0"><hh:align horizontal="JUSTIFY" vertical="BASELINE"/><hh:heading type="BULLET" idRef="%d" level="0"/><hh:breakSetting breakLatinWord="KEEP_WORD" breakNonLatinWord="KEEP_WORD" widowOrphan="0" keepWithNext="0" keepLines="0" pageBreakBefore="0" lineWrap="BREAK"/><hp:switch><hp:case hp:required-namespace="http://www.hancom.co.kr/hwpml/2016/HwpUnitChar"><hh:margin><hc:intent value="-350" unit="HWPUNIT"/><hc:left value="1100" unit="HWPUNIT"/><hc:right value="0" unit="HWPUNIT"/><hc:prev value="0" unit="HWPUNIT"/><hc:next value="45" unit="HWPUNIT"/></hh:margin><hh:lineSpacing type="PERCENT" value="155" unit="HWPUNIT"/></hp:case><hp:default><hh:margin><hc:intent value="-700" unit="HWPUNIT"/><hc:left value="2200" unit="HWPUNIT"/><hc:right value="0" unit="HWPUNIT"/><hc:prev value="0" unit="HWPUNIT"/><hc:next value="90" unit="HWPUNIT"/></hh:margin><hh:lineSpacing type="PERCENT" value="155" unit="HWPUNIT"/></hp:default></hp:switch><hh:autoSpacing eAsianEng="0" eAsianNum="0"/><hh:border borderFillIDRef="2" offsetLeft="0" offsetRight="0" offsetTop="0" offsetBottom="0" connect="0" ignoreMargin="0"/></hh:paraPr>"""
                .formatted(bulletParaPrId, bulletId);

        Matcher matcher = PARA_PROPERTIES_OPEN.matcher(headerXml);
        if (!matcher.find()) {
            return headerXml;
        }

        int itemCount = Integer.parseInt(matcher.group(1));
        String updatedOpen = matcher.group(0).replace("itemCnt=\"" + itemCount + "\"", "itemCnt=\"" + (itemCount + 1) + "\"");
        String updated = headerXml.substring(0, matcher.start()) + updatedOpen + headerXml.substring(matcher.end());
        return updated.replace("</hh:paraProperties>", bulletParaPr + "</hh:paraProperties>");
    }

    private static int nextParaPrId(String headerXml) {
        int max = -1;
        Matcher matcher = PARA_PR_ID.matcher(headerXml);
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max + 1;
    }

    private static String normalizeCodeTables(String sectionXml) {
        Matcher matcher = TABLE_ELEMENT.matcher(sectionXml);
        StringBuffer buffer = new StringBuffer(sectionXml.length());
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(normalizeCodeTable(matcher.group())));
        }
        matcher.appendTail(buffer);
        return buffer.toString().replace(CODE_LINE_BREAK_MARKER, "<hp:lineBreak/>");
    }

    private static String normalizeCodeTable(String tableXml) {
        if (!tableXml.contains(CODE_LINE_BREAK_MARKER)) {
            return tableXml;
        }

        int lineCount = 1 + countOccurrences(tableXml, CODE_LINE_BREAK_MARKER);
        long height = Math.max(1282L, CODE_TABLE_VERTICAL_PADDING + lineCount * CODE_LINE_HEIGHT);
        String updated = replaceFirstElementAttribute(tableXml, "hp:sz", "height", height);
        updated = replaceFirstElementAttribute(updated, "hp:cellSz", "height", height);
        return updated.replace(CODE_LINE_BREAK_MARKER, "<hp:lineBreak/>");
    }

    private static String replaceFirstElementAttribute(String xml, String elementName, String attributeName, long value) {
        Pattern pattern = Pattern.compile("(<\\Q" + elementName + "\\E\\b[^>]*\\b\\Q" + attributeName + "\\E=\")\\d+(\"[^>]*>)");
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) {
            return xml;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + value + matcher.group(2)));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

    private static String replaceMarkdownImageMarkers(String sectionXml, ProcessingState state) {
        Matcher matcher = TEXT_ELEMENT.matcher(sectionXml);
        StringBuffer buffer = new StringBuffer(sectionXml.length());
        while (matcher.find()) {
            String replacement = "<hp:t>" + replaceImageMarkersInText(matcher.group(1), state) + "</hp:t>";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceImageMarkersInText(String text, ProcessingState state) {
        StringBuilder replaced = new StringBuilder(text.length());
        int textPosition = 0;
        while (textPosition < text.length()) {
            int markerStart = text.indexOf(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX, textPosition);
            if (markerStart < 0) {
                replaced.append(text, textPosition, text.length());
                break;
            }

            int markerEnd = text.indexOf(
                    MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX,
                    markerStart + MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX.length()
            );
            if (markerEnd < 0) {
                replaced.append(text, textPosition, text.length());
                break;
            }

            int exclusiveEnd = markerEnd + MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX.length();
            ImageMarkerData marker = imageMarkerData(text.substring(markerStart, exclusiveEnd));
            if (marker == null) {
                replaced.append(text, textPosition, exclusiveEnd);
                textPosition = exclusiveEnd;
                continue;
            }

            replaced.append(text, textPosition, markerStart);
            try {
                DownloadedImage downloaded = downloadImage(marker.source());
                EmbeddedImage image = state.addImage(downloaded);
                replaced.append("</hp:t>");
                replaced.append(pictureXml(state.nextPictureId(), image, marker.altText()));
                replaced.append("<hp:t>");
            } catch (RuntimeException e) {
                replaced.append(escapeXml(marker.markdownFallback()));
            }
            textPosition = exclusiveEnd;
        }
        return replaced.toString();
    }

    private static ImageMarkerData imageMarkerData(String markerText) {
        if (!markerText.startsWith(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX)
                || !markerText.endsWith(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX)) {
            return null;
        }

        String encoded = markerText.substring(
                MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX.length(),
                markerText.length() - MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX.length()
        );
        String[] parts = encoded.split(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SEPARATOR, -1);
        if (parts.length != 3) {
            return null;
        }

        try {
            String source = decodeMarkerPart(parts[0]);
            if (source == null || source.isBlank()) {
                return null;
            }
            return new ImageMarkerData(source, emptyToNull(decodeMarkerPart(parts[1])), emptyToNull(decodeMarkerPart(parts[2])));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String decodeMarkerPart(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static DownloadedImage downloadImage(String source) {
        try {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                return downloadHttpImage(source);
            }
            return readLocalImage(source);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not load markdown image: " + source, e);
        }
    }

    private static DownloadedImage downloadHttpImage(String source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response = IMAGE_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Image request failed with HTTP " + response.statusCode());
        }
        byte[] bytes = response.body();
        String extension = imageExtension(bytes, response.headers().firstValue("content-type").orElse(source));
        ImageSize size = imageSize(bytes);
        return new DownloadedImage(bytes, extension, mediaType(extension), size);
    }

    private static DownloadedImage readLocalImage(String source) throws IOException {
        Path path = source.startsWith("file:")
                ? Path.of(URI.create(source))
                : Path.of(source);
        byte[] bytes = Files.readAllBytes(path);
        String extension = imageExtension(bytes, path.getFileName().toString());
        ImageSize size = imageSize(bytes);
        return new DownloadedImage(bytes, extension, mediaType(extension), size);
    }

    private static String imageExtension(byte[] bytes, String hint) {
        String magicExtension = imageExtensionFromMagic(bytes);
        if (magicExtension != null) {
            return magicExtension;
        }

        String normalized = hint == null ? "" : hint.toLowerCase(Locale.ROOT);
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (normalized.contains("png") || normalized.endsWith(".png")) {
            return "png";
        }
        if (normalized.contains("jpeg") || normalized.contains("jpg") || normalized.endsWith(".jpeg") || normalized.endsWith(".jpg")) {
            return "jpg";
        }
        if (normalized.contains("gif") || normalized.endsWith(".gif")) {
            return "gif";
        }
        if (normalized.contains("bmp") || normalized.endsWith(".bmp")) {
            return "bmp";
        }
        return "png";
    }

    private static String imageExtensionFromMagic(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47) {
            return "png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (bytes.length >= 6 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46) {
            return "gif";
        }
        if (bytes.length >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4d) {
            return "bmp";
        }
        return null;
    }

    private static ImageSize imageSize(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            return new ImageSize(0, 0);
        }
        return new ImageSize(image.getWidth(), image.getHeight());
    }

    private static String mediaType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    private static String pictureXml(int pictureId, EmbeddedImage image, String altText) {
        String scale = decimal(image.displaySize().width() / (double) image.nativeSize().width());
        String shapeComment = altText == null || altText.isBlank()
                ? ""
                : "<hp:shapeComment>" + escapeXml(altText) + "</hp:shapeComment>";

        return """
                <hp:pic id="%d" zOrder="%d" numberingType="PICTURE" textWrap="TOP_AND_BOTTOM" textFlow="BOTH_SIDES" lock="0" dropcapstyle="None" href="" groupLevel="0" instid="%d" reverse="0"><hp:offset x="0" y="0"/><hp:orgSz width="%d" height="%d"/><hp:curSz width="%d" height="%d"/><hp:flip horizontal="0" vertical="0"/><hp:rotationInfo angle="0" centerX="%d" centerY="%d" rotateimage="1"/><hp:renderingInfo><hc:transMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/><hc:scaMatrix e1="%s" e2="0" e3="0" e4="0" e5="%s" e6="0"/><hc:rotMatrix e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"/></hp:renderingInfo><hp:imgRect><hc:pt0 x="0" y="0"/><hc:pt1 x="%d" y="0"/><hc:pt2 x="%d" y="%d"/><hc:pt3 x="0" y="%d"/></hp:imgRect><hp:imgClip left="0" right="%d" top="0" bottom="%d"/><hp:inMargin left="0" right="0" top="0" bottom="0"/><hc:img binaryItemIDRef="%s" bright="0" contrast="0" effect="REAL_PIC" alpha="0"/><hp:effects/><hp:sz width="%d" widthRelTo="ABSOLUTE" height="%d" heightRelTo="ABSOLUTE" protect="0"/><hp:pos treatAsChar="1" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/><hp:outMargin left="0" right="0" top="0" bottom="0"/>%s</hp:pic>"""
                .formatted(
                        pictureId,
                        pictureId,
                        700000 + pictureId,
                        image.nativeSize().width(),
                        image.nativeSize().height(),
                        image.displaySize().width(),
                        image.displaySize().height(),
                        image.displaySize().width() / 2,
                        image.displaySize().height() / 2,
                        scale,
                        decimal(image.displaySize().height() / (double) image.nativeSize().height()),
                        image.nativeSize().width(),
                        image.nativeSize().width(),
                        image.nativeSize().height(),
                        image.nativeSize().height(),
                        image.nativeSize().width(),
                        image.nativeSize().height(),
                        image.id(),
                        image.displaySize().width(),
                        image.displaySize().height(),
                        shapeComment
                );
    }

    private static String normalizeListParagraphs(String sectionXml, int bulletParaPrId) {
        if (bulletParaPrId < 0) {
            return sectionXml;
        }

        Matcher tableMatcher = TABLE_ELEMENT.matcher(sectionXml);
        StringBuilder updated = new StringBuilder(sectionXml.length());
        int position = 0;
        while (tableMatcher.find()) {
            updated.append(normalizeListParagraphsOutsideTables(sectionXml.substring(position, tableMatcher.start()), bulletParaPrId));
            updated.append(tableMatcher.group());
            position = tableMatcher.end();
        }
        updated.append(normalizeListParagraphsOutsideTables(sectionXml.substring(position), bulletParaPrId));
        return updated.toString();
    }

    private static String normalizeListParagraphsOutsideTables(String xml, int bulletParaPrId) {
        if (xml.isEmpty()) {
            return xml;
        }

        Matcher matcher = SIMPLE_LIST_PARAGRAPH.matcher(xml);
        StringBuffer buffer = new StringBuffer(xml.length());
        while (matcher.find()) {
            String openTag = setAttribute(matcher.group(1), "paraPrIDRef", String.valueOf(bulletParaPrId));
            String replacement = openTag + matcher.group(2) + matcher.group(4).stripLeading() + matcher.group(5);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String setAttribute(String openTagPrefix, String attributeName, String value) {
        Pattern attribute = Pattern.compile("\\b\\Q" + attributeName + "\\E=\"[^\"]*\"");
        Matcher matcher = attribute.matcher(openTagPrefix);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(attributeName + "=\"" + value + "\""));
        }
        return openTagPrefix + " " + attributeName + "=\"" + value + "\"";
    }

    private static String addImageManifestItems(String contentHpf, List<EmbeddedImage> images) {
        StringBuilder itemXml = new StringBuilder();
        for (EmbeddedImage image : images) {
            itemXml.append("<opf:item id=\"")
                    .append(image.id())
                    .append("\" href=\"")
                    .append(image.entryName())
                    .append("\" media-type=\"")
                    .append(image.mediaType())
                    .append("\" isEmbeded=\"1\"/>");
        }
        return contentHpf.replace("</opf:manifest>", itemXml + "</opf:manifest>");
    }

    private static int nextImageIndex(Map<String, byte[]> entries) {
        int max = 0;
        for (String entryName : entries.keySet()) {
            Matcher matcher = IMAGE_ENTRY_NAME.matcher(entryName);
            if (matcher.find()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }

        byte[] contentHpfBytes = entries.get("Contents/content.hpf");
        if (contentHpfBytes != null) {
            Matcher matcher = IMAGE_ITEM_ID.matcher(xmlString(contentHpfBytes));
            while (matcher.find()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max + 1;
    }

    private static ImageDisplaySize displaySize(DownloadedImage image) {
        int nativeWidth = image.size().width() > 0
                ? pixelsToHwp(image.size().width())
                : Math.max(1000, (int) Math.round(DEFAULT_BODY_WIDTH * 0.8));
        int nativeHeight = image.size().height() > 0
                ? pixelsToHwp(image.size().height())
                : IMAGE_FALLBACK_HEIGHT;
        int maxWidth = Math.max(1000, (int) Math.round(DEFAULT_BODY_WIDTH * 0.96));
        int maxHeight = Math.max(1000, (int) Math.round(DEFAULT_BODY_HEIGHT * 0.75));

        double scale = Math.min(1.0, Math.min(maxWidth / (double) nativeWidth, maxHeight / (double) nativeHeight));
        return new ImageDisplaySize(
                Math.max(1, (int) Math.round(nativeWidth * scale)),
                Math.max(1, (int) Math.round(nativeHeight * scale))
        );
    }

    private static int pixelsToHwp(int pixels) {
        return (int) Math.round(pixels * 7200.0 / 96.0);
    }

    private static int millimetersToHwp(double millimeters) {
        return (int) Math.round(millimeters * 72000.0 / 254.0);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String xmlString(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] xmlBytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private record HeaderResult(String xml, int bulletParaPrId) {
    }

    private record ImageMarkerData(String source, String altText, String title) {
        private String markdownFallback() {
            String safeAlt = altText == null ? "" : altText;
            return "![" + safeAlt + "](" + source + ")";
        }
    }

    private record DownloadedImage(byte[] bytes, String extension, String mediaType, ImageSize size) {
    }

    private record ImageSize(int width, int height) {
    }

    private record ImageDisplaySize(int width, int height) {
    }

    private record EmbeddedImage(
            String id,
            String entryName,
            String mediaType,
            byte[] bytes,
            ImageDisplaySize nativeSize,
            ImageDisplaySize displaySize
    ) {
    }

    private static final class ProcessingState {
        private final List<EmbeddedImage> images = new ArrayList<>();
        private int nextImageIndex;
        private int nextPictureId = 1;
        private int bulletParaPrId = -1;

        private ProcessingState(int nextImageIndex) {
            this.nextImageIndex = nextImageIndex;
        }

        private EmbeddedImage addImage(DownloadedImage downloaded) {
            String id = "image" + nextImageIndex++;
            ImageDisplaySize nativeSize = new ImageDisplaySize(
                    downloaded.size().width() > 0 ? pixelsToHwp(downloaded.size().width()) : Math.max(1000, (int) Math.round(DEFAULT_BODY_WIDTH * 0.8)),
                    downloaded.size().height() > 0 ? pixelsToHwp(downloaded.size().height()) : IMAGE_FALLBACK_HEIGHT
            );
            EmbeddedImage image = new EmbeddedImage(
                    id,
                    "BinData/" + id + "." + downloaded.extension(),
                    downloaded.mediaType(),
                    downloaded.bytes(),
                    nativeSize,
                    displaySize(downloaded)
            );
            images.add(image);
            return image;
        }

        private int nextPictureId() {
            return nextPictureId++;
        }

        private List<EmbeddedImage> images() {
            return images;
        }

        private int bulletParaPrId() {
            return bulletParaPrId;
        }

        private void bulletParaPrId(int bulletParaPrId) {
            this.bulletParaPrId = bulletParaPrId;
        }
    }
}
