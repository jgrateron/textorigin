package com.textorigin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.textorigin.model.SegmentAnalysis;
import com.textorigin.model.SuspiciousFragment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalización de la respuesta del modelo: el contrato completo, los casos torcidos que
 * devuelve un modelo de lenguaje y la compatibilidad con respuestas sin los campos nuevos.
 */
class SegmentResultDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CONTRACT_JSON = """
            {
              "score": 62,
              "perplexity_indicator": "bajo",
              "burstiness_indicator": "medio",
              "indicators": ["Conectores repetitivos", "conectores repetitivos", "Estructura uniforme"],
              "suspicious_fragments": [
                {
                  "text": "\\"En el mundo actual, es importante destacar que\\"",
                  "reason": "Frase hecha muy típica de IA.",
                  "level": "alto"
                },
                {
                  "text": "En resumen, el tema es complejo",
                  "reason": "Cierre predecible.",
                  "level": "medio"
                }
              ],
              "human_evidence": ["Una errata coherente en el segundo párrafo"],
              "explanation": "El segmento muestra patrones habituales de IA.",
              "false_positive_warning": null
            }
            """;

    @Test
    void normalizaElContratoCompleto() throws Exception {
        SegmentResultDto result = objectMapper.readValue(CONTRACT_JSON, SegmentResultDto.class);

        assertThat(result.normalizedScore()).isEqualTo(62);
        assertThat(result.normalizedPerplexity()).isEqualTo("bajo");
        assertThat(result.normalizedBurstiness()).isEqualTo("medio");
        assertThat(result.normalizedIndicators())
                .containsExactly("Conectores repetitivos", "Estructura uniforme");

        List<SuspiciousFragment> fragments = result.normalizedSuspiciousFragments(2);
        assertThat(fragments).hasSize(2);

        SuspiciousFragment first = fragments.get(0);
        assertThat(first.getSegmentIndex()).isEqualTo(2);
        assertThat(first.getIndex()).isZero();
        assertThat(first.getText()).isEqualTo("En el mundo actual, es importante destacar que");
        assertThat(first.getReason()).isEqualTo("Frase hecha muy típica de IA.");
        assertThat(first.getLevelLabel()).isEqualTo("Sospecha alta");
        assertThat(first.getLevelCssClass()).isEqualTo("fragment-level--alto");
        assertThat(first.getSegmentLabel()).isEqualTo("Segmento 3");
        assertThat(fragments.get(1).getIndex()).isEqualTo(1);

        assertThat(result.normalizedHumanEvidence())
                .containsExactly("Una errata coherente en el segundo párrafo");

        SegmentAnalysis analysis = result.toSegmentAnalysis(2, "texto del segmento", 1_200L);
        assertThat(analysis.getSuspiciousFragments()).hasSize(2);
        assertThat(analysis.hasSuspiciousFragments()).isTrue();
        assertThat(analysis.getHumanEvidence()).hasSize(1);
        assertThat(analysis.getScoreLabel()).isEqualTo("62 %");
    }

    @Test
    void descartaFragmentosSinCitaYValidaElNivel() throws Exception {
        String json = """
                {
                  "score": 50,
                  "suspicious_fragments": [
                    { "text": "   ", "reason": "sin cita", "level": "alto" },
                    { "text": "null", "reason": "tampoco hay cita", "level": "medio" },
                    { "text": "Cita válida", "reason": "   ", "level": null },
                    { "text": "Otra cita válida", "level": "MUY_ALTO" }
                  ]
                }
                """;

        List<SuspiciousFragment> fragments = objectMapper.readValue(json, SegmentResultDto.class)
                .normalizedSuspiciousFragments(0);

        assertThat(fragments).hasSize(2);
        assertThat(fragments.get(0).getText()).isEqualTo("Cita válida");
        assertThat(fragments.get(0).getReason()).isEqualTo(SuspiciousFragmentDto.DEFAULT_REASON);
        assertThat(fragments.get(0).getLevel()).isNull();
        assertThat(fragments.get(0).hasLevel()).isFalse();
        assertThat(fragments.get(0).getLevelLabel()).isEqualTo("Sospecha sin cuantificar");
        assertThat(fragments.get(0).getLevelCssClass()).isEmpty();
        assertThat(fragments.get(1).getLevel()).isNull();
    }

    @Test
    void recortaLasCitasLargasYLimitaSuNumero() {
        List<SuspiciousFragmentDto> dtos = new ArrayList<>();
        dtos.add(SuspiciousFragmentDto.builder().text("a".repeat(400)).build());
        for (String text : List.of("Cita A", "Cita B", "Cita C", "Cita D", "Cita E", "Cita F")) {
            dtos.add(SuspiciousFragmentDto.builder().text(text).build());
        }

        List<SuspiciousFragment> fragments = SegmentResultDto.builder()
                .suspiciousFragments(dtos)
                .build()
                .normalizedSuspiciousFragments(0);

        assertThat(fragments).hasSize(5);
        assertThat(fragments.get(0).getText()).hasSize(301).endsWith("…");
        assertThat(fragments).extracting(SuspiciousFragment::getText)
                .containsExactly("a".repeat(300) + "…", "Cita A", "Cita B", "Cita C", "Cita D");
    }

    @Test
    void eliminaCitasDuplicadasSinDistinguirMayusculasNiEspacios() {
        SegmentResultDto result = SegmentResultDto.builder()
                .suspiciousFragments(List.of(
                        SuspiciousFragmentDto.builder().text("En resumen,   el tema").build(),
                        SuspiciousFragmentDto.builder().text("  en RESUMEN, el tema  ").build()))
                .build();

        List<SuspiciousFragment> fragments = result.normalizedSuspiciousFragments(0);

        assertThat(fragments).hasSize(1);
        assertThat(fragments.get(0).getText()).isEqualTo("En resumen,   el tema");
    }

    @Test
    void normalizaLasEvidenciasHumanas() {
        SegmentResultDto result = SegmentResultDto.builder()
                .humanEvidence(Arrays.asList("Errata coherente", "errata coherente ", "null", "", "  ",
                        null, "Ironía", "Jerga local", "Opinión arriesgada", "Referencia personal"))
                .build();

        assertThat(result.normalizedHumanEvidence()).containsExactly(
                "Errata coherente", "Ironía", "Jerga local", "Opinión arriesgada", "Referencia personal");

        SegmentResultDto withLongEntry = SegmentResultDto.builder()
                .humanEvidence(List.of("x".repeat(250)))
                .build();
        assertThat(withLongEntry.normalizedHumanEvidence().get(0)).hasSize(201).endsWith("…");
    }

    @Test
    void compatibleConElContratoAntiguo() throws Exception {
        String legacy = """
                {
                  "score": 30,
                  "perplexity_indicator": "alto",
                  "burstiness_indicator": "alto",
                  "indicators": ["Estilo personal"],
                  "explanation": "Sin señales claras.",
                  "false_positive_warning": null
                }
                """;

        SegmentResultDto result = objectMapper.readValue(legacy, SegmentResultDto.class);

        assertThat(result.normalizedSuspiciousFragments(0)).isEmpty();
        assertThat(result.normalizedHumanEvidence()).isEmpty();
    }

    @Test
    void aceptaUnValorUnicoEnLugarDeUnaLista() throws Exception {
        String json = """
                {
                  "score": 10,
                  "suspicious_fragments": { "text": "Cita única", "level": "bajo" },
                  "human_evidence": "Una ironía"
                }
                """;

        SegmentResultDto result = objectMapper.readValue(json, SegmentResultDto.class);

        assertThat(result.normalizedSuspiciousFragments(0)).hasSize(1);
        assertThat(result.normalizedSuspiciousFragments(0).get(0).getText()).isEqualTo("Cita única");
        assertThat(result.normalizedHumanEvidence()).containsExactly("Una ironía");
    }

    @Test
    void aceptaUnaCitaSueltaEnTextoPlano() throws Exception {
        String json = """
                { "score": 10, "suspicious_fragments": ["Una cita en texto plano"] }
                """;

        List<SuspiciousFragment> fragments = objectMapper.readValue(json, SegmentResultDto.class)
                .normalizedSuspiciousFragments(1);

        assertThat(fragments).hasSize(1);
        assertThat(fragments.get(0).getText()).isEqualTo("Una cita en texto plano");
        assertThat(fragments.get(0).getSegmentIndex()).isEqualTo(1);
    }
}
