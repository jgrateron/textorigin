package com.textorigin.exception;

/**
 * Se lanza cuando el texto no puede extraerse de la entrada del usuario.
 *
 * <p>Cubre los casos de archivo demasiado grande, formato no soportado, documento
 * corrupto o ilegible, y texto resultante vacío o demasiado corto para analizarlo
 * con un mínimo de fiabilidad.</p>
 */
public class TextExtractionException extends RuntimeException {

    /** Identificador de la categoría de error, usado para elegir el mensaje de la interfaz. */
    private final Reason reason;

    /** Motivos por los que puede fallar la extracción. */
    public enum Reason {
        /** El archivo supera el límite de 10 MB. */
        FILE_TOO_LARGE,
        /** La extensión o el tipo del archivo no está soportado. */
        UNSUPPORTED_FORMAT,
        /** El archivo no se pudo leer o está dañado. */
        UNREADABLE_FILE,
        /** El texto resultante está vacío o por debajo del mínimo configurado. */
        TEXT_TOO_SHORT,
        /** No se recibió ni archivo ni texto. */
        EMPTY_INPUT
    }

    public TextExtractionException(String message) {
        this(message, Reason.UNREADABLE_FILE, null);
    }

    public TextExtractionException(String message, Reason reason) {
        this(message, reason, null);
    }

    public TextExtractionException(String message, Reason reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
