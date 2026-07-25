package kr.co.manty.documentconverterapi.service;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.gso.textbox.TextVerticalAlignment;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;
import kr.dogfoot.hwplib.object.docinfo.parashape.ParaHeadShape;
import kr.dogfoot.hwplib.reader.HWPReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentConverterServiceTest {

    private static final String HP_NS = "http://www.hancom.co.kr/hwpml/2011/paragraph";

    private final DocumentConverterService service = new DocumentConverterService();

    @Test
    void markdownToHwpAppliesDistinctHeadingStyles() throws Exception {
        byte[] hwpBytes = service.markdownToHwp("""
                # 제목 1
                ## 제목 2
                ### 제목 3
                본문
                """);

        Path hwpPath = Files.createTempFile("heading-style", ".hwp");
        try {
            Files.write(hwpPath, hwpBytes);
            HWPFile hwpFile = HWPReader.fromFile(hwpPath.toFile());

            List<Paragraph> paragraphs = Arrays.stream(hwpFile.getBodyText().getSectionList().getFirst().getParagraphs())
                    .filter(paragraph -> !normalString(paragraph).isBlank())
                    .toList();
            assertThat(paragraphs).hasSizeGreaterThanOrEqualTo(4);
            assertThat(charShapeId(paragraphs.get(0))).isEqualTo(0);
            assertThat(charShapeId(paragraphs.get(1))).isEqualTo(1);
            assertThat(charShapeId(paragraphs.get(2))).isEqualTo(2);
            assertThat(charShapeId(paragraphs.get(3))).isEqualTo(5);

            List<CharShape> charShapes = hwpFile.getDocInfo().getCharShapeList();
            assertThat(charShapes.get(0).getBaseSize()).isGreaterThan(charShapes.get(1).getBaseSize());
            assertThat(charShapes.get(1).getBaseSize()).isGreaterThan(charShapes.get(2).getBaseSize());
            assertThat(charShapes.get(2).getBaseSize()).isGreaterThan(charShapes.get(5).getBaseSize());
            assertThat(charShapes.get(0).getProperty().isBold()).isTrue();
            assertThat(charShapes.get(1).getProperty().isBold()).isTrue();
            assertThat(charShapes.get(2).getProperty().isBold()).isTrue();
            assertThat(paragraphs).allSatisfy(paragraph -> {
                assertThat(paragraph.getHeader().getDivideSort().getValue()).isZero();
                assertThat(paragraph.getLineSeg()).isNotNull();
                assertThat(paragraph.getLineSeg().getLineSegItemList()).isNotEmpty();
                assertThat(paragraph.getHeader().getLineAlignCount())
                        .isEqualTo(paragraph.getLineSeg().getLineSegItemList().size());
            });
        } finally {
            Files.deleteIfExists(hwpPath);
        }
    }

    @Test
    void markdownToHwpRendersCodeBlockAsSingleCellTable() throws Exception {
        String longCodeLine = "image: registry.example.com/team/platform/really-long-container-image-name-with-a-long-tag"
                + "-and-digest-sha256-0123456789abcdef0123456789abcdef";
        byte[] hwpBytes = service.markdownToHwp("""
                본문

                ```yaml
                %s
                ```
                """.formatted(longCodeLine));

        Path hwpPath = Files.createTempFile("codeblock-table", ".hwp");
        try {
            Files.write(hwpPath, hwpBytes);
            HWPFile hwpFile = HWPReader.fromFile(hwpPath.toFile());
            ControlTable table = firstControlTable(hwpFile);
            Cell cell = table.getRowList().getFirst().getCellList().getFirst();

            assertThat(hwpFile.getBodyText().getSectionList().getFirst().getParagraphs())
                    .anySatisfy(paragraph -> assertThat(controlClassNames(paragraph)).contains("ControlTable"));
            assertThat(table.getHeader().getWidth()).isGreaterThanOrEqualTo(42000);
            assertThat(cell.getListHeader().getWidth()).isGreaterThanOrEqualTo(42000);
            assertThat(cell.getListHeader().getTextWidth()).isGreaterThan(40000);
            assertThat(cell.getListHeader().getProperty().getTextVerticalAlignment()).isEqualTo(TextVerticalAlignment.Top);
            assertThat(cell.getParagraphList().getParagraphCount()).isGreaterThan(2);
            assertThat(codeCellAlignments(hwpFile, cell)).containsOnly(Alignment.Left);
            assertThat(codeCellBaseSizes(hwpFile, cell)).containsOnly(900);
            assertThat(lastCellParagraphText(cell)).isNotBlank();
            assertThat(cellText(cell))
                    .contains("[yaml]")
                    .contains("\n")
                    .doesNotContain(longCodeLine);
            assertThat(cell.getParagraphList().getParagraph(1).getLineSeg().getLineSegItemList().getFirst().getSegmentWidth())
                    .isGreaterThan(40000);
            assertLineVerticalPositionsIncrease(cellLineVerticalPositions(cell));
        } finally {
            Files.deleteIfExists(hwpPath);
        }
    }

    @Test
    void markdownToHwpConvertsAsteriskListsToBullets() throws Exception {
        byte[] hwpBytes = service.markdownToHwp("""
                * 첫 항목
                * 둘째 항목
                """);

        Path hwpPath = Files.createTempFile("bullet-list", ".hwp");
        try {
            Files.write(hwpPath, hwpBytes);
            HWPFile hwpFile = HWPReader.fromFile(hwpPath.toFile());

            List<Paragraph> listParagraphs = Arrays.stream(hwpFile.getBodyText().getSectionList().getFirst().getParagraphs())
                    .filter(paragraph -> normalString(paragraph).equals("첫 항목") || normalString(paragraph).equals("둘째 항목"))
                    .toList();
            assertThat(listParagraphs).hasSize(2);
            assertThat(listParagraphs).allSatisfy(paragraph -> {
                assertThat(normalString(paragraph)).doesNotStartWith("*");
                assertThat(hwpFile.getDocInfo().getParaShapeList()
                        .get(paragraph.getHeader().getParaShapeId())
                        .getProperty1()
                        .getParaHeadShape()).isEqualTo(ParaHeadShape.Bullet);
            });
            assertThat(hwpFile.getDocInfo().getBulletList()).isNotEmpty();
        } finally {
            Files.deleteIfExists(hwpPath);
        }
    }

    @Test
    void markdownToHwpEmbedsMarkdownImages() throws Exception {
        Path imagePath = Files.createTempFile("markdown-image", ".png");
        Path hwpPath = Files.createTempFile("markdown-image", ".hwp");
        try {
            BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(image, "png", imagePath.toFile());

            byte[] hwpBytes = service.markdownToHwp("""
                    본문

                    앞 ![샘플](%s) 뒤
                    """.formatted(imagePath.toUri()));
            Files.write(hwpPath, hwpBytes);
            HWPFile hwpFile = HWPReader.fromFile(hwpPath.toFile());
            ControlPicture picture = firstControlPicture(hwpFile);

            assertThat(hwpFile.getDocInfo().getBinDataList()).isNotEmpty();
            assertThat(hwpFile.getBinData().getEmbeddedBinaryDataList()).isNotEmpty();
            assertThat(picture.getHeader().getWidth()).isPositive();
            assertThat(picture.getHeader().getHeight()).isPositive();
            assertThat(picture.getShapeComponentPicture().getPictureInfo().getBinItemID()).isPositive();
            assertThat(allParagraphText(hwpFile))
                    .contains("앞 ")
                    .contains(" 뒤")
                    .doesNotContain(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX)
                    .doesNotContain("[샘플]");
        } finally {
            Files.deleteIfExists(hwpPath);
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    void markdownToHwpxUsesStyledMarkdownPipeline() throws Exception {
        Path imagePath = Files.createTempFile("markdown-hwpx-image", ".png");
        try {
            BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(image, "png", imagePath.toFile());

            byte[] hwpxBytes = service.markdownToHwpx("""
                    # 제목

                    * 첫 항목
                    * 둘째 항목

                    ```go
                    func main() {
                        println("hello")
                    }
                    ```

                    ![샘플](%s)
                    """.formatted(imagePath.toUri()));
            String sectionXml = zipEntryText(hwpxBytes, "Contents/section0.xml");
            String headerXml = zipEntryText(hwpxBytes, "Contents/header.xml");
            String contentHpf = zipEntryText(hwpxBytes, "Contents/content.hpf");

            assertWellFormedXml(sectionXml);
            assertWellFormedXml(headerXml);
            assertWellFormedXml(contentHpf);
            assertEveryHwpxParagraphHasLineSegments(sectionXml);
            assertThat(sectionXml)
                    .contains("<hp:tbl")
                    .contains("<hp:lineBreak/>")
                    .doesNotContain("\u241E");
            assertHwpxCodeBlockTableIsStyled(sectionXml, headerXml);
            assertThat(headerXml)
                    .contains("<hh:bullets")
                    .contains("type=\"BULLET\"");
            assertThat(sectionXml)
                    .contains("첫 항목")
                    .contains("둘째 항목")
                    .doesNotContain("> - 첫 항목")
                    .doesNotContain("> - 둘째 항목")
                    .doesNotContain("> * 첫 항목")
                    .doesNotContain("> * 둘째 항목");
            assertThat(sectionXml)
                    .contains("<hp:pic")
                    .contains("binaryItemIDRef=\"image1\"")
                    .doesNotContain(MarkdownDocumentPreProcessor.MARKDOWN_IMAGE_MARKER_PREFIX)
                    .doesNotContain("[샘플]");
            assertThat(contentHpf)
                    .contains("id=\"image1\"")
                    .contains("href=\"BinData/image1.png\"")
                    .contains("media-type=\"image/png\"");
            assertThat(zipHasEntry(hwpxBytes, "BinData/image1.png")).isTrue();
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    private long charShapeId(Paragraph paragraph) {
        return paragraph.getCharShape().getPositonShapeIdPairList().getFirst().getShapeId();
    }

    private String normalString(Paragraph paragraph) {
        try {
            return paragraph.getNormalString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> controlClassNames(Paragraph paragraph) {
        if (paragraph.getControlList() == null) {
            return List.of();
        }
        return paragraph.getControlList().stream()
                .map(Control::getClass)
                .map(Class::getSimpleName)
                .toList();
    }

    private ControlTable firstControlTable(HWPFile hwpFile) {
        for (Paragraph paragraph : hwpFile.getBodyText().getSectionList().getFirst()) {
            if (paragraph.getControlList() == null) {
                continue;
            }
            for (Control control : paragraph.getControlList()) {
                if (control instanceof ControlTable table) {
                    return table;
                }
            }
        }
        throw new AssertionError("ControlTable not found");
    }

    private ControlPicture firstControlPicture(HWPFile hwpFile) {
        for (Paragraph paragraph : hwpFile.getBodyText().getSectionList().getFirst()) {
            if (paragraph.getControlList() == null) {
                continue;
            }
            for (Control control : paragraph.getControlList()) {
                if (control instanceof ControlPicture picture) {
                    return picture;
                }
            }
        }
        throw new AssertionError("ControlPicture not found");
    }

    private String allParagraphText(HWPFile hwpFile) {
        StringBuilder text = new StringBuilder();
        for (Paragraph paragraph : hwpFile.getBodyText().getSectionList().getFirst()) {
            text.append(normalString(paragraph));
        }
        return text.toString();
    }

    private String zipEntryText(byte[] zipBytes, String entryName) throws IOException {
        return new String(zipEntryBytes(zipBytes, entryName), StandardCharsets.UTF_8);
    }

    private void assertWellFormedXml(String xml) throws Exception {
        parseXml(xml);
    }

    private void assertEveryHwpxParagraphHasLineSegments(String xml) throws Exception {
        Document document = parseXml(xml);
        NodeList paragraphs = document.getElementsByTagNameNS(HP_NS, "p");
        assertThat(paragraphs.getLength()).isPositive();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            Element lineSegArray = directChild(paragraph, "linesegarray");
            assertThat(lineSegArray)
                    .as("section0 paragraph %s has linesegarray", i)
                    .isNotNull();
            assertThat(directChild(lineSegArray, "lineseg"))
                    .as("section0 paragraph %s has at least one lineseg", i)
                    .isNotNull();
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && HP_NS.equals(child.getNamespaceURI())
                    && localName.equals(child.getLocalName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private void assertHwpxCodeBlockTableIsStyled(String sectionXml, String headerXml) {
        String codeBorderFillId = codeBorderFillId(headerXml);
        String tableXml = firstMatch(sectionXml, "<hp:tbl\\b.*?</hp:tbl>");

        assertThat(tableXml)
                .contains("borderFillIDRef=\"" + codeBorderFillId + "\"")
                .doesNotContain("\u241E");
        assertThat(headerXml)
                .contains("id=\"" + codeBorderFillId + "\"")
                .contains("color=\"#D0D7DE\"")
                .contains("faceColor=\"#F6F8FA\"");
        assertThat(longAttribute(tableXml, "hp:sz", "height")).isGreaterThan(6500L);
        assertThat(longAttribute(tableXml, "hp:cellSz", "height")).isGreaterThan(6500L);
        assertThat(longAttribute(tableXml, "hp:cellMargin", "left")).isGreaterThan(500L);
        assertThat(longAttribute(tableXml, "hp:cellMargin", "right")).isGreaterThan(500L);
        assertThat(longAttribute(tableXml, "hp:cellMargin", "top")).isGreaterThan(500L);
        assertThat(longAttribute(tableXml, "hp:cellMargin", "bottom")).isGreaterThan(500L);
        assertThat(countMatches(tableXml, "<hp:lineseg\\b")).isGreaterThanOrEqualTo(4);
    }

    private String codeBorderFillId(String headerXml) {
        Matcher matcher = Pattern.compile(
                "<hh:borderFill\\s+id=\"(\\d+)\"[^>]*>(?:(?!</hh:borderFill>).)*faceColor=\"#F6F8FA\"(?:(?!</hh:borderFill>).)*</hh:borderFill>",
                Pattern.DOTALL
        ).matcher(headerXml);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String firstMatch(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private long longAttribute(String xml, String elementName, String attributeName) {
        Matcher matcher = Pattern.compile("<\\Q" + elementName + "\\E\\b[^>]*\\b\\Q" + attributeName + "\\E=\"(\\d+)\"")
                .matcher(xml);
        assertThat(matcher.find()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private long countMatches(String text, String regex) {
        return Pattern.compile(regex).matcher(text).results().count();
    }

    private boolean zipHasEntry(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private byte[] zipEntryBytes(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return zis.readAllBytes();
                }
            }
        }
        throw new AssertionError("ZIP entry not found: " + entryName);
    }

    private String cellText(Cell cell) {
        try {
            return cell.getParagraphList().getNormalString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Integer> cellLineVerticalPositions(Cell cell) {
        List<Integer> verticalPositions = new ArrayList<>();
        for (Paragraph paragraph : cell.getParagraphList()) {
            paragraph.getLineSeg().getLineSegItemList()
                    .forEach(item -> verticalPositions.add(item.getLineVerticalPosition()));
        }
        return verticalPositions;
    }

    private List<Alignment> codeCellAlignments(HWPFile hwpFile, Cell cell) {
        List<Alignment> alignments = new ArrayList<>();
        for (Paragraph paragraph : cell.getParagraphList()) {
            int paraShapeId = paragraph.getHeader().getParaShapeId();
            alignments.add(hwpFile.getDocInfo().getParaShapeList().get(paraShapeId).getProperty1().getAlignment());
        }
        return alignments;
    }

    private List<Integer> codeCellBaseSizes(HWPFile hwpFile, Cell cell) {
        List<Integer> baseSizes = new ArrayList<>();
        for (Paragraph paragraph : cell.getParagraphList()) {
            int charShapeId = (int) paragraph.getCharShape().getPositonShapeIdPairList().getFirst().getShapeId();
            baseSizes.add(hwpFile.getDocInfo().getCharShapeList().get(charShapeId).getBaseSize());
        }
        return baseSizes;
    }

    private String lastCellParagraphText(Cell cell) {
        Paragraph paragraph = cell.getParagraphList().getParagraph(cell.getParagraphList().getParagraphCount() - 1);
        return normalString(paragraph);
    }

    private void assertLineVerticalPositionsIncrease(List<Integer> verticalPositions) {
        assertThat(verticalPositions).hasSizeGreaterThan(2);
        for (int i = 1; i < verticalPositions.size(); i++) {
            assertThat(verticalPositions.get(i)).isGreaterThan(verticalPositions.get(i - 1));
        }
    }
}
