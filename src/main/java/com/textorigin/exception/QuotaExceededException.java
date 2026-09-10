package com.textorigin.exception;

/**
 * Se lanza cuando el usuario ha agotado su cuota de análisis.
 *
 * <p>El límite agotado puede ser el de la sesión HTTP (3 análisis) o el de la IP
 * (10 análisis por día). La excepción transporta el tipo de límite y los contadores
 * para que la interfaz pueda mostrar un mensaje específico y el correo de contacto.</p>
 */
public class QuotaExceededException extends RuntimeException {

    /** Tipo de límite agotado. */
    public enum QuotaType {
        /** Límite de análisis por sesión HTTP. */
        SESSION,
        /** Límite de análisis por IP y día. */
        IP
    }

    private final QuotaType quotaType;
    private final int limit;
    private final int used;

    public QuotaExceededException(QuotaType quotaType, int limit, int used, String message) {
        super(message);
        this.quotaType = quotaType;
        this.limit = limit;
        this.used = used;
    }

    public QuotaType getQuotaType() {
        return quotaType;
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

    /** Mensaje específico según el tipo de límite agotado. */
    public String getUserMessage() {
        return quotaType == QuotaType.SESSION
                ? "Has utilizado los " + limit + " análisis disponibles en esta sesión. "
                  + "El contador se reinicia tras 30 minutos de inactividad."
                : "Has alcanzado el máximo de " + limit + " análisis diarios permitidos desde tu conexión. "
                  + "El contador se reinicia cada día.";
    }
}
