package com.textorigin.model;

import java.util.List;

/**
 * Texto ya saneado junto con los avisos que ha generado el saneado.
 *
 * <p>Es el tipo de retorno de la extracción y de las defensas anti prompt-injection: el texto
 * viaja hacia el análisis y los avisos hacia {@link DocumentAnalysis}. El texto nunca es
 * {@code null} y la lista de avisos es inmutable.</p>
 *
 * @param text     texto saneado, listo para segmentar
 * @param warnings avisos generados, en orden de detección
 */
public record SanitizedText(String text, List<DocumentWarning> warnings) {

    public SanitizedText {
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /** Texto sin hallazgos. */
    public static SanitizedText of(String text) {
        return new SanitizedText(text, List.of());
    }

    /** Indica si el saneado ha dejado avisos. */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
