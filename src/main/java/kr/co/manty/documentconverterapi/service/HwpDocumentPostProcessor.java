package kr.co.manty.documentconverterapi.service;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlSectionDefine;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.HeightCriterion;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.HorzRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.ObjectNumberSort;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.RelativeArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.TextFlowMethod;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.TextHorzArrange;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.VertRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.WidthCriterion;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.GsoControlType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.ShapeComponent;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.lineinfo.LineType;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponenteach.ShapeComponentPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment;
import kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.DivideAtPageBoundary;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Table;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.object.docinfo.BinData;
import kr.dogfoot.hwplib.object.docinfo.Bullet;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataCompress;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataState;
import kr.dogfoot.hwplib.object.docinfo.bindata.BinDataType;
import kr.dogfoot.hwplib.object.docinfo.numbering.ParagraphAlignment;
import kr.dogfoot.hwplib.object.docinfo.numbering.ParagraphHeadInfo;
import kr.dogfoot.hwplib.object.docinfo.numbering.ValueType;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForEnglish;
import kr.dogfoot.hwplib.object.docinfo.parashape.LineDivideForHangul;
import kr.dogfoot.hwplib.object.docinfo.parashape.ParaHeadShape;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

final class HwpDocumentPostProcessor {

    private static final int H1_CHAR_SHAPE_ID = 0;
    private static final int H2_CHAR_SHAPE_ID = 1;
    private static final int H3_CHAR_SHAPE_ID = 2;
    private static final int DEFAULT_CHAR_SHAPE_ID = 5;
    private static final int H1_TOP_SPACING = 1800;
    private static final int H2_TOP_SPACING = 1500;
    private static final int H3_TOP_SPACING = 1200;
    private static final int DEFAULT_BODY_WIDTH = 42520;
    private static final int DEFAULT_BODY_HEIGHT = 74268;
    private static final int CODE_TABLE_CELL_MARGIN = millimetersToHwp(2.0);
    private static final int CODE_TABLE_MIN_HEIGHT = millimetersToHwp(7.0);
    private static final int CODE_CHAR_SHAPE_BASE_SIZE = 900;
    private static final String CODE_LINE_BREAK_MARKER = "\u241E";
    private static final int IMAGE_BOTTOM_MARGIN = millimetersToHwp(2.0);
    private static final int IMAGE_FALLBACK_HEIGHT = millimetersToHwp(40.0);
    private static final int BULLET_BASE_LEFT_MARGIN = millimetersToHwp(5.0);
    private static final int BULLET_DEPTH_MARGIN = millimetersToHwp(5.0);
    private static final int BULLET_HANGING_INDENT = millimetersToHwp(3.0);
    private static final double DEFAULT_LINE_SPACING_RATIO = 1.6;
    private static final HttpClient IMAGE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HwpDocumentPostProcessor() {
    }

    static void apply(HWPFile hwpFile) {
        List<CharShape> charShapes = hwpFile.getDocInfo().getCharShapeList();

        applyHeadingStyle(charShapes, H1_CHAR_SHAPE_ID, 1600);
        applyHeadingStyle(charShapes, H2_CHAR_SHAPE_ID, 1400);
        applyHeadingStyle(charShapes, H3_CHAR_SHAPE_ID, 1200);
        normalizeParagraphHeaders(hwpFile);
        normalizeSingleCellTables(hwpFile, charShapes);
        normalizeMarkdownListMarkers(hwpFile);
        embedMarkdownImages(hwpFile, charShapes);
        applyLineSegments(hwpFile, charShapes);
    }

    private static void applyHeadingStyle(List<CharShape> charShapes, int charShapeId, int baseSize) {
        if (charShapes.size() <= charShapeId) {
            return;
        }

        CharShape charShape = charShapes.get(charShapeId);
        charShape.setBaseSize(baseSize);
        charShape.getProperty().setBold(true);
    }

    private static void normalizeParagraphHeaders(HWPFile hwpFile) {
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                Paragraph paragraph = section.getParagraph(i);
                paragraph.getHeader().setLastInList(i == section.getParagraphCount() - 1);
                if (!hasSectionOrColumnControl(paragraph)) {
                    paragraph.getHeader().getDivideSort().setValue((short) 0);
                }
            }
        }
    }

    private static boolean hasSectionOrColumnControl(Paragraph paragraph) {
        if (paragraph.getControlList() == null) {
            return false;
        }
        return paragraph.getControlList().stream()
                .anyMatch(control -> control instanceof ControlSectionDefine
                        || control.getClass().getSimpleName().equals("ControlColumnDefine"));
    }

    private static void normalizeMarkdownListMarkers(HWPFile hwpFile) {
        int bulletId = -1;
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            for (Paragraph paragraph : section) {
                if (paragraph.getControlList() != null) {
                    continue;
                }

                BulletListItem item = bulletListItem(stripGeneratedParagraphBreak(paragraphText(paragraph)));
                if (item == null) {
                    continue;
                }

                if (bulletId < 0) {
                    bulletId = addBullet(hwpFile);
                }
                setParagraphText(paragraph, item.text());
                paragraph.getHeader().setParaShapeId(addBulletParaShape(hwpFile, paragraph, bulletId, item.depth()));
            }
        }
    }

    private static BulletListItem bulletListItem(String text) {
        int leadingSpaces = 0;
        while (leadingSpaces < text.length() && text.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        if (leadingSpaces + 1 >= text.length()
                || text.charAt(leadingSpaces) != '*'
                || !Character.isWhitespace(text.charAt(leadingSpaces + 1))) {
            return null;
        }

        int contentStart = leadingSpaces + 2;
        while (contentStart < text.length() && text.charAt(contentStart) == ' ') {
            contentStart++;
        }
        String content = text.substring(contentStart);
        if (content.isBlank()) {
            return null;
        }
        return new BulletListItem(content, leadingSpaces / 2);
    }

    private static int addBullet(HWPFile hwpFile) {
        Bullet bullet = hwpFile.getDocInfo().addNewBullet();
        bullet.getBulletChar().fromUTF16LEString("\u2022");
        bullet.getCheckBulletChar().fromUTF16LEString("");
        bullet.setImageBullet(false);

        ParagraphHeadInfo headInfo = bullet.getParagraphHeadInfo();
        headInfo.getProperty().setParagraphAlignment(ParagraphAlignment.Left);
        headInfo.getProperty().setFollowStringWidth(true);
        headInfo.getProperty().setAutoIndent(true);
        headInfo.getProperty().setValueTypeForDistanceFromBody(ValueType.Value);
        headInfo.setCorrectionValueForWidth(0);
        headInfo.setDistanceFromBody(BULLET_HANGING_INDENT);
        headInfo.setCharShapeID(DEFAULT_CHAR_SHAPE_ID);

        int bulletId = hwpFile.getDocInfo().getBulletList().size() - 1;
        hwpFile.getDocInfo().getIDMappings().setBulletCount(hwpFile.getDocInfo().getBulletList().size());
        return bulletId;
    }

    private static int addBulletParaShape(HWPFile hwpFile, Paragraph paragraph, int bulletId, int depth) {
        int sourceParaShapeId = paragraph.getHeader().getParaShapeId();
        ParaShape paraShape = hwpFile.getDocInfo().getParaShapeList().get(sourceParaShapeId).clone();
        paraShape.getProperty1().setParaHeadShape(ParaHeadShape.Bullet);
        paraShape.setParaHeadId(bulletId);
        paraShape.setLeftMargin(Math.max(
                paraShape.getLeftMargin(),
                BULLET_BASE_LEFT_MARGIN + Math.max(0, depth) * BULLET_DEPTH_MARGIN
        ));
        paraShape.setIndent(-BULLET_HANGING_INDENT);

        hwpFile.getDocInfo().getParaShapeList().add(paraShape);
        hwpFile.getDocInfo().getIDMappings().setParaShapeCount(hwpFile.getDocInfo().getParaShapeList().size());
        return hwpFile.getDocInfo().getParaShapeList().size() - 1;
    }

    private static void embedMarkdownImages(HWPFile hwpFile, List<CharShape> charShapes) {
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            PageMetrics pageMetrics = pageMetrics(section);
            for (Paragraph paragraph : section) {
                if (paragraph.getControlList() != null) {
                    continue;
                }

                String text = stripGeneratedParagraphBreak(paragraphText(paragraph));
                List<ImageMarkerMatch> matches = imageMarkerMatches(text);
                if (matches.isEmpty()) {
                    continue;
                }

                try {
                    List<DownloadedImage> images = new ArrayList<>(matches.size());
                    for (ImageMarkerMatch match : matches) {
                        images.add(downloadImage(match.image().source()));
                    }
                    replaceParagraphImageMarkers(
                            hwpFile,
                            paragraph,
                            text,
                            matches,
                            images,
                            pageMetrics.bodyWidth(),
                            pageMetrics.bodyHeight(),
                            charShapes
                    );
                } catch (RuntimeException e) {
                    setParagraphText(paragraph, imageMarkerFallbackText(text, matches));
                }
            }
        }
    }

    private static List<ImageMarkerMatch> imageMarkerMatches(String text) {
        List<ImageMarkerMatch> matches = new ArrayList<>();
        int searchStart = 0;
        while (searchStart < text.length()) {
            int markerStart = text.indexOf(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX, searchStart);
            if (markerStart < 0) {
                break;
            }

            int markerEnd = text.indexOf(
                    MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX,
                    markerStart + MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX.length()
            );
            if (markerEnd < 0) {
                break;
            }

            int exclusiveEnd = markerEnd + MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX.length();
            ImageMarkerData image = imageMarkerData(text.substring(markerStart, exclusiveEnd));
            if (image == null) {
                searchStart = markerStart + MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX.length();
                continue;
            }

            matches.add(new ImageMarkerMatch(markerStart, exclusiveEnd, image));
            searchStart = exclusiveEnd;
        }
        return matches;
    }

    private static ImageMarkerData imageMarkerData(String text) {
        if (!text.startsWith(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX)
                || !text.endsWith(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX)) {
            return null;
        }

        String encoded = text.substring(
                MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX.length(),
                text.length() - MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_SUFFIX.length()
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
        return new DownloadedImage(bytes, extension, size.width(), size.height());
    }

    private static DownloadedImage readLocalImage(String source) throws IOException {
        Path path;
        if (source.startsWith("file:")) {
            path = Path.of(URI.create(source));
        } else {
            path = Path.of(source);
        }

        byte[] bytes = Files.readAllBytes(path);
        String extension = imageExtension(bytes, path.getFileName().toString());
        ImageSize size = imageSize(bytes);
        return new DownloadedImage(bytes, extension, size.width(), size.height());
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

    private static void replaceParagraphImageMarkers(
            HWPFile hwpFile,
            Paragraph paragraph,
            String text,
            List<ImageMarkerMatch> matches,
            List<DownloadedImage> images,
            int bodyWidth,
            int bodyHeight,
            List<CharShape> charShapes
    ) {
        clearParagraphText(paragraph);
        ensureSingleCharShape(paragraph, availableCharShapeId(charShapes, firstCharShapeId(paragraph)));

        int textPosition = 0;
        for (int i = 0; i < matches.size(); i++) {
            ImageMarkerMatch match = matches.get(i);
            appendParagraphText(paragraph, text.substring(textPosition, match.start()));
            addPictureControl(hwpFile, paragraph, images.get(i), bodyWidth, bodyHeight);
            textPosition = match.end();
        }
        appendParagraphText(paragraph, text.substring(textPosition));
        paragraph.getHeader().setCharacterCount(paragraph.getText().getCharSize());
    }

    private static void addPictureControl(
            HWPFile hwpFile,
            Paragraph paragraph,
            DownloadedImage image,
            int bodyWidth,
            int bodyHeight
    ) {
        int binItemId = addEmbeddedImage(hwpFile, image);
        ImageDisplaySize displaySize = displaySize(image, bodyWidth, bodyHeight);
        paragraph.getText().addExtendCharForGSO();
        ControlPicture picture = (ControlPicture) paragraph.addNewGsoControl(GsoControlType.Picture);
        setPictureHeader(picture.getHeader(), displaySize);
        setPictureShape(picture.getShapeComponent(), displaySize);
        setPictureInfo(picture.getShapeComponentPicture(), binItemId, image, displaySize);
    }

    private static void appendParagraphText(Paragraph paragraph, String text) {
        if (text.isEmpty()) {
            return;
        }
        try {
            paragraph.getText().addString(text);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String imageMarkerFallbackText(String text, List<ImageMarkerMatch> matches) {
        StringBuilder fallback = new StringBuilder(text.length());
        int textPosition = 0;
        for (ImageMarkerMatch match : matches) {
            fallback.append(text, textPosition, match.start());
            fallback.append(match.image().markdownFallback());
            textPosition = match.end();
        }
        fallback.append(text.substring(textPosition));
        return fallback.toString();
    }

    private static int addEmbeddedImage(HWPFile hwpFile, DownloadedImage image) {
        int docInfoBinItemId = hwpFile.getDocInfo().getBinDataList().size() + 1;
        int storageBinDataId = nextStorageBinDataId(hwpFile);
        String name = "BIN%04d.%s".formatted(storageBinDataId, image.extension());

        BinData binData = hwpFile.getDocInfo().addNewBinData();
        binData.getProperty().setType(BinDataType.Embedding);
        binData.getProperty().setCompress(BinDataCompress.NoCompress);
        binData.getProperty().setState(BinDataState.SuccessAccess);
        binData.setBinDataID(storageBinDataId);
        binData.setExtensionForEmbedding(image.extension());
        hwpFile.getBinData().addNewEmbeddedBinaryData(name, image.bytes(), BinDataCompress.NoCompress);
        hwpFile.getDocInfo().getIDMappings().setBinDataCount(hwpFile.getDocInfo().getBinDataList().size());
        return docInfoBinItemId;
    }

    private static int nextStorageBinDataId(HWPFile hwpFile) {
        int maxStorageId = 0;
        for (EmbeddedBinaryData embeddedBinaryData : hwpFile.getBinData().getEmbeddedBinaryDataList()) {
            maxStorageId = Math.max(maxStorageId, storageBinDataId(embeddedBinaryData.getName()));
        }
        return maxStorageId + 1;
    }

    private static int storageBinDataId(String name) {
        if (name == null) {
            return 0;
        }
        String upperName = name.toUpperCase(Locale.ROOT);
        if (!upperName.startsWith("BIN")) {
            return 0;
        }

        int dotIndex = upperName.indexOf('.');
        int endIndex = dotIndex > 3 ? dotIndex : upperName.length();
        try {
            return Integer.parseInt(upperName.substring(3, endIndex));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ImageDisplaySize displaySize(DownloadedImage image, int bodyWidth, int bodyHeight) {
        int nativeWidth = image.width() > 0 ? pixelsToHwp(image.width()) : Math.max(1000, (int) Math.round(bodyWidth * 0.8));
        int nativeHeight = image.height() > 0 ? pixelsToHwp(image.height()) : IMAGE_FALLBACK_HEIGHT;
        int maxWidth = Math.max(1000, (int) Math.round(bodyWidth * 0.96));
        int maxHeight = Math.max(1000, (int) Math.round(bodyHeight * 0.75));

        double scale = Math.min(1.0, Math.min(maxWidth / (double) nativeWidth, maxHeight / (double) nativeHeight));
        return new ImageDisplaySize(
                Math.max(1, (int) Math.round(nativeWidth * scale)),
                Math.max(1, (int) Math.round(nativeHeight * scale))
        );
    }

    private static int pixelsToHwp(int pixels) {
        return (int) Math.round(pixels * 7200.0 / 96.0);
    }

    private static void setPictureHeader(CtrlHeaderGso header, ImageDisplaySize displaySize) {
        header.getProperty().setLikeWord(true);
        header.getProperty().setApplyLineSpace(true);
        header.getProperty().setVertRelTo(VertRelTo.Para);
        header.getProperty().setVertRelativeArrange(RelativeArrange.TopOrLeft);
        header.getProperty().setHorzRelTo(HorzRelTo.Para);
        header.getProperty().setHorzRelativeArrange(RelativeArrange.TopOrLeft);
        header.getProperty().setVertRelToParaLimit(false);
        header.getProperty().setAllowOverlap(false);
        header.getProperty().setWidthCriterion(WidthCriterion.Absolute);
        header.getProperty().setHeightCriterion(HeightCriterion.Absolute);
        header.getProperty().setProtectSize(false);
        header.getProperty().setTextFlowMethod(TextFlowMethod.FitWithText);
        header.getProperty().setTextHorzArrange(TextHorzArrange.LeftOnly);
        header.getProperty().setObjectNumberSort(ObjectNumberSort.Figure);
        header.setxOffset(0);
        header.setyOffset(0);
        header.setWidth(displaySize.width());
        header.setHeight(displaySize.height());
        header.setOutterMarginLeft(0);
        header.setOutterMarginRight(0);
        header.setOutterMarginTop(0);
        header.setOutterMarginBottom(IMAGE_BOTTOM_MARGIN);
        header.setPreventPageDivide(false);
    }

    private static void setPictureShape(ShapeComponent shape, ImageDisplaySize displaySize) {
        shape.setOffsetX(0);
        shape.setOffsetY(0);
        shape.setWidthAtCreate(displaySize.width());
        shape.setHeightAtCreate(displaySize.height());
        shape.setWidthAtCurrent(displaySize.width());
        shape.setHeightAtCurrent(displaySize.height());
        shape.setRotateXCenter(displaySize.width() / 2);
        shape.setRotateYCenter(displaySize.height() / 2);
        shape.setMatrixsNormal();
    }

    private static void setPictureInfo(
            ShapeComponentPicture pictureInfo,
            int binItemId,
            DownloadedImage image,
            ImageDisplaySize displaySize
    ) {
        pictureInfo.getPictureInfo().setBinItemID(binItemId);
        pictureInfo.setImageWidth(Math.max(1, image.width()));
        pictureInfo.setImageHeight(Math.max(1, image.height()));
        pictureInfo.setBorderThickness(0);
        pictureInfo.getBorderProperty().setLineType(LineType.None);
        pictureInfo.getLeftTop().setX(0);
        pictureInfo.getLeftTop().setY(0);
        pictureInfo.getRightTop().setX(displaySize.width());
        pictureInfo.getRightTop().setY(0);
        pictureInfo.getLeftBottom().setX(0);
        pictureInfo.getLeftBottom().setY(displaySize.height());
        pictureInfo.getRightBottom().setX(displaySize.width());
        pictureInfo.getRightBottom().setY(displaySize.height());
    }

    private static void clearParagraphText(Paragraph paragraph) {
        if (paragraph.getText() == null) {
            paragraph.createText();
        } else {
            paragraph.getText().getCharList().clear();
        }
    }

    private static void ensureSingleCharShape(Paragraph paragraph, int charShapeId) {
        if (paragraph.getCharShape() == null) {
            paragraph.createCharShape();
        }
        paragraph.getCharShape().getPositonShapeIdPairList().clear();
        paragraph.getCharShape().addParaCharShape(0, charShapeId);
        paragraph.getHeader().setCharShapeCount(1);
    }

    private static void normalizeSingleCellTables(HWPFile hwpFile, List<CharShape> charShapes) {
        CodeCellStyles codeCellStyles = new CodeCellStyles(-1, -1);
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            PageMetrics pageMetrics = pageMetrics(section);
            for (Paragraph paragraph : section) {
                if (paragraph.getControlList() == null) {
                    continue;
                }
                for (Control control : paragraph.getControlList()) {
                    if (control instanceof ControlTable table && isSingleCellTable(table)) {
                        codeCellStyles = resizeSingleCellTable(
                                hwpFile,
                                table,
                                pageMetrics.bodyWidth(),
                                charShapes,
                                codeCellStyles
                        );
                    }
                }
            }
        }
    }

    private static boolean isSingleCellTable(ControlTable table) {
        Table tableInfo = table.getTable();
        return tableInfo.getRowCount() == 1
                && tableInfo.getColumnCount() == 1
                && table.getRowList().size() == 1
                && table.getRowList().getFirst().getCellList().size() == 1;
    }

    private static CodeCellStyles resizeSingleCellTable(
            HWPFile hwpFile,
            ControlTable table,
            int bodyWidth,
            List<CharShape> charShapes,
            CodeCellStyles codeCellStyles
    ) {
        long tableWidth = Math.max(1000L, bodyWidth);
        Row row = table.getRowList().getFirst();
        Cell cell = row.getCellList().getFirst();
        rewriteMarkedCellParagraphs(cell);
        if (cell.getParagraphList().getParagraphCount() > 0) {
            codeCellStyles = ensureCodeCellStyles(hwpFile, charShapes, cell, codeCellStyles);
        }

        long textWidth = Math.max(1000L, tableWidth - (CODE_TABLE_CELL_MARGIN * 2L));
        applyCodeCellParaShape(cell, codeCellStyles.paraShapeId());
        applyCodeCellCharShape(cell, codeCellStyles.charShapeId());
        long tableHeight = singleCellTableHeight(cell, (int) textWidth, charShapes);

        table.getHeader().setxOffset(0);
        table.getHeader().setyOffset(0);
        table.getHeader().setWidth(tableWidth);
        table.getHeader().setHeight(tableHeight);
        table.getHeader().setOutterMarginLeft(0);
        table.getHeader().setOutterMarginRight(0);
        table.getHeader().setOutterMarginTop(0);
        table.getHeader().setOutterMarginBottom(0);
        table.getHeader().setPreventPageDivide(false);

        table.getTable().setCellSpacing(0);
        table.getTable().setLeftInnerMargin(0);
        table.getTable().setRightInnerMargin(0);
        table.getTable().setTopInnerMargin(0);
        table.getTable().setBottomInnerMargin(0);
        table.getTable().getProperty().setDivideAtPageBoundary(DivideAtPageBoundary.Divide);

        ListHeaderForCell cellHeader = cell.getListHeader();
        cellHeader.setWidth(tableWidth);
        cellHeader.setHeight(tableHeight);
        cellHeader.setLeftMargin(CODE_TABLE_CELL_MARGIN);
        cellHeader.setRightMargin(CODE_TABLE_CELL_MARGIN);
        cellHeader.setTopMargin(CODE_TABLE_CELL_MARGIN);
        cellHeader.setBottomMargin(CODE_TABLE_CELL_MARGIN);
        cellHeader.setTextWidth(textWidth);
        cellHeader.setParaCount(cell.getParagraphList().getParagraphCount());
        cellHeader.getProperty().setTextVerticalAlignment(TextVerticalAlignment.Top);

        applyCellLineSegments(cell, (int) textWidth, charShapes);
        return codeCellStyles;
    }

    private static CodeCellStyles ensureCodeCellStyles(
            HWPFile hwpFile,
            List<CharShape> charShapes,
            Cell cell,
            CodeCellStyles codeCellStyles
    ) {
        int paraShapeId = codeCellStyles.paraShapeId();
        int charShapeId = codeCellStyles.charShapeId();
        Paragraph templateParagraph = cell.getParagraphList().getParagraph(0);
        if (paraShapeId < 0) {
            paraShapeId = addCodeCellParaShape(hwpFile, templateParagraph);
        }
        if (charShapeId < 0) {
            charShapeId = addCodeCellCharShape(hwpFile, charShapes, templateParagraph);
        }
        return new CodeCellStyles(paraShapeId, charShapeId);
    }

    private static int addCodeCellParaShape(HWPFile hwpFile, Paragraph templateParagraph) {
        int sourceParaShapeId = templateParagraph.getHeader().getParaShapeId();
        ParaShape paraShape = hwpFile.getDocInfo().getParaShapeList().get(sourceParaShapeId).clone();
        paraShape.getProperty1().setAlignment(Alignment.Left);
        paraShape.getProperty1().setLineDivideForEnglish(LineDivideForEnglish.ByLetter);
        paraShape.getProperty1().setLineDivideForHangul(LineDivideForHangul.ByLetter);
        paraShape.getProperty1().setMinimumSpace((byte) 0);
        paraShape.getProperty2().setAutoAdjustGapHangulEnglish(false);
        paraShape.getProperty2().setAutoAdjustGapHangulNumber(false);
        hwpFile.getDocInfo().getParaShapeList().add(paraShape);
        return hwpFile.getDocInfo().getParaShapeList().size() - 1;
    }

    private static int addCodeCellCharShape(
            HWPFile hwpFile,
            List<CharShape> charShapes,
            Paragraph templateParagraph
    ) {
        int sourceCharShapeId = availableCharShapeId(charShapes, firstCharShapeId(templateParagraph));
        CharShape charShape = charShapes.get(sourceCharShapeId).clone();
        charShape.setBaseSize(CODE_CHAR_SHAPE_BASE_SIZE);
        charShape.getProperty().setBold(false);
        hwpFile.getDocInfo().getCharShapeList().add(charShape);
        return hwpFile.getDocInfo().getCharShapeList().size() - 1;
    }

    private static int availableCharShapeId(List<CharShape> charShapes, int charShapeId) {
        if (charShapeId >= 0 && charShapeId < charShapes.size()) {
            return charShapeId;
        }
        return Math.min(DEFAULT_CHAR_SHAPE_ID, charShapes.size() - 1);
    }

    private static void applyCodeCellParaShape(Cell cell, int paraShapeId) {
        if (paraShapeId < 0) {
            return;
        }
        for (Paragraph paragraph : cell.getParagraphList()) {
            paragraph.getHeader().setParaShapeId(paraShapeId);
        }
    }

    private static void applyCodeCellCharShape(Cell cell, int charShapeId) {
        if (charShapeId < 0) {
            return;
        }
        for (Paragraph paragraph : cell.getParagraphList()) {
            if (paragraph.getCharShape() == null) {
                paragraph.createCharShape();
            }
            paragraph.getCharShape().getPositonShapeIdPairList().clear();
            paragraph.getCharShape().addParaCharShape(0, charShapeId);
            paragraph.getHeader().setCharShapeCount(1);
        }
    }


    private static void rewriteMarkedCellParagraphs(Cell cell) {
        String text = stripGeneratedParagraphBreak(cellText(cell));
        if (!text.contains(CODE_LINE_BREAK_MARKER) || cell.getParagraphList().getParagraphCount() == 0) {
            return;
        }

        Paragraph template = cell.getParagraphList().getParagraph(0).clone();
        List<String> lines = codeLines(text);
        cell.getParagraphList().deleteAllParagraphs();
        for (int i = 0; i < lines.size(); i++) {
            Paragraph paragraph = template.clone();
            setParagraphText(paragraph, lines.get(i));
            paragraph.getHeader().setLastInList(i == lines.size() - 1);
            paragraph.getHeader().getDivideSort().setValue((short) 0);
            cell.getParagraphList().addParagraph(paragraph);
        }
    }

    private static List<String> codeLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split(CODE_LINE_BREAK_MARKER, -1)));
        while (lines.size() > 1 && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private static String stripGeneratedParagraphBreak(String text) {
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static void setParagraphText(Paragraph paragraph, String text) {
        if (paragraph.getText() == null) {
            paragraph.createText();
        } else {
            paragraph.getText().getCharList().clear();
        }
        try {
            paragraph.getText().addString(text);
            paragraph.getHeader().setCharacterCount(paragraph.getText().getCharSize());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long singleCellTableHeight(Cell cell, int textWidth, List<CharShape> charShapes) {
        long contentHeight = CODE_TABLE_CELL_MARGIN * 2L;
        for (Paragraph paragraph : cell.getParagraphList()) {
            int charShapeId = firstCharShapeId(paragraph);
            int lineHeight = lineHeight(charShapes, charShapeId);
            int lineAdvance = lineAdvance(lineHeight);
            contentHeight += lineStarts(paragraphText(paragraph), textWidth, lineHeight).size() * (long) lineAdvance;
        }
        return Math.max(CODE_TABLE_MIN_HEIGHT, contentHeight);
    }

    private static void applyCellLineSegments(Cell cell, int segmentWidth, List<CharShape> charShapes) {
        int paragraphCount = cell.getParagraphList().getParagraphCount();
        int verticalPosition = 0;
        for (int i = 0; i < paragraphCount; i++) {
            Paragraph paragraph = cell.getParagraphList().getParagraph(i);
            int charShapeId = firstCharShapeId(paragraph);
            int lineHeight = lineHeight(charShapes, charShapeId);
            int lineAdvance = lineAdvance(lineHeight);
            List<Integer> lineStarts = lineStarts(paragraphText(paragraph), segmentWidth, lineHeight);

            paragraph.createLineSeg();
            paragraph.getLineSeg().getLineSegItemList().clear();
            for (int lineStart : lineStarts) {
                addLineSegment(paragraph, lineStart, verticalPosition, lineHeight, lineAdvance, segmentWidth);
                verticalPosition += lineAdvance;
            }
            paragraph.getHeader().setLineAlignCount(paragraph.getLineSeg().getLineSegItemList().size());
            paragraph.getHeader().setLastInList(i == paragraphCount - 1);
            paragraph.getHeader().getDivideSort().setValue((short) 0);
        }
    }

    private static String cellText(Cell cell) {
        try {
            return cell.getParagraphList().getNormalString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void applyLineSegments(HWPFile hwpFile, List<CharShape> charShapes) {
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            PageMetrics pageMetrics = pageMetrics(section);
            int verticalPosition = 0;
            boolean seenVisibleContent = false;

            for (Paragraph paragraph : section) {
                int charShapeId = firstCharShapeId(paragraph);
                int pictureHeight = pictureControlHeight(paragraph);
                int lineHeight = Math.max(lineHeight(charShapes, charShapeId), pictureHeight);
                int lineAdvance = pictureHeight > 0 ? lineHeight + IMAGE_BOTTOM_MARGIN : lineAdvance(lineHeight);
                List<Integer> lineStarts = pictureHeight > 0
                        ? List.of(0)
                        : lineStarts(paragraphText(paragraph), pageMetrics.bodyWidth(), lineHeight);

                int paragraphHeight = lineStarts.size() * lineAdvance;
                int topSpacing = verticalPosition > 0 && seenVisibleContent
                        ? headingTopSpacing(charShapeId)
                        : 0;
                if (verticalPosition > 0 && verticalPosition + topSpacing + paragraphHeight > pageMetrics.bodyHeight()) {
                    verticalPosition = 0;
                    topSpacing = 0;
                }
                if (verticalPosition > 0 && topSpacing > 0) {
                    verticalPosition += topSpacing;
                }

                paragraph.createLineSeg();
                paragraph.getLineSeg().getLineSegItemList().clear();
                for (int lineStart : lineStarts) {
                    if (verticalPosition > 0 && verticalPosition + lineAdvance > pageMetrics.bodyHeight()) {
                        verticalPosition = 0;
                    }
                    addLineSegment(paragraph, lineStart, verticalPosition, lineHeight, lineAdvance, pageMetrics.bodyWidth());
                    verticalPosition += lineAdvance;
                }
                paragraph.getHeader().setLineAlignCount(paragraph.getLineSeg().getLineSegItemList().size());
                seenVisibleContent = seenVisibleContent || paragraphHasVisibleContent(paragraph);
            }
        }
    }

    private static int headingTopSpacing(int charShapeId) {
        return switch (charShapeId) {
            case H1_CHAR_SHAPE_ID -> H1_TOP_SPACING;
            case H2_CHAR_SHAPE_ID -> H2_TOP_SPACING;
            case H3_CHAR_SHAPE_ID -> H3_TOP_SPACING;
            default -> 0;
        };
    }

    private static boolean paragraphHasVisibleContent(Paragraph paragraph) {
        if (!paragraphText(paragraph).isBlank()) {
            return true;
        }
        if (paragraph.getControlList() == null) {
            return false;
        }
        return paragraph.getControlList().stream()
                .anyMatch(control -> !(control instanceof ControlSectionDefine)
                        && !control.getClass().getSimpleName().equals("ControlColumnDefine"));
    }

    private static int pictureControlHeight(Paragraph paragraph) {
        if (paragraph.getControlList() == null) {
            return 0;
        }

        long height = 0;
        for (Control control : paragraph.getControlList()) {
            if (control instanceof ControlPicture picture) {
                height = Math.max(height, picture.getHeader().getHeight());
            }
        }
        return height > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) height;
    }

    private static void addLineSegment(
            Paragraph paragraph,
            int textStartPosition,
            int verticalPosition,
            int lineHeight,
            int lineAdvance,
            int segmentWidth
    ) {
        LineSegItem lineSegItem = paragraph.getLineSeg().addNewLineSegItem();
        lineSegItem.setTextStartPosition(textStartPosition);
        lineSegItem.setLineVerticalPosition(verticalPosition);
        lineSegItem.setLineHeight(lineHeight);
        lineSegItem.setTextPartHeight(lineHeight);
        lineSegItem.setDistanceBaseLineToLineVerticalPosition((int) Math.round(lineHeight * 0.85));
        lineSegItem.setLineSpace(Math.max(0, lineAdvance - lineHeight));
        lineSegItem.setStartPositionFromColumn(0);
        lineSegItem.setSegmentWidth(segmentWidth);
        lineSegItem.getTag().setFirstSegmentAtLine(true);
        lineSegItem.getTag().setLastSegmentAtLine(true);
    }

    private static List<Integer> lineStarts(String text, int bodyWidth, int lineHeight) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        if (text.isBlank()) {
            return starts;
        }

        int lineStart = 0;
        int lastBreakable = -1;
        double currentWidth = 0;
        double maxWidth = bodyWidth * 0.96;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            currentWidth += charWidth(ch, lineHeight);
            if (Character.isWhitespace(ch)) {
                lastBreakable = i + 1;
            }

            if (currentWidth > maxWidth && i > lineStart) {
                int nextLineStart = lastBreakable > lineStart ? lastBreakable : i;
                while (nextLineStart < text.length() && Character.isWhitespace(text.charAt(nextLineStart))) {
                    nextLineStart++;
                }
                if (nextLineStart > lineStart && nextLineStart < text.length()) {
                    starts.add(nextLineStart);
                    lineStart = nextLineStart;
                    lastBreakable = -1;
                    currentWidth = textWidth(text, lineStart, i + 1, lineHeight);
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
        if (isAscii(ch)) {
            if (Character.isLetterOrDigit(ch)) {
                return lineHeight * 0.56;
            }
            return lineHeight * 0.45;
        }
        if (isCjk(ch)) {
            return lineHeight;
        }
        return lineHeight * 0.8;
    }

    private static boolean isAscii(char ch) {
        return ch < 128;
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

    private static int firstCharShapeId(Paragraph paragraph) {
        if (paragraph.getCharShape() == null || paragraph.getCharShape().getPositonShapeIdPairList().isEmpty()) {
            return DEFAULT_CHAR_SHAPE_ID;
        }
        return (int) paragraph.getCharShape().getPositonShapeIdPairList().getFirst().getShapeId();
    }

    private static int lineHeight(List<CharShape> charShapes, int charShapeId) {
        if (charShapeId < 0 || charShapeId >= charShapes.size()) {
            charShapeId = DEFAULT_CHAR_SHAPE_ID;
        }
        return Math.max(1000, charShapes.get(charShapeId).getBaseSize());
    }

    private static int lineAdvance(int lineHeight) {
        return (int) Math.round(lineHeight * DEFAULT_LINE_SPACING_RATIO);
    }

    private static String paragraphText(Paragraph paragraph) {
        try {
            return paragraph.getNormalString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PageMetrics pageMetrics(Section section) {
        for (Paragraph paragraph : section) {
            if (paragraph.getControlList() == null) {
                continue;
            }
            for (Control control : paragraph.getControlList()) {
                if (control instanceof ControlSectionDefine sectionDefine) {
                    PageDef pageDef = sectionDefine.getPageDef();
                    int bodyWidth = (int) (pageDef.getPaperWidth() - pageDef.getLeftMargin() - pageDef.getRightMargin());
                    int bodyHeight = (int) (pageDef.getPaperHeight() - pageDef.getTopMargin() - pageDef.getBottomMargin());
                    return new PageMetrics(
                            bodyWidth > 0 ? bodyWidth : DEFAULT_BODY_WIDTH,
                            bodyHeight > 0 ? bodyHeight : DEFAULT_BODY_HEIGHT
                    );
                }
            }
        }
        return new PageMetrics(DEFAULT_BODY_WIDTH, DEFAULT_BODY_HEIGHT);
    }

    private static int millimetersToHwp(double millimeters) {
        return (int) Math.round(millimeters * 72000.0 / 254.0);
    }

    private record PageMetrics(int bodyWidth, int bodyHeight) {
    }

    private record CodeCellStyles(int paraShapeId, int charShapeId) {
    }

    private record BulletListItem(String text, int depth) {
    }

    private record ImageMarkerData(String source, String altText, String title) {
        private String markdownFallback() {
            String safeAlt = altText == null ? "" : altText;
            return "![" + safeAlt + "](" + source + ")";
        }
    }

    private record ImageMarkerMatch(int start, int end, ImageMarkerData image) {
    }

    private record DownloadedImage(byte[] bytes, String extension, int width, int height) {
    }

    private record ImageSize(int width, int height) {
    }

    private record ImageDisplaySize(int width, int height) {
    }
}
