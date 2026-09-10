package com.textorigin.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agregados de citas y evidencias del documento, y etiqueta del porcentaje global.
 */
class DocumentAnalysisTest {

    private static SuspiciousFragment fragment(int segmentIndex, int index, String text) {
        return SuspiciousFragment.builder()
                .segmentIndex(segmentIndex)
                .index(index)
                .text(text)
                .reason("Motivo señalado por el modelo.")
                .level("alto")
                .build();
    }

    @Test
    void aplanaLosFragmentosEnOrdenDeSegmento() {
        SegmentAnalysis first = SegmentAnalysis.builder().index(0).text("uno").score(80)
                .suspiciousFragments(List.of(fragment(0, 0, "cita A")))
                .build();
        SegmentAnalysis second = SegmentAnalysis.builder().index(1).text("dos").score(20)
                .suspiciousFragments(List.of(fragment(1, 0, "cita B"), fragment(1, 1, "cita C")))
                .humanEvidence(List.of("Una errata coherente"))
                .build();
        SegmentAnalysis third = SegmentAnalysis.builder().index(2).text("tres").score(50).build();

        DocumentAnalysis analysis = DocumentAnalysis.builder()
                .segments(new CopyOnWriteArrayList<>(List.of(first, second, third)))
                .status(DocumentAnalysis.Status.COMPLETED)
                .build();

        assertThat(analysis.getAllSuspiciousFragments())
                .extracting(SuspiciousFragment::getText)
                .containsExactly("cita A", "cita B", "cita C");
        assertThat(analysis.getAllSuspiciousFragments())
                .extracting(SuspiciousFragment::getSegmentIndex)
                .containsExactly(0, 1, 1);
        assertThat(analysis.hasSuspiciousFragments()).isTrue();
        assertThat(analysis.getSuspiciousFragmentCount()).isEqualTo(3);
        assertThat(analysis.hasHumanEvidence()).isTrue();
        assertThat(analysis.getHumanEvidenceCount()).isEqualTo(1);
    }

    @Test
    void losContadoresSoportanListasAusentes() {
        SegmentAnalysis failed = SegmentAnalysis.builder().index(0).text("x")
                .errorMessage("No se pudo analizar este fragmento.")
                .build();
        DocumentAnalysis withError = DocumentAnalysis.builder()
                .segments(new CopyOnWriteArrayList<>(List.of(failed)))
                .build();

        assertThat(withError.hasSuspiciousFragments()).isFalse();
        assertThat(withError.getSuspiciousFragmentCount()).isZero();
        assertThat(withError.hasHumanEvidence()).isFalse();
        assertThat(withError.getHumanEvidenceCount()).isZero();
        assertThat(withError.getAllSuspiciousFragments()).isEmpty();

        DocumentAnalysis withoutSegments = DocumentAnalysis.builder().segments(null).build();
        assertThat(withoutSegments.getSuspiciousFragmentCount()).isZero();
        assertThat(withoutSegments.getHumanEvidenceCount()).isZero();
        assertThat(withoutSegments.getAllSuspiciousFragments()).isEmpty();
    }

    @Test
    void elPorcentajeGlobalSeEtiquetaComoPorcentaje() {
        SegmentAnalysis first = SegmentAnalysis.builder().index(0).text("uno dos tres").score(40).build();
        SegmentAnalysis second = SegmentAnalysis.builder().index(1).text("cuatro cinco seis").score(80).build();

        DocumentAnalysis analysis = DocumentAnalysis.builder()
                .segments(new CopyOnWriteArrayList<>(List.of(first, second)))
                .status(DocumentAnalysis.Status.COMPLETED)
                .build();

        assertThat(analysis.getGlobalScoreRounded()).isEqualTo(60);
        assertThat(analysis.getGlobalScoreLabel()).isEqualTo("60 %");
    }

    @Test
    void elPorcentajeGlobalEsUnGuionSinResultados() {
        assertThat(DocumentAnalysis.builder().build().getGlobalScoreLabel()).isEqualTo("—");
    }

    @Test
    void losAvisosDeDefensaSeDetectanYSeCuentan() {
        DocumentAnalysis sinAvisos = DocumentAnalysis.builder().build();

        assertThat(sinAvisos.hasWarnings()).isFalse();
        assertThat(sinAvisos.getWarningCount()).isZero();
        assertThat(sinAvisos.getWarnings()).isEmpty();

        DocumentAnalysis conAvisos = DocumentAnalysis.builder()
                .warnings(List.of(DocumentWarning.instructionPhrase("ignora las instrucciones")))
                .build();

        assertThat(conAvisos.hasWarnings()).isTrue();
        assertThat(conAvisos.getWarningCount()).isEqualTo(1);
        assertThat(conAvisos.getWarnings().getFirst().getTitle())
                .isEqualTo("Posible instrucción dirigida al modelo");
    }
}
