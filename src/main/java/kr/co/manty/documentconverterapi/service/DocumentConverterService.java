package kr.co.manty.documentconverterapi.service;

import kr.co.manty.docconv.api.DocConv;
import kr.co.manty.docconv.core.converter.ConversionResult;
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
            ConversionResult<Path> result = DocConv.markdownToHwp(markdown, tempOutput);
            Path outputPath = result.getResultOrThrow();
            return Files.readAllBytes(outputPath);
        } finally {
            Files.deleteIfExists(tempOutput);
        }
    }

    public byte[] markdownToHwpx(String markdown) throws IOException {
        Path tempOutput = Files.createTempFile("output", ".hwpx");
        try {
            ConversionResult<Path> result = DocConv.markdownToHwpx(markdown, tempOutput);
            Path outputPath = result.getResultOrThrow();
            return Files.readAllBytes(outputPath);
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
