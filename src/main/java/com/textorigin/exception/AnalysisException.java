package com.textorigin.exception;

/**
 * Se lanza cuando el análisis con el modelo de lenguaje no puede completarse.
 *
 * <p>Agrupa los fallos de comunicación con DeepSeek (timeout, error HTTP, cuota de la API),
 * las respuestas que no respetan el formato JSON esperado y la ausencia de configuración
 * de la clave de API.</p>
 */
public class AnalysisException extends RuntimeException {

    /** Indica que el análisis solicitado no existe (o ha caducado) en lugar de haber fallado. */
    private final boolean notFound;

    public AnalysisException(String message) {
        this(message, null, false);
    }

    public AnalysisException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private AnalysisException(String message, Throwable cause, boolean notFound) {
        super(message, cause);
        this.notFound = notFound;
    }

    /**
     * Crea una excepción para un análisis inexistente o caducado, que el manejador de errores
     * traduce a un 404 en lugar de a un fallo del servicio.
     *
     * @param message mensaje para el usuario
     * @return la excepción marcada como "no encontrado"
     */
    public static AnalysisException notFound(String message) {
        return new AnalysisException(message, null, true);
    }

    public boolean isNotFound() {
        return notFound;
    }
}
