package com.textorigin.controller;

import com.textorigin.exception.AnalysisException;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.service.AnalysisStorageService;
import com.textorigin.service.PdfReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.nio.charset.StandardCharsets;

/**
 * Descarga del informe PDF de un análisis.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ReportController {

    private final AnalysisStorageService storageService;
    private final PdfReportService pdfReportService;

    /**
     * Genera y devuelve el informe en PDF.
     *
     * @param id identificador del análisis
     * @return el PDF como descarga
     */
    @GetMapping("/report/{id}")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String id) {
        DocumentAnalysis analysis = storageService.getRequired(id);

        if (analysis.isRunning()) {
            throw new AnalysisException(
                    "El análisis todavía está en curso. Espera a que termine para descargar el informe.");
        }

        byte[] pdf = pdfReportService.generateReport(analysis);
        String fileName = pdfReportService.buildFileName(analysis);

        log.info("Descarga de informe solicitada: id={} archivo={} bytes={}", id, fileName, pdf.length);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(pdf);
    }
}
