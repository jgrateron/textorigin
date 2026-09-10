package com.textorigin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.textorigin.model.SegmentAnalysis;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
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
 * convertidas en listas vacías y textos vacíos tratados como ausentes.</p>
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
        if (indicators == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String indicator : indicators) {
            if (indicator != null && !indicator.isBlank() && !result.contains(indicator.trim())) {
                result.add(indicator.trim());
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
                .explanation(normalizedExplanation())
                .falsePositiveWarning(normalizedFalsePositiveWarning())
                .durationMs(durationMs)
                .build();
    }
}
