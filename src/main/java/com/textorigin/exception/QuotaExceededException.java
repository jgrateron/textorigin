package com.textorigin.exception;

/**
 * Se lanza cuando el visitante ha agotado su cuota de análisis.
 *
 * <p>El límite es diario y por IP. La excepción transporta los contadores para que la
 * interfaz pueda mostrar un mensaje específico y el correo de contacto.</p>
 */
public class QuotaExceededException extends RuntimeException {

    private final int limit;
    private final int used;

    public QuotaExceededException(int limit, int used, String message) {
        super(message);
        this.limit = limit;
        this.used = used;
    }

    /** Límite configurado que se ha alcanzado. */
    public int getLimit() {
        return limit;
    }

    /** Consumo acumulado en el momento de denegar el acceso. */
    public int getUsed() {
        return used;
    }

    /** Título corto para la interfaz. */
    public String getTitle() {
        return "Límite alcanzado";
    }

    /** Mensaje para la interfaz. */
    public String getUserMessage() {
        return "Has alcanzado el máximo de " + limit + " análisis diarios permitidos desde tu conexión. "
                + "El contador se reinicia cada día.";
    }
}
