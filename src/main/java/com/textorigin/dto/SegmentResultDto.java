package com.textorigin.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.textorigin.model.SegmentAnalysis;
import com.textorigin.model.SuspiciousFragment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resultado del análisis de un segmento tal y como lo devuelve DeepSeek.
 *
 * <p>Refleja exactamente el contrato JSON descrito en
 * {@code prompts/deepseek-analysis.txt}. Como la respuesta de un modelo de lenguaje nunca
 * es totalmente fiable, esta clase incluye la normalización necesaria: puntuación acotada
 * al rango 0-100, indicadores validados contra el vocabulario permitido, listas nulas
 * convertidas en listas vacías y textos vacíos tratados como ausentes. Los fragmentos
 * sospechosos se sanean en {@link SuspiciousFragmentDto} y las evidencias humanas comparten
 * el tratamiento de los indicadores.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SegmentResultDto {

    /** Valores admitidos para los indicadores de perplejidad y burstiness. */
    private static final Set<String> ALLOWED_LEVELS = Set.of("bajo", "medio", "alto");

    /** Número máximo de indicadores que se conservan. */
    private static final int MAX_INDICATORS = 6;

    /** Longitud máxima de un indicador. */
    private static final int MAX_INDICATOR_LENGTH = 200;

    /** Número máximo de citas sospechosas que se conservan por segmento. */
    private static final int MAX_FRAGMENTS = 5;

    /** Número máximo de evidencias de mano humana que se conservan. */
    private static final int MAX_HUMAN_EVIDENCE = 5;

    /** Longitud máxima de una evidencia de mano humana. */
    private static final int MAX_EVIDENCE_LENGTH = 200;

    /** Puntuación de 0 a 100: probabilidad estimada de generación por IA. */
    private Integer score;

    /** Perplejidad observada: {@code bajo}, {@code medio} o {@code alto}. */
    @JsonProperty("perplexity_indicator")
    private String perplexityIndicator;

    /** Burstiness observado: {@code bajo}, {@code medio} o {@code alto}. */
    @JsonProperty("burstiness_indicator")
    private String burstinessIndicator;

    /** Características detectadas por el modelo. */
    private List<String> indicators;

    /** Citas textuales que el modelo ha señalado como sospechosas, con su motivo y nivel. */
    @JsonProperty("suspicious_fragments")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<SuspiciousFragmentDto> suspiciousFragments;

    /** Señales de posible autoría humana detectadas por el modelo. */
    @JsonProperty("human_evidence")
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> humanEvidence;

    /** Explicación en español sencillo dirigida al profesorado. */
    private String explanation;

    /** Advertencia de posible falso positivo, o {@code null} si no procede. */
    @JsonProperty("false_positive_warning")
    private String falsePositiveWarning;

    /** Puntuación acotada al rango 0-100. */
    public int normalizedScore() {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, score));
    }

    /** Indicador de perplejidad validado, o {@code null} si no es un valor admitido. */
    public String normalizedPerplexity() {
        return normalizeLevel(perplexityIndicator);
    }

    /** Indicador de burstiness validado, o {@code null} si no es un valor admitido. */
    public String normalizedBurstiness() {
        return normalizeLevel(burstinessIndicator);
    }

    /** Indicadores detectados, sin entradas vacías ni duplicados. */
    public List<String> normalizedIndicators() {
        return normalizeStringList(indicators, MAX_INDICATORS, MAX_INDICATOR_LENGTH);
    }

    /** Evidencias de mano humana, sin entradas vacías ni duplicados. */
    public List<String> normalizedHumanEvidence() {
        return normalizeStringList(humanEvidence, MAX_HUMAN_EVIDENCE, MAX_EVIDENCE_LENGTH);
    }

    /**
     * Citas sospechosas saneadas: se descartan las que no traen texto, se eliminan los
     * duplicados (comparando sin distinguir mayúsculas ni espacios) y se limita su número.
     *
     * @param segmentIndex índice del segmento, que se guarda en cada cita para poder enlazarla
     * @return las citas listas para almacenar, en el mismo orden en que llegaron
     */
    public List<SuspiciousFragment> normalizedSuspiciousFragments(int segmentIndex) {
        if (suspiciousFragments == null) {
            return new ArrayList<>();
        }
        List<SuspiciousFragment> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SuspiciousFragmentDto dto : suspiciousFragments) {
            if (dto == null || !dto.isUsable()) {
                continue;
            }
            String key = dto.normalizedText().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!seen.add(key)) {
                continue;
            }
            result.add(dto.toFragment(segmentIndex, result.size()));
            if (result.size() == MAX_FRAGMENTS) {
                break;
            }
        }
        return result;
    }

    /** Explicación saneada, con un texto de reserva si el modelo no la proporcionó. */
    public String normalizedExplanation() {
        if (explanation == null || explanation.isBlank()) {
            return "El modelo no ha proporcionado una explicación detallada para este fragmento.";
        }
        return explanation.trim();
    }

    /** Advertencia de falso positivo, o {@code null} si el modelo no señaló ninguna. */
    public String normalizedFalsePositiveWarning() {
        if (falsePositiveWarning == null || falsePositiveWarning.isBlank()
                || "null".equalsIgnoreCase(falsePositiveWarning.trim())) {
            return null;
        }
        return falsePositiveWarning.trim();
    }

    private static String normalizeLevel(String level) {
        if (level == null) {
            return null;
        }
        String value = level.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_LEVELS.contains(value) ? value : null;
    }

    /**
     * Saneado común de las listas de texto del modelo: descarta entradas nulas, vacías o con el
     * literal {@code "null"}, recorta las demasiado largas y elimina duplicados comparando sin
     * distinguir mayúsculas.
     */
    private static List<String> normalizeStringList(List<String> values, int maxItems, int maxLength) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String clean = cleanText(value, maxLength);
            if (clean == null || !seen.add(clean.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(clean);
            if (result.size() == maxItems) {
                break;
            }
        }
        return result;
    }

    private static String cleanText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean)) {
            return null;
        }
        return clean.length() <= maxLength ? clean
                : clean.substring(0, maxLength).stripTrailing() + "…";
    }

    /**
     * Convierte el resultado en el modelo de dominio aplicando la normalización.
     *
     * @param index      posición del segmento en el documento
     * @param text       texto analizado
     * @param durationMs tiempo empleado en el análisis
     * @return el resultado listo para almacenar
     */
    public SegmentAnalysis toSegmentAnalysis(int index, String text, long durationMs) {
        return SegmentAnalysis.builder()
                .index(index)
                .text(text)
                .score(normalizedScore())
                .perplexityIndicator(normalizedPerplexity())
                .burstinessIndicator(normalizedBurstiness())
                .indicators(normalizedIndicators())
                .suspiciousFragments(normalizedSuspiciousFragments(index))
                .humanEvidence(normalizedHumanEvidence())
                .explanation(normalizedExplanation())
                .falsePositiveWarning(normalizedFalsePositiveWarning())
                .durationMs(durationMs)
                .build();
    }
}
