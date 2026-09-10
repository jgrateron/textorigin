package com.textorigin.service;

import com.textorigin.model.Document;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.model.DocumentWarning;
import com.textorigin.model.SegmentAnalysis;
import com.textorigin.model.SuspiciousFragment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generación del informe PDF: porcentaje, secciones de citas y evidencias, estados vacíos
 * y caracteres que la fuente Helvetica no puede representar.
 */
class PdfReportServiceTest {

    private static PdfReportService service() {
        PdfReportService service = new PdfReportService();
        ReflectionTestUtils.setField(service, "contactEmail", "docente@ejemplo.org");
        ReflectionTestUtils.setField(service, "model", "deepseek-flash");
        return service;
    }

    private static DocumentAnalysis analysisWith(SegmentAnalysis... segments) {
        return DocumentAnalysis.builder()
                .id("11111111-2222-3333-4444-555555555555")
                .document(Document.builder()
                        .fileName("ensayo.pdf")
                        .contentType("application/pdf")
                        .text("texto")
                        .wordCount(320)
                        .build())
                .status(DocumentAnalysis.Status.COMPLETED)
                .createdAt(LocalDateTime.of(2026, 9, 10, 12, 0))
                .completedAt(LocalDateTime.of(2026, 9, 10, 12, 1))
                .durationMs(61_000)
                .segments(new CopyOnWriteArrayList<>(List.of(segments)))
                .build();
    }

    private static SegmentAnalysis completedSegment(int index, int score, String text) {
        return SegmentAnalysis.builder()
                .index(index)
                .text(text)
                .score(score)
                .explanation("Explicación del segmento.")
                .build();
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void elInformeMuestraElPorcentajeYLasCitas() throws IOException {
        SegmentAnalysis withFindings = SegmentAnalysis.builder()
                .index(0)
                .text("Un párrafo con estilo muy predecible.")
                .score(62)
                .explanation("Patrones típicos de IA.")
                .suspiciousFragments(List.of(SuspiciousFragment.builder()
                        .index(0)
                        .segmentIndex(0)
                        .text("En el mundo actual, es importante destacar")
                        .reason("Frase hecha muy habitual en textos generados.")
                        .level("alto")
                        .build()))
                .humanEvidence(List.of("Una errata coherente en la segunda frase"))
                .build();

        byte[] pdf = service().generateReport(analysisWith(withFindings, completedSegment(1, 35, "Otro párrafo.")));

        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        String text = extractText(pdf);
        assertThat(text).contains("Probabilidad estimada de IA");
        // El modelo configurado aparece en el resumen (el ajuste de línea puede partirlo).
        assertThat(text).contains("deepseek-flash");
        assertThat(text).contains("62 %");
        assertThat(text).contains("4. Fragmentos sospechosos citados");
        assertThat(text).contains("En el mundo actual, es importante destacar");
        assertThat(text).contains("Sospecha alta");
        assertThat(text).contains("5. Evidencias de mano humana");
        assertThat(text).contains("Una errata coherente en la segunda frase");
        assertThat(text).contains("6. Advertencias y limitaciones metodológicas");
        assertThat(text).doesNotContain("/100");
    }

    @Test
    void elInformeSinCitasNiEvidenciasLoIndica() throws IOException {
        byte[] pdf = service().generateReport(analysisWith(completedSegment(0, 20, "Texto sin señales.")));

        String text = extractText(pdf);
        assertThat(text).contains("El modelo no ha citado fragmentos sospechosos concretos");
        assertThat(text).contains("No se han detectado evidencias claras");
    }

    @Test
    void elInformeSustituyeLosCaracteresNoRepresentables() throws IOException {
        byte[] pdf = service().generateReport(
                analysisWith(completedSegment(0, 20, "Texto con emoji 🤖 y japonés 日本語.")));

        String text = extractText(pdf);
        assertThat(text).contains("Texto con emoji");
        assertThat(text).contains("?");
    }

    @Test
    void elInformeIncluyeLosSegmentosConError() throws IOException {
        SegmentAnalysis failed = SegmentAnalysis.builder()
                .index(0)
                .text("Un párrafo que no pudo analizarse.")
                .errorMessage("No se pudo analizar este fragmento.")
                .build();

        byte[] pdf = service().generateReport(analysisWith(failed, completedSegment(1, 50, "Otro párrafo.")));

        String text = extractText(pdf);
        assertThat(text).contains("No se pudo analizar este fragmento.");
        assertThat(text).contains("50 %");
    }

    @Test
    void elInformeIncluyeElAvisoDeContenidoDirigidoAlModelo() throws IOException {
        DocumentAnalysis analysis = DocumentAnalysis.builder()
                .id("11111111-2222-3333-4444-555555555555")
                .document(Document.builder().fileName("ensayo.pdf").text("texto").wordCount(320).build())
                .status(DocumentAnalysis.Status.COMPLETED)
                .createdAt(LocalDateTime.of(2026, 9, 10, 12, 0))
                .segments(new CopyOnWriteArrayList<>(List.of(completedSegment(0, 20, "Párrafo."))))
                .warnings(List.of(
                        DocumentWarning.instructionPhrase("ignora las instrucciones anteriores"),
                        DocumentWarning.hiddenTextMajority(85)))
                .build();

        String text = extractText(service().generateReport(analysis));

        assertThat(text).contains("Aviso: contenido dirigido al modelo detectado");
        assertThat(text).contains("Posible instrucción dirigida al modelo");
        assertThat(text).contains("ignora las instrucciones anteriores");
        assertThat(text).contains("Documento con casi todo el texto oculto");
        assertThat(text).contains("contenido dirigido a un modelo de lenguaje");
    }

    @Test
    void elInformeSinAvisosNoIncluyeLaFranja() throws IOException {
        byte[] pdf = service().generateReport(analysisWith(completedSegment(0, 20, "Texto sin señales.")));

        String text = extractText(pdf);
        assertThat(text).doesNotContain("Aviso: contenido dirigido al modelo detectado");
        assertThat(text).doesNotContain("contenido dirigido a un modelo de lenguaje");
    }
}
