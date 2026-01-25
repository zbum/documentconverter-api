package kr.co.manty.documentconverterapi.controller;

import kr.co.manty.documentconverterapi.service.DocumentConverterService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/convert")
public class DocumentConverterController {

    private final DocumentConverterService converterService;

    public DocumentConverterController(DocumentConverterService converterService) {
        this.converterService = converterService;
    }

    @PostMapping("/hwp-to-markdown")
    public ResponseEntity<String> hwpToMarkdown(@RequestParam("file") MultipartFile file) throws IOException {
        String markdown = converterService.hwpToMarkdown(file);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(markdown);
    }

    @PostMapping("/markdown-to-hwp")
    public ResponseEntity<byte[]> markdownToHwp(@RequestBody String markdown) throws IOException {
        byte[] hwpBytes = converterService.markdownToHwp(markdown);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.hwp\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(hwpBytes);
    }

    @PostMapping("/markdown-to-hwpx")
    public ResponseEntity<byte[]> markdownToHwpx(@RequestBody String markdown) throws IOException {
        byte[] hwpxBytes = converterService.markdownToHwpx(markdown);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.hwpx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(hwpxBytes);
    }

    @PostMapping("/hwp-to-hwpx")
    public ResponseEntity<byte[]> hwpToHwpx(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] hwpxBytes = converterService.hwpToHwpx(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.hwpx\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(hwpxBytes);
    }

    @PostMapping("/hwpx-to-hwp")
    public ResponseEntity<byte[]> hwpxToHwp(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] hwpBytes = converterService.hwpxToHwp(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"document.hwp\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(hwpBytes);
    }
}
