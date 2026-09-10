package com.textorigin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resultado completo del análisis de un {@link Document}.
 *
 * <p>Es la unidad que se almacena en memoria y se identifica con un UUID. Mientras el
 * análisis está en curso, la lista de segmentos contiene objetos en estado pendiente que
 * se van sustituyendo por los resultados definitivos; por eso la lista es una
 * {@link CopyOnWriteArrayList}, que publica cada sustitución de forma segura entre hilos.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysis {

    /** Estados del ciclo de vida de un análisis. */
    public enum Status {
        /** Creado, todavía sin procesar. */
        PENDING,
        /** Procesándose en segundo plano. */
        IN_PROGRESS,
        /** Finalizado, con resultados disponibles. */
        COMPLETED,
        /** Finalizado sin resultados utilizables. */
        FAILED
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

    /** Identificador del análisis (UUID), usado en las URLs y en el informe PDF. */
    private String id;

    /** Documento analizado. */
    private Document document;

    /**
     * Estado actual del análisis.
     *
     * <p>Es {@code volatile} porque lo escribe el hilo de fondo que analiza los segmentos y lo
     * leen las peticiones que consultan el progreso. Al ser la última escritura del análisis,
     * una lectura de {@code COMPLETED} publica también el resto de resultados.</p>
     */
    private volatile Status status;

    /** Un resultado por cada segmento del documento, en el mismo orden. */
    @Builder.Default
    private List<SegmentAnalysis> segments = new CopyOnWriteArrayList<>();

    /** Mensaje de error global, si el análisis no pudo completarse. */
    private String errorMessage;

    /** Momento de creación del análisis. */
    private LocalDateTime createdAt;

    /** Momento en que finalizó el análisis. */
    private LocalDateTime completedAt;

    /** Duración total del análisis en milisegundos. */
    private long durationMs;

    /** Número total de segmentos del documento. */
    public int getTotalCount() {
        return segments == null ? 0 : segments.size();
    }

    /** Número de segmentos ya resueltos (con resultado o con error). */
    public int getProcessedCount() {
        if (segments == null) {
            return 0;
        }
        return (int) segments.stream().filter(s -> !s.isPending()).count();
    }

    /** Número de segmentos todavía pendientes. */
    public int getPendingCount() {
        return getTotalCount() - getProcessedCount();
    }

    /** Número de segmentos clasificados como probablemente humanos. */
    public int getHumanCount() {
        return countByCategory(SegmentAnalysis.Category.HUMAN);
    }

    /** Número de segmentos clasificados como dudosos. */
    public int getDoubtfulCount() {
        return countByCategory(SegmentAnalysis.Category.DOUBTFUL);
    }

    /** Número de segmentos clasificados como alta sospecha de IA. */
    public int getAiCount() {
        return countByCategory(SegmentAnalysis.Category.AI);
    }

    /** Número de segmentos que no pudieron analizarse. */
    public int getErrorCount() {
        return countByCategory(SegmentAnalysis.Category.ERROR);
    }

    private int countByCategory(SegmentAnalysis.Category category) {
        if (segments == null) {
            return 0;
        }
        return (int) segments.stream().filter(s -> s.getCategory() == category).count();
    }

    /**
     * Score global del documento: media ponderada por número de palabras de los segmentos
     * analizados correctamente. Se recalcula en cada consulta para que la barra de progreso
     * refleje el estado real durante el análisis.
     *
     * @return score entre 0 y 100, o {@code null} si aún no hay ningún segmento analizado
     */
    public Double getGlobalScore() {
        if (segments == null) {
            return null;
        }
        double weightedSum = 0;
        long totalWeight = 0;
        for (SegmentAnalysis segment : segments) {
            if (!segment.isCompleted()) {
                continue;
            }
            int weight = Math.max(1, countWords(segment.getText()));
            weightedSum += segment.getScore() * (double) weight;
            totalWeight += weight;
        }
        return totalWeight == 0 ? null : weightedSum / totalWeight;
    }

    /** Score global redondeado, o {@code null} si todavía no hay resultados. */
    public Integer getGlobalScoreRounded() {
        Double value = getGlobalScore();
        return value == null ? null : (int) Math.round(value);
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    /** Porcentaje de segmentos ya procesados (0-100), para la barra de progreso. */
    public int getProgressPercent() {
        int total = getTotalCount();
        return total == 0 ? 0 : (int) Math.round(getProcessedCount() * 100.0 / total);
    }

    /** Categoría global del documento, derivada del score global. */
    public SegmentAnalysis.Category getGlobalCategory() {
        Integer value = getGlobalScoreRounded();
        return value == null ? SegmentAnalysis.Category.PENDING : SegmentAnalysis.categoryFor(value);
    }

    /** Clase CSS del veredicto global. */
    public String getGlobalCssClass() {
        return switch (getGlobalCategory()) {
            case HUMAN -> "score-human";
            case DOUBTFUL -> "score-doubtful";
            case AI -> "score-ai";
            case ERROR, PENDING -> "score-pending";
        };
    }

    /**
     * Veredicto orientativo en lenguaje llano. Insiste siempre en el carácter probabilístico
     * del resultado, nunca en una certeza.
     */
    public String getVerdict() {
        if (status == Status.FAILED) {
            return "El análisis no pudo completarse";
        }
        return switch (getGlobalCategory()) {
            case HUMAN -> "El texto presenta rasgos compatibles con la escritura humana";
            case DOUBTFUL -> "El texto muestra señales mixtas: conviene revisarlo con detenimiento";
            case AI -> "El texto presenta indicios relevantes de generación automática";
            case ERROR, PENDING -> "Análisis en curso";
        };
    }

    /** Frase de matiz que acompaña al veredicto en la interfaz y en el informe. */
    public String getVerdictHint() {
        return switch (getGlobalCategory()) {
            case HUMAN -> "No se han detectado patrones típicos de IA, aunque esto no demuestra autoría humana.";
            case DOUBTFUL -> "Algunos párrafos podrían ser de IA. Revise los segmentos marcados y contraste con el estudiante.";
            case AI -> "Varios párrafos muestran patrones característicos de IA. Este indicio no es una prueba: dialogue con el estudiante.";
            case ERROR, PENDING -> "";
        };
    }

    /** Etiqueta legible del estado del análisis. */
    public String getStatusLabel() {
        return switch (status) {
            case PENDING -> "En cola";
            case IN_PROGRESS -> "Analizando";
            case COMPLETED -> "Completado";
            case FAILED -> "Fallido";
        };
    }

    /** Indica si el análisis terminó correctamente. */
    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    /** Indica si el análisis falló. */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /** Indica si el análisis sigue en curso. */
    public boolean isRunning() {
        return status == Status.PENDING || status == Status.IN_PROGRESS;
    }

    /** Fecha de creación formateada para la interfaz. */
    public String getFormattedCreatedAt() {
        return createdAt == null ? "" : DATE_FORMAT.format(createdAt);
    }

    /** Fecha de finalización formateada para la interfaz. */
    public String getFormattedCompletedAt() {
        return completedAt == null ? "" : DATE_FORMAT.format(completedAt);
    }

    /** Duración total legible. */
    public String getFormattedDuration() {
        return durationMs > 0 ? String.format("%.1f s", durationMs / 1000.0) : "";
    }

    /** Nombre del documento analizado, o una etiqueta genérica. */
    public String getDocumentName() {
        if (document == null || document.getFileName() == null || document.getFileName().isBlank()) {
            return "Texto sin título";
        }
        return document.getFileName();
    }
}
