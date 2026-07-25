package kr.co.manty.documentconverterapi.service;

import kr.co.manty.docconv.core.model.Block;
import kr.co.manty.docconv.core.model.Document;
import kr.co.manty.docconv.core.model.Inline;
import kr.co.manty.docconv.core.model.TableModel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

final class MarkdownDocumentPreProcessor {

    private static final int CODE_TABLE_WRAP_COLUMNS = 88;
    private static final String CODE_LINE_BREAK_MARKER = "\u241E";
    static final String MARKDOWN_IMAGE_MARKER_PREFIX = "\u241FIMG:";
    static final String MARKDOWN_IMAGE_MARKER_SEPARATOR = ":";
    static final String MARKDOWN_IMAGE_MARKER_SUFFIX = "\u241F";

    private MarkdownDocumentPreProcessor() {
    }

    static Document apply(Document document) {
        Document processed = new Document(transformBlocks(document.getBlocks()));
        processed.setMetadata(document.getMetadata());
        return processed;
    }

    private static List<Block> transformBlocks(List<Block> blocks) {
        return blocks.stream()
                .map(MarkdownDocumentPreProcessor::transformBlock)
                .toList();
    }

    private static Block transformBlock(Block block) {
        return switch (block) {
            case Block.Paragraph paragraph -> new Block.Paragraph(transformInlines(paragraph.inlines()));
            case Block.Heading heading -> new Block.Heading(heading.level(), transformInlines(heading.inlines()));
            case Block.CodeBlock codeBlock -> codeBlockTable(codeBlock);
            case Block.BlockQuote blockQuote -> new Block.BlockQuote(transformBlocks(blockQuote.blocks()));
            case Block.ListBlock listBlock -> transformListBlock(listBlock);
            default -> block;
        };
    }

    private static Block.ListBlock transformListBlock(Block.ListBlock listBlock) {
        List<Block.ListItem> items = listBlock.items().stream()
                .map(item -> new Block.ListItem(transformBlocks(item.blocks())))
                .toList();
        return new Block.ListBlock(listBlock.ordered(), listBlock.startNumber(), items);
    }

    private static List<Inline> transformInlines(List<Inline> inlines) {
        return inlines.stream()
                .map(MarkdownDocumentPreProcessor::transformInline)
                .toList();
    }

    private static Inline transformInline(Inline inline) {
        return switch (inline) {
            case Inline.Bold bold -> new Inline.Bold(transformInlines(bold.inlines()));
            case Inline.Italic italic -> new Inline.Italic(transformInlines(italic.inlines()));
            case Inline.BoldItalic boldItalic -> new Inline.BoldItalic(transformInlines(boldItalic.inlines()));
            case Inline.Link link -> new Inline.Link(link.destination(), link.title(), transformInlines(link.inlines()));
            case Inline.Image image -> new Inline.Text(imageMarker(image));
            default -> inline;
        };
    }

    private static String imageMarker(Inline.Image image) {
        return MARKDOWN_IMAGE_MARKER_PREFIX
                + encodeMarkerPart(image.source())
                + MARKDOWN_IMAGE_MARKER_SEPARATOR
                + encodeMarkerPart(image.altText())
                + MARKDOWN_IMAGE_MARKER_SEPARATOR
                + encodeMarkerPart(image.title())
                + MARKDOWN_IMAGE_MARKER_SUFFIX;
    }

    private static String encodeMarkerPart(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Block.Table codeBlockTable(Block.CodeBlock codeBlock) {
        String content = codeBlock.hasLanguage()
                ? "[" + codeBlock.language() + "]" + CODE_LINE_BREAK_MARKER + wrapCode(codeBlock.code())
                : wrapCode(codeBlock.code());
        TableModel model = TableModel.builder()
                .addRow(content)
                .build();
        return new Block.Table(model);
    }

    private static String wrapCode(String code) {
        String[] lines = stripTrailingLineBreaks(code).split("\\R", -1);
        StringBuilder wrapped = new StringBuilder(code.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                wrapped.append(CODE_LINE_BREAK_MARKER);
            }
            appendWrappedLine(wrapped, lines[i]);
        }
        return wrapped.toString();
    }

    private static String stripTrailingLineBreaks(String code) {
        int end = code.length();
        while (end > 0) {
            char ch = code.charAt(end - 1);
            if (ch != '\n' && ch != '\r') {
                break;
            }
            end--;
        }
        return code.substring(0, end);
    }

    private static void appendWrappedLine(StringBuilder target, String line) {
        int start = 0;
        boolean firstSegment = true;
        while (displayWidth(line, start, line.length()) > CODE_TABLE_WRAP_COLUMNS) {
            int end = fitEnd(line, start);
            int breakPosition = breakPosition(line, start, end);
            if (breakPosition <= start) {
                breakPosition = end;
            }

            if (!firstSegment) {
                target.append(CODE_LINE_BREAK_MARKER);
            }
            target.append(line, start, breakPosition);
            start = skipBreakCharacters(line, breakPosition);
            firstSegment = false;
        }

        if (!firstSegment) {
            target.append(CODE_LINE_BREAK_MARKER);
        }
        target.append(line, start, line.length());
    }

    private static int fitEnd(String line, int start) {
        int width = 0;
        for (int i = start; i < line.length(); i++) {
            width += characterWidth(line.charAt(i));
            if (width > CODE_TABLE_WRAP_COLUMNS) {
                return Math.max(start + 1, i);
            }
        }
        return line.length();
    }

    private static int breakPosition(String line, int start, int end) {
        for (int i = end; i > start; i--) {
            if (isPreferredBreakCharacter(line.charAt(i - 1))) {
                return i;
            }
        }
        return end;
    }

    private static int skipBreakCharacters(String line, int position) {
        int current = position;
        while (current < line.length() && Character.isWhitespace(line.charAt(current))) {
            current++;
        }
        return current;
    }

    private static int displayWidth(String line, int start, int end) {
        int width = 0;
        for (int i = start; i < end; i++) {
            width += characterWidth(line.charAt(i));
        }
        return width;
    }

    private static int characterWidth(char ch) {
        if (ch == '\t') {
            return 4;
        }
        return isWideCharacter(ch) ? 2 : 1;
    }

    private static boolean isPreferredBreakCharacter(char ch) {
        return Character.isWhitespace(ch)
                || ch == ','
                || ch == ';'
                || ch == ':'
                || ch == '/'
                || ch == '\\'
                || ch == '-'
                || ch == '_'
                || ch == '.'
                || ch == ')'
                || ch == ']'
                || ch == '}';
    }

    private static boolean isWideCharacter(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
