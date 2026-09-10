package com.textorigin.service;

/**
 * Umbrales y comprobaciones que comparten la detección de texto oculto en PDF
 * ({@link HiddenTextPdfStripper}) y en DOCX (dentro de {@code TextExtractionService}).
 *
 * <p>Los umbrales son deliberadamente estrictos: solo se descarta texto que nadie podría leer
 * (por debajo de 2 pt, pintado en blanco o invisible), nunca letra pequeña legible.</p>
 */
final class HiddenFormatRules {

    /**
     * Por debajo de este tamaño (en puntos) el texto no es legible ni en pantalla ni impreso:
     * el texto oculto de los documentos reales se dibuja a 1 pt o menos, mientras que la letra
     * pequeña legible (pies de página, notas legales) rara vez baja de 6 pt.
     */
    static final float HIDDEN_FONT_SIZE_PT = 2.0f;

    /** Componente RGB a partir del cual un color cuenta como blanco. */
    static final float WHITE_COMPONENT_MIN = 0.95f;

    /** Tinta máxima por componente para considerar blanco un color CMYK. */
    static final float INK_COMPONENT_MAX = 0.05f;

    private HiddenFormatRules() {
    }

    /**
     * Indica si una tinta de PDF es (casi) blanca, admitiendo escala de grises, RGB y CMYK.
     *
     * @param components componentes del color, posiblemente {@code null}
     * @return {@code true} solo si se puede afirmar que la tinta es blanca
     */
    static boolean isWhiteish(float[] components) {
        if (components == null) {
            return false;
        }
        return switch (components.length) {
            case 1 -> components[0] >= WHITE_COMPONENT_MIN;
            case 3 -> components[0] >= WHITE_COMPONENT_MIN
                    && components[1] >= WHITE_COMPONENT_MIN
                    && components[2] >= WHITE_COMPONENT_MIN;
            case 4 -> components[0] <= INK_COMPONENT_MAX
                    && components[1] <= INK_COMPONENT_MAX
                    && components[2] <= INK_COMPONENT_MAX
                    && components[3] <= INK_COMPONENT_MAX;
            default -> false;
        };
    }

    /**
     * Indica si un color hexadecimal de DOCX («FFFFFF») es (casi) blanco. Un color {@code null}
     * (habitual cuando el color viene de un tema de Word) no se considera blanco.
     *
     * @param hexColor color en hexadecimal, posiblemente {@code null} o con almohadilla
     * @return {@code true} solo si el color se puede leer y es blanco
     */
    static boolean isWhiteish(String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return false;
        }
        String hex = hexColor.startsWith("#") ? hexColor.substring(1) : hexColor;
        if (hex.length() != 6) {
            return false;
        }
        try {
            int threshold = (int) Math.ceil(WHITE_COMPONENT_MIN * 255);
            return Integer.parseInt(hex.substring(0, 2), 16) >= threshold
                    && Integer.parseInt(hex.substring(2, 4), 16) >= threshold
                    && Integer.parseInt(hex.substring(4, 6), 16) >= threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Indica si un tamaño de fuente es positivo y menor que el umbral: un tamaño desconocido
     * (cero o negativo, habitual cuando la fuente hereda el tamaño) nunca se considera oculto.
     */
    static boolean isTooSmall(double fontSizePt) {
        return fontSizePt > 0 && fontSizePt < HIDDEN_FONT_SIZE_PT;
    }
}
