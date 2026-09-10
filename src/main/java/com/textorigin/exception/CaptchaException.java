package com.textorigin.exception;

/**
 * Se lanza cuando la verificación anti-bots (Cloudflare Turnstile) no se supera.
 *
 * <p>Se comprueba antes que la cuota y antes de llamar a DeepSeek: un envío sin token válido
 * no gasta ni análisis del visitante ni tokens del modelo.</p>
 */
public class CaptchaException extends RuntimeException {

    public CaptchaException(String message) {
        super(message);
    }

    /** Título corto para la interfaz. */
    public String getTitle() {
        return "Verificación de seguridad";
    }

    /** Mensaje para la interfaz. */
    public String getUserMessage() {
        return "No se ha podido confirmar que la solicitud venga de una persona. Marca la "
                + "casilla de verificación y vuelve a intentarlo.";
    }
}
