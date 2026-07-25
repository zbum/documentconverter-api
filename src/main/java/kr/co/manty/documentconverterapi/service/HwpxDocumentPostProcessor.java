package kr.co.manty.documentconverterapi.service;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

final class HwpxDocumentPostProcessor {

    private static final String HP_NS = "http://www.hancom.co.kr/hwpml/2011/paragraph";
    private static final String NORMAL_LINE_SEGMENT_FLAGS = "2147876864";
    private static final String OBJECT_LINE_SEGMENT_FLAGS = "393216";
    private static final String CODE_LINE_BREAK_MARKER = "\u241E";
    private static final int DEFAULT_BODY_WIDTH = 42520;
    private static final int DEFAULT_BODY_HEIGHT = 74268;
    private static final int DEFAULT_LINE_HEIGHT = 1000;
    private static final int HWPX_H1_HEIGHT = 1600;
    private static final int HWPX_H2_HEIGHT = 1400;
    private static final int HWPX_H3_HEIGHT = 1200;
    private static final int IMAGE_FALLBACK_HEIGHT = millimetersToHwp(40.0);
    private static final long CODE_LINE_HEIGHT = 1105L;
    private static final int CODE_TABLE_CELL_MARGIN = millimetersToHwp(2.0);
    private static final long CODE_TABLE_MIN_HEIGHT = millimetersToHwp(7.0);
    private static final Pattern SECTION_XML_ENTRY = Pattern.compile("Contents/section\\d+\\.xml");
    private static final Pattern TEXT_ELEMENT = Pattern.compile("<hp:t>(.*?)</hp:t>", Pattern.DOTALL);
    private static final Pattern TABLE_ELEMENT = Pattern.compile("<hp:tbl\\b.*?</hp:tbl>", Pattern.DOTALL);
    private static final Pattern LINE_BREAK_ELEMENT = Pattern.compile("<hp:lineBreak\\b[^>]*/>");
    private static final Pattern SIMPLE_LIST_PARAGRAPH = Pattern.compile(
            "(<hp:p\\b[^>]*)(>\\s*<hp:run\\b[^>]*>\\s*<hp:t>)( *)(?:-|\\*)\\s+([^<]*)(</hp:t>\\s*</hp:run>\\s*</hp:p>)",
            Pattern.DOTALL
    );
    private static final Pattern PARA_PR_ID = Pattern.compile("<hh:paraPr\\s+id=\"(\\d+)\"");
    private static final Pattern CHAR_PR_ID = Pattern.compile("<hh:charPr\\s+id=\"(\\d+)\"");
    private static final Pattern BORDER_FILL_ID = Pattern.compile("<hh:borderFill\\s+id=\"(\\d+)\"");
    private static final Pattern CHAR_PROPERTIES_OPEN = Pattern.compile("<hh:charProperties itemCnt=\"(\\d+)\">");
    private static final Pattern BORDER_FILLS_OPEN = Pattern.compile("<hh:borderFills itemCnt=\"(\\d+)\">");
    private static final Pattern CODE_TABLE_BORDER_FILL = Pattern.compile(
            "<hh:borderFill\\s+id=\"(\\d+)\"[^>]*>(?:(?!</hh:borderFill>).)*faceColor=\"#F6F8FA\"(?:(?!</hh:borderFill>).)*</hh:borderFill>",
            Pattern.DOTALL
    );
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
            HeaderResult header = ensureHeaderStyles(xmlString(headerBytes));
            state.bulletParaPrId(header.bulletParaPrId());
            state.codeBorderFillId(header.codeBorderFillId());
            state.headingStyles(header.headingStyles());
            entries.put("Contents/header.xml", xmlBytes(header.xml()));
        }

        for (Map.Entry<String, byte[]> entry : new ArrayList<>(entries.entrySet())) {
            if (!SECTION_XML_ENTRY.matcher(entry.getKey()).matches()) {
                continue;
            }
            String sectionXml = xmlString(entry.getValue());
            sectionXml = normalizeCodeTables(sectionXml, state.codeBorderFillId());
            sectionXml = replaceMarkdownImageMarkers(sectionXml, state);
            sectionXml = normalizeListParagraphs(sectionXml, state.bulletParaPrId());
            sectionXml = normalizeHeadingParagraphs(sectionXml, state.headingStyles());
            sectionXml = addLineSegments(sectionXml, state.headingStyles());
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

    private static HeaderResult ensureHeaderStyles(String headerXml) {
        BulletParagraphResult bulletResult = ensureBulletParagraphStyle(headerXml);
        BorderFillResult codeBorderFill = ensureCodeTableBorderFill(bulletResult.xml());
        HeadingStyleResult headingStyles = ensureHeadingCharStyles(codeBorderFill.xml());
        return new HeaderResult(
                headingStyles.xml(),
                bulletResult.bulletParaPrId(),
                codeBorderFill.borderFillId(),
                headingStyles.headingStyles()
        );
    }

    private static HeadingStyleResult ensureHeadingCharStyles(String headerXml) {
        String updated = headerXml;
        Map<Integer, HeadingStyle> headingStyles = new LinkedHashMap<>();
        int[][] styleDefinitions = {
                {1, HWPX_H1_HEIGHT},
                {2, HWPX_H2_HEIGHT},
                {3, HWPX_H3_HEIGHT}
        };

        for (int[] styleDefinition : styleDefinitions) {
            int level = styleDefinition[0];
            int height = styleDefinition[1];
            int charPrId = nextCharPrId(updated);
            updated = insertHeadingCharPr(updated, charPrId, height);
            headingStyles.put(level, new HeadingStyle(charPrId, height));
        }
        return new HeadingStyleResult(updated, Map.copyOf(headingStyles));
    }

    private static BulletParagraphResult ensureBulletParagraphStyle(String headerXml) {
        Matcher existingBulletParaPr = BULLET_PARA_PR.matcher(headerXml);
        if (existingBulletParaPr.find()) {
            return new BulletParagraphResult(headerXml, Integer.parseInt(existingBulletParaPr.group(1)));
        }

        int bulletId = 1;
        String updated = headerXml;
        if (!updated.contains("<hh:bullets")) {
            updated = insertBulletDefinition(updated, bulletId);
        }

        int bulletParaPrId = nextParaPrId(updated);
        updated = insertBulletParaPr(updated, bulletParaPrId, bulletId);
        return new BulletParagraphResult(updated, bulletParaPrId);
    }

    private static BorderFillResult ensureCodeTableBorderFill(String headerXml) {
        Matcher existingCodeBorderFill = CODE_TABLE_BORDER_FILL.matcher(headerXml);
        if (existingCodeBorderFill.find()) {
            return new BorderFillResult(headerXml, Integer.parseInt(existingCodeBorderFill.group(1)));
        }

        Matcher matcher = BORDER_FILLS_OPEN.matcher(headerXml);
        if (!matcher.find()) {
            return new BorderFillResult(headerXml, -1);
        }

        int borderFillId = nextBorderFillId(headerXml);
        int itemCount = Integer.parseInt(matcher.group(1));
        String updatedOpen = matcher.group(0)
                .replace("itemCnt=\"" + itemCount + "\"", "itemCnt=\"" + (itemCount + 1) + "\"");
        String updated = headerXml.substring(0, matcher.start()) + updatedOpen + headerXml.substring(matcher.end());
        String borderFill = """
                <hh:borderFill id="%d" threeD="0" shadow="0" centerLine="NONE" breakCellSeparateLine="0"><hh:slash type="NONE" Crooked="0" isCounter="0"/><hh:backSlash type="NONE" Crooked="0" isCounter="0"/><hh:leftBorder type="SOLID" width="0.12 mm" color="#D0D7DE"/><hh:rightBorder type="SOLID" width="0.12 mm" color="#D0D7DE"/><hh:topBorder type="SOLID" width="0.12 mm" color="#D0D7DE"/><hh:bottomBorder type="SOLID" width="0.12 mm" color="#D0D7DE"/><hh:diagonal type="SOLID" width="0.1 mm" color="#000000"/><hc:fillBrush><hc:winBrush faceColor="#F6F8FA" hatchColor="#000000" alpha="0"/></hc:fillBrush></hh:borderFill>"""
                .formatted(borderFillId);
        return new BorderFillResult(updated.replace("</hh:borderFills>", borderFill + "</hh:borderFills>"), borderFillId);
    }

    private static String insertBulletDefinition(String headerXml, int bulletId) {
        String bulletXml = """
                <hh:bullets itemCnt="1"><hh:bullet id="%d" char="•" useImage="0"><hh:paraHead level="0" align="LEFT" useInstWidth="0" autoIndent="1" widthAdjust="0" textOffsetType="PERCENT" textOffset="50" numFormat="DIGIT" charPrIDRef="4294967295" checkable="0"/></hh:bullet></hh:bullets>"""
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

    private static int nextCharPrId(String headerXml) {
        int max = -1;
        Matcher matcher = CHAR_PR_ID.matcher(headerXml);
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max + 1;
    }

    private static String insertHeadingCharPr(String headerXml, int charPrId, int height) {
        Matcher matcher = CHAR_PROPERTIES_OPEN.matcher(headerXml);
        if (!matcher.find()) {
            return headerXml;
        }

        int itemCount = Integer.parseInt(matcher.group(1));
        String updatedOpen = matcher.group(0).replace("itemCnt=\"" + itemCount + "\"", "itemCnt=\"" + (itemCount + 1) + "\"");
        String updated = headerXml.substring(0, matcher.start()) + updatedOpen + headerXml.substring(matcher.end());
        String charPr = """
                <hh:charPr id="%d" height="%d" textColor="#111827" shadeColor="none" useFontSpace="0" useKerning="0" symMark="NONE" borderFillIDRef="2"><hh:fontRef hangul="1" latin="1" hanja="1" japanese="1" other="1" symbol="1" user="1"/><hh:ratio hangul="100" latin="100" hanja="100" japanese="100" other="100" symbol="100" user="100"/><hh:spacing hangul="0" latin="0" hanja="0" japanese="0" other="0" symbol="0" user="0"/><hh:relSz hangul="100" latin="100" hanja="100" japanese="100" other="100" symbol="100" user="100"/><hh:offset hangul="0" latin="0" hanja="0" japanese="0" other="0" symbol="0" user="0"/><hh:bold/><hh:underline type="NONE" shape="SOLID" color="#000000"/><hh:strikeout shape="NONE" color="#000000"/><hh:outline type="NONE"/><hh:shadow type="NONE" color="#B2B2B2" offsetX="10" offsetY="10"/></hh:charPr>"""
                .formatted(charPrId, height);
        return updated.replace("</hh:charProperties>", charPr + "</hh:charProperties>");
    }

    private static int nextBorderFillId(String headerXml) {
        int max = -1;
        Matcher matcher = BORDER_FILL_ID.matcher(headerXml);
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max + 1;
    }

    private static String normalizeCodeTables(String sectionXml, int codeBorderFillId) {
        Matcher matcher = TABLE_ELEMENT.matcher(sectionXml);
        StringBuffer buffer = new StringBuffer(sectionXml.length());
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(normalizeCodeTable(matcher.group(), codeBorderFillId)));
        }
        matcher.appendTail(buffer);
        return buffer.toString().replace(CODE_LINE_BREAK_MARKER, "<hp:lineBreak/>");
    }

    private static String normalizeCodeTable(String tableXml, int codeBorderFillId) {
        if (!isCodeTable(tableXml)) {
            return tableXml;
        }

        int lineCount = codeLineCount(tableXml);
        long contentHeight = (CODE_TABLE_CELL_MARGIN * 2L)
                + DEFAULT_LINE_HEIGHT
                + Math.max(0, lineCount - 1L) * lineAdvance(DEFAULT_LINE_HEIGHT);
        long height = Math.max(CODE_TABLE_MIN_HEIGHT, Math.max(contentHeight, (CODE_TABLE_CELL_MARGIN * 2L) + lineCount * CODE_LINE_HEIGHT));
        String updated = replaceFirstElementAttribute(tableXml, "hp:sz", "height", height);
        updated = replaceFirstElementAttribute(updated, "hp:cellSz", "height", height);
        updated = replaceFirstElementAttribute(updated, "hp:cellMargin", "left", CODE_TABLE_CELL_MARGIN);
        updated = replaceFirstElementAttribute(updated, "hp:cellMargin", "right", CODE_TABLE_CELL_MARGIN);
        updated = replaceFirstElementAttribute(updated, "hp:cellMargin", "top", CODE_TABLE_CELL_MARGIN);
        updated = replaceFirstElementAttribute(updated, "hp:cellMargin", "bottom", CODE_TABLE_CELL_MARGIN);
        if (codeBorderFillId > 0) {
            String borderFillId = String.valueOf(codeBorderFillId);
            updated = setFirstElementAttribute(updated, "hp:tbl", "borderFillIDRef", borderFillId);
            updated = setFirstElementAttribute(updated, "hp:tc", "borderFillIDRef", borderFillId);
        }
        return updated.replace(CODE_LINE_BREAK_MARKER, "<hp:lineBreak/>");
    }

    private static boolean isCodeTable(String tableXml) {
        return tableXml.contains(CODE_LINE_BREAK_MARKER) || LINE_BREAK_ELEMENT.matcher(tableXml).find();
    }

    private static int codeLineCount(String tableXml) {
        return Math.max(
                1,
                1 + countOccurrences(tableXml, CODE_LINE_BREAK_MARKER) + countMatches(LINE_BREAK_ELEMENT, tableXml)
        );
    }

    private static String replaceFirstElementAttribute(String xml, String elementName, String attributeName, long value) {
        return setFirstElementAttribute(xml, elementName, attributeName, String.valueOf(value));
    }

    private static String setFirstElementAttribute(String xml, String elementName, String attributeName, String value) {
        Pattern pattern = Pattern.compile("(<\\Q" + elementName + "\\E\\b[^>]*?)(/?>)");
        Matcher matcher = pattern.matcher(xml);
        if (!matcher.find()) {
            return xml;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(
                setAttribute(matcher.group(1), attributeName, value) + matcher.group(2)
        ));
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

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
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

    private static String normalizeHeadingParagraphs(String sectionXml, Map<Integer, HeadingStyle> headingStyles) throws IOException {
        if (headingStyles.isEmpty()) {
            return sectionXml;
        }

        try {
            Document document = parseXml(sectionXml);
            boolean changed = false;
            NodeList paragraphs = document.getElementsByTagNameNS(HP_NS, "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element paragraph = (Element) paragraphs.item(i);
                if (nearestAncestor(paragraph, HP_NS, "tc") != null) {
                    continue;
                }

                changed = applyHeadingStyle(paragraph, headingStyles) || changed;
            }
            return changed ? serializeXml(document) : sectionXml;
        } catch (Exception e) {
            throw new IOException("Failed to normalize HWPX heading paragraphs", e);
        }
    }

    private static boolean applyHeadingStyle(Element paragraph, Map<Integer, HeadingStyle> headingStyles) {
        Element firstText = firstDirectText(paragraph);
        if (firstText == null) {
            return false;
        }

        String text = firstText.getTextContent();
        HeadingMarker marker = headingMarker(text);
        if (marker == null) {
            return false;
        }

        HeadingStyle style = headingStyles.get(marker.level());
        if (style == null) {
            return false;
        }

        firstText.setTextContent(text.substring(marker.endIndex()));
        for (Element run : directChildren(paragraph, HP_NS, "run")) {
            run.setAttribute("charPrIDRef", String.valueOf(style.charPrId()));
        }
        return true;
    }

    private static Element firstDirectText(Element paragraph) {
        for (Element run : directChildren(paragraph, HP_NS, "run")) {
            Element text = directChild(run, HP_NS, "t");
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static HeadingMarker headingMarker(String text) {
        if (text == null || !text.startsWith(MarkdownDocumentPreProcessor.HWPX_HEADING_MARKER_PREFIX)) {
            return null;
        }

        int levelStart = MarkdownDocumentPreProcessor.HWPX_HEADING_MARKER_PREFIX.length();
        int levelEnd = text.indexOf(MarkdownDocumentPreProcessor.HWPX_HEADING_MARKER_SUFFIX, levelStart);
        if (levelEnd < 0) {
            return null;
        }

        try {
            int level = Integer.parseInt(text.substring(levelStart, levelEnd));
            return new HeadingMarker(Math.max(1, Math.min(3, level)), levelEnd + MarkdownDocumentPreProcessor.HWPX_HEADING_MARKER_SUFFIX.length());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String addLineSegments(String sectionXml, Map<Integer, HeadingStyle> headingStyles) throws IOException {
        try {
            Document document = parseXml(sectionXml);
            applyTableCellLineSegments(document);
            applySectionLineSegments(document, headingStyles);
            return serializeXml(document);
        } catch (Exception e) {
            throw new IOException("Failed to add HWPX line segments", e);
        }
    }

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String serializeXml(Document document) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static void applyTableCellLineSegments(Document document) {
        NodeList cells = document.getElementsByTagNameNS(HP_NS, "tc");
        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);
            Element subList = directChild(cell, HP_NS, "subList");
            if (subList == null) {
                continue;
            }

            int verticalPosition = 0;
            for (Element paragraph : directChildren(subList, HP_NS, "p")) {
                verticalPosition = applyLineSegments(
                        document,
                        paragraph,
                        directChild(paragraph, HP_NS, "linesegarray"),
                        verticalPosition,
                        lineWidth(paragraph, DEFAULT_BODY_WIDTH),
                        Map.of()
                );
            }
        }
    }

    private static void applySectionLineSegments(Document document, Map<Integer, HeadingStyle> headingStyles) {
        Element section = document.getDocumentElement();
        PageMetrics pageMetrics = pageMetrics(document);
        int verticalPosition = 0;

        for (Element paragraph : directChildren(section, HP_NS, "p")) {
            LineSegmentPlan plan = lineSegmentPlan(
                    paragraph,
                    directChild(paragraph, HP_NS, "linesegarray"),
                    pageMetrics.bodyWidth(),
                    headingStyles
            );
            if (verticalPosition > 0 && verticalPosition + plan.height() > pageMetrics.bodyHeight()) {
                verticalPosition = 0;
            }
            verticalPosition = applyLineSegments(
                    document,
                    paragraph,
                    directChild(paragraph, HP_NS, "linesegarray"),
                    verticalPosition,
                    pageMetrics.bodyWidth(),
                    headingStyles
            );
            if (verticalPosition > pageMetrics.bodyHeight()) {
                verticalPosition = 0;
            }
        }
    }

    private static int applyLineSegments(
            Document document,
            Element paragraph,
            Element existingLineSegArray,
            int verticalPosition,
            int paragraphWidth,
            Map<Integer, HeadingStyle> headingStyles
    ) {
        LineSegmentPlan plan = lineSegmentPlan(paragraph, existingLineSegArray, paragraphWidth, headingStyles);
        Element lineSegArray = existingLineSegArray != null
                ? existingLineSegArray
                : document.createElementNS(HP_NS, "hp:linesegarray");
        while (lineSegArray.hasChildNodes()) {
            lineSegArray.removeChild(lineSegArray.getFirstChild());
        }
        if (existingLineSegArray == null) {
            paragraph.appendChild(lineSegArray);
        }

        int currentVerticalPosition = verticalPosition;
        for (int start : plan.starts()) {
            Element lineSeg = document.createElementNS(HP_NS, "hp:lineseg");
            lineSeg.setAttribute("textpos", String.valueOf(start));
            lineSeg.setAttribute("vertpos", String.valueOf(currentVerticalPosition));
            lineSeg.setAttribute("vertsize", String.valueOf(plan.lineHeight()));
            lineSeg.setAttribute("textheight", String.valueOf(plan.lineHeight()));
            lineSeg.setAttribute("baseline", String.valueOf((int) Math.round(plan.lineHeight() * 0.85)));
            lineSeg.setAttribute("spacing", String.valueOf(Math.max(0, plan.lineAdvance() - plan.lineHeight())));
            lineSeg.setAttribute("horzpos", "0");
            lineSeg.setAttribute("horzsize", String.valueOf(plan.lineWidth()));
            lineSeg.setAttribute("flags", plan.objectOnly() ? OBJECT_LINE_SEGMENT_FLAGS : NORMAL_LINE_SEGMENT_FLAGS);
            lineSegArray.appendChild(lineSeg);
            currentVerticalPosition += plan.lineAdvance();
        }
        return currentVerticalPosition;
    }

    private static LineSegmentPlan lineSegmentPlan(
            Element paragraph,
            Element existingLineSegArray,
            int paragraphWidth,
            Map<Integer, HeadingStyle> headingStyles
    ) {
        LineSegmentSeed seed = lineSegmentSeed(existingLineSegArray);
        int styledLineHeight = paragraphStyledLineHeight(paragraph, headingStyles);
        if (styledLineHeight > seed.lineHeight()) {
            seed = new LineSegmentSeed(styledLineHeight, lineAdvance(styledLineHeight) - styledLineHeight);
        }

        String text = directParagraphText(paragraph);
        int objectHeight = directObjectHeight(paragraph);
        int lineHeight = Math.max(seed.lineHeight(), objectHeight);
        int lineAdvance = lineHeight + seed.spacing();
        int lineWidth = lineWidth(paragraph, paragraphWidth);
        boolean objectOnly = objectHeight > 0 && text.isBlank();
        List<Integer> starts = objectOnly
                ? List.of(0)
                : lineStarts(text, lineWidth, lineHeight);
        return new LineSegmentPlan(starts, lineHeight, lineAdvance, lineWidth, objectOnly);
    }

    private static int paragraphStyledLineHeight(Element paragraph, Map<Integer, HeadingStyle> headingStyles) {
        if (headingStyles.isEmpty()) {
            return 0;
        }

        int lineHeight = 0;
        for (Element run : directChildren(paragraph, HP_NS, "run")) {
            int charPrId = positiveIntAttribute(run, "charPrIDRef", -1);
            for (HeadingStyle style : headingStyles.values()) {
                if (style.charPrId() == charPrId) {
                    lineHeight = Math.max(lineHeight, style.lineHeight());
                }
            }
        }
        return lineHeight;
    }

    private static LineSegmentSeed lineSegmentSeed(Element lineSegArray) {
        Element lineSeg = lineSegArray == null ? null : directChild(lineSegArray, HP_NS, "lineseg");
        if (lineSeg == null) {
            return new LineSegmentSeed(DEFAULT_LINE_HEIGHT, lineAdvance(DEFAULT_LINE_HEIGHT) - DEFAULT_LINE_HEIGHT);
        }

        int lineHeight = positiveIntAttribute(
                lineSeg,
                "textheight",
                positiveIntAttribute(lineSeg, "vertsize", DEFAULT_LINE_HEIGHT)
        );
        int spacing = positiveIntAttribute(lineSeg, "spacing", lineAdvance(lineHeight) - lineHeight);
        return new LineSegmentSeed(lineHeight, spacing);
    }

    private static String directParagraphText(Element paragraph) {
        StringBuilder text = new StringBuilder();
        for (Element run : directChildren(paragraph, HP_NS, "run")) {
            for (Element t : directChildren(run, HP_NS, "t")) {
                appendTextContent(t, text);
            }
        }
        return text.toString();
    }

    private static void appendTextContent(Node node, StringBuilder text) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            } else if (isElement(child, HP_NS, "lineBreak")) {
                text.append('\n');
            }
        }
    }

    private static int directObjectHeight(Element paragraph) {
        int height = 0;
        for (Element run : directChildren(paragraph, HP_NS, "run")) {
            for (Element table : directChildren(run, HP_NS, "tbl")) {
                height = Math.max(height, elementHeight(table));
            }
            for (Element picture : directChildren(run, HP_NS, "pic")) {
                height = Math.max(height, elementHeight(picture));
            }
        }
        return height;
    }

    private static int elementHeight(Element object) {
        Element size = directChild(object, HP_NS, "sz");
        int height = 0;
        if (size != null) {
            height = Math.max(height, positiveIntAttribute(size, "height"));
        }
        Element currentSize = directChild(object, HP_NS, "curSz");
        if (currentSize != null) {
            height = Math.max(height, positiveIntAttribute(currentSize, "height"));
        }
        Element outMargin = directChild(object, HP_NS, "outMargin");
        if (outMargin != null && height > 0) {
            height += positiveIntAttribute(outMargin, "top");
            height += positiveIntAttribute(outMargin, "bottom");
        }
        return height;
    }

    private static int lineWidth(Element paragraph, int defaultWidth) {
        Element tableCell = nearestAncestor(paragraph, HP_NS, "tc");
        if (tableCell == null) {
            return defaultWidth;
        }

        int width = defaultWidth;
        Element cellSize = directChild(tableCell, HP_NS, "cellSz");
        if (cellSize != null) {
            width = positiveIntAttribute(cellSize, "width", defaultWidth);
        }

        Element cellMargin = directChild(tableCell, HP_NS, "cellMargin");
        if (cellMargin != null) {
            width -= positiveIntAttribute(cellMargin, "left");
            width -= positiveIntAttribute(cellMargin, "right");
        }
        return Math.max(1000, width);
    }

    private static PageMetrics pageMetrics(Document document) {
        NodeList pageProperties = document.getElementsByTagNameNS(HP_NS, "pagePr");
        if (pageProperties.getLength() == 0) {
            return new PageMetrics(DEFAULT_BODY_WIDTH, DEFAULT_BODY_HEIGHT);
        }

        Element pageProperty = (Element) pageProperties.item(0);
        int pageWidth = positiveIntAttribute(pageProperty, "width", DEFAULT_BODY_WIDTH);
        int pageHeight = positiveIntAttribute(pageProperty, "height", DEFAULT_BODY_HEIGHT);
        Element margin = directChild(pageProperty, HP_NS, "margin");
        if (margin == null) {
            return new PageMetrics(pageWidth, pageHeight);
        }

        int bodyWidth = pageWidth
                - positiveIntAttribute(margin, "left")
                - positiveIntAttribute(margin, "right")
                - positiveIntAttribute(margin, "gutter");
        int bodyHeight = pageHeight
                - positiveIntAttribute(margin, "top")
                - positiveIntAttribute(margin, "bottom");
        return new PageMetrics(
                Math.max(1000, bodyWidth),
                Math.max(1000, bodyHeight)
        );
    }

    private static List<Integer> lineStarts(String text, int bodyWidth, int lineHeight) {
        List<Integer> starts = new ArrayList<>();
        int offset = 0;
        String[] explicitLines = text.split("\n", -1);
        for (String explicitLine : explicitLines) {
            starts.addAll(wrappedLineStarts(explicitLine, offset, bodyWidth, lineHeight));
            offset += explicitLine.length() + 1;
        }
        if (starts.isEmpty()) {
            starts.add(0);
        }
        return starts;
    }

    private static List<Integer> wrappedLineStarts(String line, int offset, int bodyWidth, int lineHeight) {
        List<Integer> starts = new ArrayList<>();
        starts.add(offset);
        if (line.isBlank()) {
            return starts;
        }

        int lineStart = 0;
        int lastBreakable = -1;
        double currentWidth = 0;
        double maxWidth = bodyWidth * 0.96;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            currentWidth += charWidth(ch, lineHeight);
            if (Character.isWhitespace(ch)) {
                lastBreakable = i + 1;
            }

            if (currentWidth > maxWidth && i > lineStart) {
                int nextLineStart = lastBreakable > lineStart ? lastBreakable : i;
                while (nextLineStart < line.length() && Character.isWhitespace(line.charAt(nextLineStart))) {
                    nextLineStart++;
                }
                if (nextLineStart > lineStart && nextLineStart < line.length()) {
                    starts.add(offset + nextLineStart);
                    lineStart = nextLineStart;
                    lastBreakable = -1;
                    currentWidth = textWidth(line, lineStart, i + 1, lineHeight);
                }
            }
        }
        return starts;
    }

    private static double textWidth(String text, int start, int end, int lineHeight) {
        double width = 0;
        for (int i = start; i < end; i++) {
            width += charWidth(text.charAt(i), lineHeight);
        }
        return width;
    }

    private static double charWidth(char ch, int lineHeight) {
        if (Character.isWhitespace(ch)) {
            return lineHeight * 0.35;
        }
        if (ch < 128) {
            return Character.isLetterOrDigit(ch) ? lineHeight * 0.56 : lineHeight * 0.45;
        }
        if (isCjk(ch)) {
            return lineHeight;
        }
        return lineHeight * 0.8;
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static int lineAdvance(int lineHeight) {
        return (int) Math.round(lineHeight * 1.6);
    }

    private static List<Element> directChildren(Element parent, String namespace, String localName) {
        List<Element> children = new ArrayList<>();
        NodeList nodeList = parent.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node child = nodeList.item(i);
            if (isElement(child, namespace, localName)) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static Element directChild(Element parent, String namespace, String localName) {
        NodeList nodeList = parent.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node child = nodeList.item(i);
            if (isElement(child, namespace, localName)) {
                return (Element) child;
            }
        }
        return null;
    }

    private static Element nearestAncestor(Node node, String namespace, String localName) {
        Node current = node.getParentNode();
        while (current != null) {
            if (isElement(current, namespace, localName)) {
                return (Element) current;
            }
            current = current.getParentNode();
        }
        return null;
    }

    private static boolean isElement(Node node, String namespace, String localName) {
        return node.getNodeType() == Node.ELEMENT_NODE
                && namespace.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName());
    }

    private static int positiveIntAttribute(Element element, String name) {
        return positiveIntAttribute(element, name, 0);
    }

    private static int positiveIntAttribute(Element element, String name, int fallback) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    private record HeaderResult(String xml, int bulletParaPrId, int codeBorderFillId, Map<Integer, HeadingStyle> headingStyles) {
    }

    private record BulletParagraphResult(String xml, int bulletParaPrId) {
    }

    private record BorderFillResult(String xml, int borderFillId) {
    }

    private record HeadingStyleResult(String xml, Map<Integer, HeadingStyle> headingStyles) {
    }

    private record HeadingStyle(int charPrId, int lineHeight) {
    }

    private record HeadingMarker(int level, int endIndex) {
    }

    private record PageMetrics(int bodyWidth, int bodyHeight) {
    }

    private record LineSegmentSeed(int lineHeight, int spacing) {
    }

    private record LineSegmentPlan(
            List<Integer> starts,
            int lineHeight,
            int lineAdvance,
            int lineWidth,
            boolean objectOnly
    ) {
        private int height() {
            return starts.size() * lineAdvance;
        }
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
        private int codeBorderFillId = -1;
        private Map<Integer, HeadingStyle> headingStyles = Map.of();

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

        private int codeBorderFillId() {
            return codeBorderFillId;
        }

        private void codeBorderFillId(int codeBorderFillId) {
            this.codeBorderFillId = codeBorderFillId;
        }

        private Map<Integer, HeadingStyle> headingStyles() {
            return headingStyles;
        }

        private void headingStyles(Map<Integer, HeadingStyle> headingStyles) {
            this.headingStyles = headingStyles;
        }
    }
}
