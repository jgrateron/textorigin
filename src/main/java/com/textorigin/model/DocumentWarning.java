package com.textorigin.model;

import java.util.List;

/**
 * Aviso sobre contenido del documento que las defensas anti prompt-injection han detectado
 * (y, cuando procede, neutralizado) antes de enviar el texto al modelo.
 *
 * <p>Los avisos se acumulan en {@link DocumentAnalysis#getWarnings()} y los muestran el panel
 * de resultados y el informe PDF. Cada aviso reproduce, cuando existe, el fragmento original
 * sustituido o descartado: la neutralización nunca es silenciosa.</p>
 */
public record DocumentWarning(Kind kind, String title, String detail, List<String> excerpts, int count) {

    /** Familias de avisos. */
    public enum Kind {
        /** Texto descartado por su formato (blanco, diminuto, invisible, fuera de página). */
        HIDDEN_FORMAT,
        /** Caracteres Unicode invisibles eliminados. */
        INVISIBLE_CHARACTERS,
        /** Frase dirigida al modelo, sustituida por una marca visible. */
        INSTRUCTION_PHRASE
    }

    /** Longitud máxima de un extracto, tanto en la web como en el PDF. */
    public static final int MAX_EXCERPT_CHARS = 120;

    private static final String OPENING_QUOTES = "\"'«“‘";
    private static final String CLOSING_QUOTES = "\"'»”’";

    public DocumentWarning {
        excerpts = List.copyOf(excerpts == null ? List.of() : excerpts);
        count = Math.max(1, count);
    }

    // Getters explícitos: las plantillas Thymeleaf acceden a propiedades reales (getX()).

    public Kind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public List<String> getExcerpts() {
        return excerpts;
    }

    public int getCount() {
        return count;
    }

    /** Indica si hay extractos que mostrar. */
    public boolean hasExcerpts() {
        return !excerpts.isEmpty();
    }

    /** Indica si hay detalle que mostrar. */
    public boolean hasDetail() {
        return detail != null && !detail.isBlank();
    }

    /** Etiqueta legible de la familia, para la interfaz. */
    public String getKindLabel() {
        return switch (kind) {
            case HIDDEN_FORMAT -> "Formato oculto";
            case INVISIBLE_CHARACTERS -> "Caracteres invisibles";
            case INSTRUCTION_PHRASE -> "Instrucción neutralizada";
        };
    }

    /**
     * Prepara un texto para mostrarlo como extracto: colapsa los espacios y saltos de línea,
     * retira las comillas envolventes (el render añade «») y recorta a
     * {@value #MAX_EXCERPT_CHARS} caracteres.
     *
     * @param raw texto original, posiblemente {@code null}
     * @return extracto listo para mostrar, nunca {@code null}
     */
    public static String truncateExcerpt(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.replaceAll("\\s+", " ").strip();
        collapsed = stripWrappingQuotes(collapsed);
        return collapsed.length() <= MAX_EXCERPT_CHARS
                ? collapsed
                : collapsed.substring(0, MAX_EXCERPT_CHARS) + "…";
    }

    /** Varios fragmentos descartados por su formato oculto, agrupados por motivo. */
    public static DocumentWarning hiddenFormat(String reason, int count, String locationSummary,
                                                List<String> excerpts) {
        StringBuilder detail = new StringBuilder();
        detail.append(count == 1 ? "Se ha descartado 1 fragmento" : "Se han descartado " + count + " fragmentos");
        detail.append(" con ").append(reason);
        if (locationSummary != null && !locationSummary.isBlank()) {
            detail.append(" (").append(locationSummary).append(")");
        }
        detail.append(". Ese texto no se analiza, pero aquí se reproduce lo que ocultaba.");
        return new DocumentWarning(Kind.HIDDEN_FORMAT, "Texto oculto descartado del análisis",
                detail.toString(), excerpts, count);
    }

    /** El documento es casi todo texto «oculto» (capa OCR, PDF escaneado): se analiza completo. */
    public static DocumentWarning hiddenTextMajority(int percent) {
        return new DocumentWarning(Kind.HIDDEN_FORMAT, "Documento con casi todo el texto oculto",
                "El " + percent + " % del texto está marcado como oculto, algo habitual en capas de OCR o "
                        + "PDF escaneados. Para no dejar el análisis sin contenido, se analiza el documento completo.",
                List.of(), 1);
    }

    /** Caracteres invisibles eliminados, con su histograma y el mensaje decodificado si lo había. */
    public static DocumentWarning invisibleCharacters(int removedCount, String detail, List<String> excerpts) {
        return new DocumentWarning(Kind.INVISIBLE_CHARACTERS, "Caracteres invisibles eliminados",
                detail, excerpts, removedCount);
    }

    /** Frase dirigida al modelo, sustituida por una marca visible. */
    public static DocumentWarning instructionPhrase(String excerpt) {
        return new DocumentWarning(Kind.INSTRUCTION_PHRASE, "Posible instrucción dirigida al modelo",
                "El fragmento señalado parecía dirigirse al analizador y no al contenido del trabajo: se ha "
                        + "sustituido por una marca visible y aquí se reproduce el original.",
                List.of(excerpt), 1);
    }

    /** Frases neutralizadas más allá del límite de avisos individuales. */
    public static DocumentWarning moreInstructionPhrases(int additionalCount) {
        return new DocumentWarning(Kind.INSTRUCTION_PHRASE, "Más instrucciones neutralizadas",
                "Se han neutralizado " + additionalCount + " fragmentos más dirigidos al modelo; también se han "
                        + "sustituido por la marca visible.", List.of(), additionalCount);
    }

    /** Intentos de cerrar el delimitador del texto que se envía al modelo. */
    public static DocumentWarning promptDelimiter(int count) {
        String detail = "Se ha neutralizado 1 secuencia de triples comillas que podía cerrar el bloque de texto "
                + "enviado al modelo.";
        if (count > 1) {
            detail = "Se han neutralizado " + count + " secuencias de triples comillas que podían cerrar el "
                    + "bloque de texto enviado al modelo.";
        }
        return new DocumentWarning(Kind.INSTRUCTION_PHRASE, "Posible cierre del delimitador del texto",
                detail, List.of(), count);
    }

    /** Una comprobación de la defensa no pudo completarse: el documento se analiza igualmente. */
    public static DocumentWarning defenseUnavailable(String detail) {
        return new DocumentWarning(Kind.HIDDEN_FORMAT, "No se pudo comprobar todo el contenido oculto",
                "El documento se analiza igualmente, sin esa comprobación. Detalle: " + detail,
                List.of(), 1);
    }

    /** Retira un par de comillas envolventes si el texto va entrecomillado de principio a fin. */
    private static String stripWrappingQuotes(String text) {
        if (text.length() < 2) {
            return text;
        }
        int index = OPENING_QUOTES.indexOf(text.charAt(0));
        if (index < 0 || text.charAt(text.length() - 1) != CLOSING_QUOTES.charAt(index)) {
            return text;
        }
        return text.substring(1, text.length() - 1).strip();
    }
}
