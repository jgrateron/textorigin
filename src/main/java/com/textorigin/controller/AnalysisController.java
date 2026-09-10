package com.textorigin.controller;

import com.textorigin.exception.AnalysisException;
import com.textorigin.exception.TextExtractionException;
import com.textorigin.exception.TextExtractionException.Reason;
import com.textorigin.model.AnalysisRequest;
import com.textorigin.model.Document;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.model.DocumentWarning;
import com.textorigin.model.SanitizedText;
import com.textorigin.model.Segment;
import com.textorigin.service.AnalysisStorageService;
import com.textorigin.service.BibliographyDetector;
import com.textorigin.service.DeepSeekAnalysisService;
import com.textorigin.service.InjectionDefenseService;
import com.textorigin.service.QuotaService;
import com.textorigin.service.TextExtractionService;
import com.textorigin.service.TextSegmentationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Flujo de análisis: recepción del documento, progreso y resultados.
 *
 * <p>El envío del formulario responde de inmediato con el fragmento de progreso; el análisis
 * real ocurre en segundo plano y la página consulta su estado por HTMX hasta que hay
 * resultados.</p>
 */
@Slf4j
@Controller
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final TextExtractionService extractionService;
    private final BibliographyDetector bibliographyDetector;
    private final InjectionDefenseService injectionDefense;
    private final TextSegmentationService segmentationService;
    private final AnalysisStorageService storageService;
    private final DeepSeekAnalysisService analysisService;
    private final QuotaService quotaService;

    /**
     * Recibe el documento y lanza su análisis.
     *
     * <p>La comprobación de cuota se hace aquí, antes de extraer el texto y de llamar a
     * DeepSeek, para no gastar tokens en vano. El consumo solo se registra cuando el análisis
     * termina con resultados: si algo falla, el usuario no pierde un análisis.</p>
     *
     * @param file    archivo subido (opcional)
     * @param text    texto pegado (opcional)
     * @param request petición en curso
     * @param model   modelo de la vista
     * @return el fragmento con el indicador de progreso
     */
    @PostMapping("/analyze")
    public String analyze(@RequestParam(value = "file", required = false) MultipartFile file,
                          @RequestParam(value = "text", required = false) String text,
                          HttpServletRequest request,
                          Model model) {

        quotaService.checkQuota(request);

        AnalysisRequest analysisRequest = AnalysisRequest.builder()
                .fileName(file != null ? file.getOriginalFilename() : null)
                .contentType(file != null ? file.getContentType() : null)
                .pastedText(text)
                .filePresent(file != null && !file.isEmpty())
                .fileSize(file != null ? file.getSize() : 0L)
                .build();

        boolean fromFile = analysisRequest.isFilePresent();
        analysisRequest.setSource(fromFile ? Document.Source.UPLOAD : Document.Source.PASTED);

        // Antes de construir el documento: la extracción ya ha descartado el texto oculto, la
        // bibliografía final se excluye aquí y, después, se neutralizan las instrucciones
        // dirigidas al modelo que hayan quedado en el texto. Todo lo descartado deja aviso.
        SanitizedText extracted = resolveText(file, text);
        SanitizedText sanitized = injectionDefense.neutralizeInstructions(
                bibliographyDetector.stripBibliography(extracted.text()));

        List<DocumentWarning> warnings = new ArrayList<>(extracted.warnings());
        warnings.addAll(sanitized.warnings());

        String fileName = fromFile ? file.getOriginalFilename() : "Texto pegado";
        String contentType = fromFile ? file.getContentType() : "text/plain";

        Document document = extractionService.createDocument(
                sanitized.text(), fileName, contentType, analysisRequest.getSource());

        List<Segment> segments = segmentationService.segment(document.getText());
        if (segments.isEmpty()) {
            throw new TextExtractionException(
                    "No se han podido identificar párrafos en el documento.",
                    Reason.TEXT_TOO_SHORT);
        }

        DocumentAnalysis analysis = storageService.save(
                storageService.createAnalysis(document, segments, warnings));
        log.info("Análisis solicitado: id={} origen={} -> {} segmentos, {} avisos de defensa",
                analysis.getId(), analysisRequest.describe(), segments.size(), warnings.size());

        // La IP se captura ahora: el consumo se registra desde el hilo de fondo, que ya no
        // tiene acceso a la petición HTTP.
        String clientIp = quotaService.getClientIp(request);

        analysisService.analyzeAsync(analysis, () -> quotaService.registerConsumption(clientIp));

        model.addAttribute("analysis", analysis);
        return "fragments/analysis-progress :: progress";
    }

    /**
     * Página de resultados de un análisis.
     *
     * @param id      identificador del análisis
     * @param request petición en curso
     * @param model   modelo de la vista
     * @return la plantilla {@code analysis}
     */
    @GetMapping("/{id}")
    public String results(@PathVariable String id, HttpServletRequest request, Model model) {
        DocumentAnalysis analysis = storageService.getRequired(id);
        model.addAttribute("analysis", analysis);
        model.addAttribute("quota", quotaService.getStatus(request));
        model.addAttribute("contactEmail", quotaService.getContactEmail());
        return "analysis";
    }

    /**
     * Estado del análisis, consultado periódicamente por HTMX.
     *
     * @param id      identificador del análisis
     * @param request petición en curso
     * @param model   modelo de la vista
     * @return el fragmento de progreso mientras se analiza, o el de resultados al terminar
     */
    @GetMapping("/{id}/status")
    public String status(@PathVariable String id, HttpServletRequest request, Model model) {
        DocumentAnalysis analysis = storageService.getRequired(id);
        model.addAttribute("analysis", analysis);
        model.addAttribute("quota", quotaService.getStatus(request));
        model.addAttribute("contactEmail", quotaService.getContactEmail());
        return analysis.isRunning()
                ? "fragments/analysis-progress :: progress"
                : "fragments/analysis-results :: results";
    }

    /**
     * Detalle de un segmento concreto, cargado al pulsar sobre un párrafo.
     *
     * @param id    identificador del análisis
     * @param index posición del segmento
     * @param model modelo de la vista
     * @return el fragmento con el detalle del segmento
     */
    @GetMapping("/{id}/segment/{index}")
    public String segment(@PathVariable String id, @PathVariable int index, Model model) {
        DocumentAnalysis analysis = storageService.getRequired(id);
        if (index < 0 || index >= analysis.getTotalCount()) {
            throw new AnalysisException("El segmento solicitado no existe en este análisis.");
        }
        model.addAttribute("segment", analysis.getSegments().get(index));
        model.addAttribute("analysisId", id);
        return "fragments/segment-detail :: detail";
    }

    /** Decide si el texto analizable viene de un archivo o del área de texto. */
    private SanitizedText resolveText(MultipartFile file, String text) {
        if (file != null && !file.isEmpty()) {
            return extractionService.extractTextWithFindings(file);
        }
        if (text != null && !text.isBlank()) {
            return extractionService.validatePastedTextWithFindings(text);
        }
        throw new TextExtractionException(
                "No has subido ningún archivo ni pegado ningún texto. Elige una de las dos opciones "
                        + "para lanzar el análisis.",
                Reason.EMPTY_INPUT);
    }
}
