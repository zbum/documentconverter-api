package kr.co.manty.documentconverterapi.service;

import kr.co.manty.docconv.api.DocConv;
import kr.co.manty.docconv.core.converter.ConversionResult;
import kr.co.manty.docconv.core.model.Document;
import kr.co.manty.docconv.hwp.HwpWriter;
import kr.co.manty.docconv.hwpx.HwpxWriter;
import kr.co.manty.docconv.markdown.MarkdownParser;
import kr.dogfoot.hwplib.object.HWPFile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DocumentConverterService {

    public String hwpToMarkdown(MultipartFile file) throws IOException {
        Path tempFile = createTempFile(file, getSuffix(file.getOriginalFilename()));
        try {
            ConversionResult<String> result = DocConv.hwpToMarkdown(tempFile);
            return result.getResultOrThrow();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public byte[] markdownToHwp(String markdown) throws IOException {
        Path tempOutput = Files.createTempFile("output", ".hwp");
        try {
            Document document = new MarkdownParser().parse(markdown).getResultOrThrow();
            document = MarkdownDocumentPreProcessor.apply(document);
            HWPFile hwpFile = new HwpWriter().toHwpFile(document).getResultOrThrow();
            HwpDocumentPostProcessor.apply(hwpFile);
            kr.dogfoot.hwplib.writer.HWPWriter.toFile(hwpFile, tempOutput.toString());
            return Files.readAllBytes(tempOutput);
        } catch (Exception e) {
            throw new IOException("Failed to convert markdown to HWP", e);
        } finally {
            Files.deleteIfExists(tempOutput);
        }
    }

    public byte[] markdownToHwpx(String markdown) throws IOException {
        Path tempOutput = Files.createTempFile("output", ".hwpx");
        try {
            Document document = new MarkdownParser().parse(markdown).getResultOrThrow();
            document = MarkdownDocumentPreProcessor.apply(document);
            ConversionResult<Path> result = new HwpxWriter().write(document, tempOutput);
            Path outputPath = result.getResultOrThrow();
            HwpxDocumentPostProcessor.apply(outputPath);
            return Files.readAllBytes(outputPath);
        } catch (Exception e) {
            throw new IOException("Failed to convert markdown to HWPX", e);
        } finally {
            Files.deleteIfExists(tempOutput);
        }
    }

    public byte[] hwpToHwpx(MultipartFile file) throws IOException {
        Path tempInput = createTempFile(file, ".hwp");
        Path tempOutput = Files.createTempFile("output", ".hwpx");
        try {
            ConversionResult<Path> result = DocConv.hwpToHwpx(tempInput, tempOutput);
            Path outputPath = result.getResultOrThrow();
            return Files.readAllBytes(outputPath);
        } finally {
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);
        }
    }

    public byte[] hwpxToHwp(MultipartFile file) throws IOException {
        Path tempInput = createTempFile(file, ".hwpx");
        Path tempOutput = Files.createTempFile("output", ".hwp");
        try {
            ConversionResult<Path> result = DocConv.hwpxToHwp(tempInput, tempOutput);
            Path outputPath = result.getResultOrThrow();
            return Files.readAllBytes(outputPath);
        } finally {
            Files.deleteIfExists(tempInput);
            Files.deleteIfExists(tempOutput);
        }
    }

    private Path createTempFile(MultipartFile file, String suffix) throws IOException {
        Path tempFile = Files.createTempFile("upload", suffix);
        file.transferTo(tempFile);
        return tempFile;
    }

    private String getSuffix(String filename) {
        if (filename == null) {
            return ".hwp";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1) {
            return ".hwp";
        }
        return filename.substring(lastDot);
    }
}
