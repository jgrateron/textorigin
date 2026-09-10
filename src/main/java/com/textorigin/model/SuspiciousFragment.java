package com.textorigin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cita textual de un fragmento que el modelo ha señalado como sospechoso.
 *
 * <p>A diferencia de los indicadores de {@link SegmentAnalysis}, que son etiquetas genéricas,
 * este objeto conserva el texto literal para que el profesorado pueda localizarlo en el
 * documento original y contrastarlo con el estudiante.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspiciousFragment {

    /** Posición del fragmento dentro de su segmento (0-based). */
    private int index;

    /** Índice del segmento del documento al que pertenece la cita. */
    private int segmentIndex;

    /** Cita textual del segmento, tal y como la devolvió el modelo. */
    private String text;

    /** Motivo por el que el modelo considera sospechoso este fragmento. */
    private String reason;

    /** Nivel de sospecha: {@code alto}, {@code medio}, {@code bajo} o {@code null} si no es válido. */
    private String level;

    /** Indica si el modelo señaló un nivel de sospecha utilizable. */
    public boolean hasLevel() {
        return level != null;
    }

    /** Etiqueta legible del nivel de sospecha. */
    public String getLevelLabel() {
        if (level == null) {
            return "Sospecha sin cuantificar";
        }
        return switch (level) {
            case "alto" -> "Sospecha alta";
            case "medio" -> "Sospecha media";
            default -> "Sospecha baja";
        };
    }

    /** Clase CSS asociada al nivel de sospecha, para la insignia de la interfaz. */
    public String getLevelCssClass() {
        return level == null ? "" : "fragment-level--" + level;
    }

    /** Etiqueta del segmento al que pertenece la cita: "Segmento 3". */
    public String getSegmentLabel() {
        return "Segmento " + (segmentIndex + 1);
    }
}
