package com.textorigin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fragmento de texto (normalmente un párrafo) en el que se divide un {@link Document}
 * antes de enviarlo al modelo de lenguaje.
 *
 * <p>Los desplazamientos {@link #startOffset} y {@link #endOffset} se calculan sobre el
 * texto normalizado del documento y permiten reconstruir la posición original del
 * fragmento dentro del texto completo.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Segment {

    /** Posición del segmento dentro del documento (base 0). */
    private int index;

    /** Texto del segmento, ya normalizado. */
    private String text;

    /** Posición inicial dentro del texto normalizado del documento. */
    private int startOffset;

    /** Posición final (exclusiva) dentro del texto normalizado del documento. */
    private int endOffset;

    /** Número de caracteres del segmento. */
    private int characterCount;

    /** Número de palabras del segmento. */
    private int wordCount;

    /** Número de párrafos originales agrupados en este segmento (mayor que 1 si hubo agrupación). */
    private int mergedParagraphs;

    /** Etiqueta corta para la interfaz: "Segmento 3". */
    public String getLabel() {
        return "Segmento " + (index + 1);
    }
}
