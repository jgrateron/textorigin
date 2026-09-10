package com.textorigin.model;

/**
 * Fragmento de texto descartado por su formato oculto (blanco, diminuto, invisible, fuera de
 * página), con la localización donde aparecía y el motivo.
 *
 * @param location descripción legible del sitio («página 3», «párrafo 12», «tabla 2»)
 * @param reason   motivo por el que se considera oculto («modo de renderizado invisible»…)
 * @param text     el texto que quedaba oculto, para citarlo en el aviso
 */
public record HiddenSpan(String location, String reason, String text) {

    public HiddenSpan {
        location = location == null ? "" : location;
        reason = reason == null ? "" : reason;
        text = text == null ? "" : text;
    }

    /** Indica si el fragmento oculto contenía algo de texto. */
    public boolean hasText() {
        return !text.isBlank();
    }
}
