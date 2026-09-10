package com.textorigin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Resultado del análisis de un {@link Segment} concreto.
 *
 * <p>El objeto se crea en estado <em>pendiente</em> (sin {@code score}) cuando se prepara
 * el análisis y se sustituye por una nueva instancia con el resultado cuando el modelo
 * responde. Así el hilo que consulta el progreso siempre encuentra objetos consistentes.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentAnalysis {

    /** Categorías derivadas del score. */
    public enum Category {
        /** Score &lt; 40: el texto parece de escritura humana. */
        HUMAN,
        /** Score entre 40 y 70: resultado dudoso, requiere revisión manual. */
        DOUBTFUL,
        /** Score &gt; 70: indicios elevados de generación por IA. */
        AI,
        /** Todavía no analizado. */
        PENDING,
        /** El análisis de este segmento falló. */
        ERROR
    }

    /** Umbral superior (exclusivo) de la categoría humana. */
    public static final int HUMAN_MAX = 40;

    /** Umbral superior (inclusive) de la categoría dudosa. */
    public static final int DOUBTFUL_MAX = 70;

    /** Índice del segmento dentro del documento. */
    private int index;

    /** Texto analizado. */
    private String text;

    /** Probabilidad estimada (0-100) de que el texto haya sido generado por IA. {@code null} si está pendiente. */
    private Integer score;

    /** Indicador de perplejidad: {@code bajo}, {@code medio} o {@code alto}. */
    private String perplexityIndicator;

    /** Indicador de burstiness: {@code bajo}, {@code medio} o {@code alto}. */
    private String burstinessIndicator;

    /** Características detectadas por el modelo. */
    @Builder.Default
    private List<String> indicators = new ArrayList<>();

    /** Citas textuales que el modelo ha señalado como sospechosas en este segmento. */
    @Builder.Default
    private List<SuspiciousFragment> suspiciousFragments = new ArrayList<>();

    /** Señales de posible autoría humana detectadas por el modelo en este segmento. */
    @Builder.Default
    private List<String> humanEvidence = new ArrayList<>();

    /** Explicación en lenguaje sencillo para el profesorado. */
    private String explanation;

    /** Advertencia de posible falso positivo, si el modelo la ha señalado. */
    private String falsePositiveWarning;

    /** Mensaje de error si el análisis de este segmento no pudo completarse. */
    private String errorMessage;

    /** Milisegundos empleados en analizar el segmento. */
    private long durationMs;

    /** Indica si el segmento ya tiene resultado. */
    public boolean isCompleted() {
        return score != null;
    }

    /** Indica si el segmento no pudo analizarse. */
    public boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }

    /** Indica si el segmento sigue en cola o en proceso. */
    public boolean isPending() {
        return score == null && !hasError();
    }

    /** Categoría del segmento según su score. */
    public Category getCategory() {
        if (isCompleted()) {
            return categoryFor(score);
        }
        return hasError() ? Category.ERROR : Category.PENDING;
    }

    /**
     * Clasifica un score en su categoría.
     *
     * @param value score entre 0 y 100
     * @return la categoría correspondiente
     */
    public static Category categoryFor(int value) {
        if (value < HUMAN_MAX) {
            return Category.HUMAN;
        }
        return value <= DOUBTFUL_MAX ? Category.DOUBTFUL : Category.AI;
    }

    /** Clase CSS asociada al segmento para el resaltado visual. */
    public String getCssClass() {
        return switch (getCategory()) {
            case HUMAN -> "score-human";
            case DOUBTFUL -> "score-doubtful";
            case AI -> "score-ai";
            case ERROR -> "score-error";
            case PENDING -> "score-pending";
        };
    }

    /** Etiqueta legible de la categoría. */
    public String getCategoryLabel() {
        return switch (getCategory()) {
            case HUMAN -> "Probablemente humano";
            case DOUBTFUL -> "Dudoso";
            case AI -> "Alta sospecha de IA";
            case ERROR -> "No analizado";
            case PENDING -> "Pendiente";
        };
    }

    /** Etiqueta corta para la interfaz: "Segmento 3". */
    public String getLabel() {
        return "Segmento " + (index + 1);
    }

    /** Porcentaje estimado listo para las insignias de la interfaz: "62 %". */
    public String getScoreLabel() {
        if (isCompleted()) {
            return score + " %";
        }
        return hasError() ? "—" : "…";
    }

    /** Score numérico seguro para plantillas (0 si aún no hay resultado). */
    public int getScoreValue() {
        return score == null ? 0 : score;
    }

    /** Indicador de perplejidad normalizado, listo para mostrar. */
    public String getPerplexityLabel() {
        return isCompleted() && perplexityIndicator != null ? perplexityIndicator : "sin datos";
    }

    /** Indicador de burstiness normalizado, listo para mostrar. */
    public String getBurstinessLabel() {
        return isCompleted() && burstinessIndicator != null ? burstinessIndicator : "sin datos";
    }

    /** Indica si hay advertencia de falso positivo que mostrar. */
    public boolean hasFalsePositiveWarning() {
        return falsePositiveWarning != null && !falsePositiveWarning.isBlank();
    }

    /** Indica si hay indicadores que listar. */
    public boolean hasIndicators() {
        return indicators != null && !indicators.isEmpty();
    }

    /** Indica si hay fragmentos sospechosos citados que mostrar. */
    public boolean hasSuspiciousFragments() {
        return suspiciousFragments != null && !suspiciousFragments.isEmpty();
    }

    /** Número de fragmentos sospechosos citados en el segmento. */
    public int getSuspiciousFragmentCount() {
        return suspiciousFragments == null ? 0 : suspiciousFragments.size();
    }

    /** Indica si hay evidencias de mano humana que mostrar. */
    public boolean hasHumanEvidence() {
        return humanEvidence != null && !humanEvidence.isEmpty();
    }

    /** Número de evidencias de mano humana detectadas en el segmento. */
    public int getHumanEvidenceCount() {
        return humanEvidence == null ? 0 : humanEvidence.size();
    }

    /** Duración legible del análisis del segmento. */
    public String getFormattedDuration() {
        return durationMs > 0 ? (durationMs / 1000.0) + " s" : "";
    }
}
