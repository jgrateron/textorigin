package com.textorigin.controller;

import com.textorigin.exception.AnalysisException;
import com.textorigin.exception.CaptchaException;
import com.textorigin.exception.QuotaExceededException;
import com.textorigin.exception.TextExtractionException;
import com.textorigin.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Manejo centralizado de errores.
 *
 * <p>Convierte las excepciones de la aplicación en respuestas con el estilo de TextOrigin:
 * la página completa {@code error} para la navegación normal y un fragmento para las
 * peticiones de HTMX, que se inserta donde estaba el formulario.</p>
 *
 * <p><strong>Nota sobre los códigos de estado:</strong> a las peticiones de HTMX se les
 * responde con {@code 200} aunque el resultado sea un error, porque HTMX solo sustituye
 * contenido con respuestas correctas; el error se comunica visualmente en el propio
 * fragmento. Las peticiones normales sí reciben el código HTTP que les corresponde.</p>
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /** Cabecera que HTMX añade a todas sus peticiones. */
    private static final String HTMX_REQUEST_HEADER = "HX-Request";

    private static final String ERROR_VIEW = "error";
    private static final String ERROR_FRAGMENT = "error :: content";
    private static final String QUOTA_FRAGMENT = "fragments/quota-exceeded :: content";

    private final QuotaService quotaService;

    public GlobalExceptionHandler(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * Cuota agotada: se muestra el bloque de límite alcanzado con el correo de contacto.
     *
     * @param ex      excepción de cuota agotada
     * @param request petición en curso
     * @return la vista o el fragmento correspondiente
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ModelAndView handleQuotaExceeded(QuotaExceededException ex, HttpServletRequest request) {
        log.warn("Acceso denegado por cuota: {}", ex.getMessage());

        if (isHtmxRequest(request)) {
            ModelAndView fragment = new ModelAndView(QUOTA_FRAGMENT);
            fragment.setStatus(HttpStatus.OK);
            fragment.addObject("quotaException", ex);
            fragment.addObject("contactEmail", quotaService.getContactEmail());
            return fragment;
        }

        return errorView(request, HttpStatus.TOO_MANY_REQUESTS,
                "Límite alcanzado",
                ex.getUserMessage(),
                "Para solicitar más análisis, escribe a " + quotaService.getContactEmail() + ".");
    }

    /**
     * Verificación anti-bots no superada (token ausente, caducado o rechazado).
     *
     * @param ex      excepción de verificación
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(CaptchaException.class)
    public ModelAndView handleCaptcha(CaptchaException ex, HttpServletRequest request) {
        log.warn("Verificación anti-bots rechazada: {}", ex.getMessage());
        return errorView(request, HttpStatus.BAD_REQUEST,
                ex.getTitle(),
                ex.getUserMessage(),
                "Si el problema persiste, escribe a " + quotaService.getContactEmail() + ".");
    }

    /**
     * Archivo por encima del límite de 10 MB.
     *
     * @param ex      excepción de tamaño
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        log.warn("Archivo rechazado por tamaño: {}", ex.getMessage());
        return errorView(request, HttpStatus.PAYLOAD_TOO_LARGE,
                "Archivo demasiado grande",
                "El archivo supera el límite de 10 MB. Divídelo en partes más pequeñas o pega "
                        + "el texto directamente en el formulario.",
                null);
    }

    /**
     * Petición multiparte mal formada (por ejemplo, una subida interrumpida).
     *
     * @param ex      excepción de multipart
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(MultipartException.class)
    public ModelAndView handleMultipart(MultipartException ex, HttpServletRequest request) {
        log.warn("Petición multiparte inválida: {}", ex.getMessage());
        return errorView(request, HttpStatus.BAD_REQUEST,
                "No se pudo leer el archivo",
                "La subida del archivo no se completó correctamente. Vuelve a intentarlo.",
                null);
    }

    /**
     * Problemas al extraer el texto (formato no soportado, documento ilegible, texto demasiado corto).
     *
     * @param ex      excepción de extracción
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(TextExtractionException.class)
    public ModelAndView handleTextExtraction(TextExtractionException ex, HttpServletRequest request) {
        log.warn("Error de extracción ({}): {}", ex.getReason(), ex.getMessage());

        String title = switch (ex.getReason()) {
            case FILE_TOO_LARGE -> "Archivo demasiado grande";
            case UNSUPPORTED_FORMAT -> "Formato no soportado";
            case TEXT_TOO_SHORT -> "Texto demasiado corto";
            case EMPTY_INPUT -> "No hay nada que analizar";
            case UNREADABLE_FILE -> "No se pudo leer el documento";
        };

        return errorView(request, HttpStatus.BAD_REQUEST, title, ex.getMessage(),
                "Formatos admitidos: PDF, DOCX y TXT, con un máximo de 10 MB.");
    }

    /**
     * Fallo del análisis con el modelo de lenguaje o análisis inexistente.
     *
     * @param ex      excepción de análisis
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(AnalysisException.class)
    public ModelAndView handleAnalysis(AnalysisException ex, HttpServletRequest request) {
        if (ex.isNotFound()) {
            log.warn("Análisis no encontrado: {}", request.getRequestURI());
            return errorView(request, HttpStatus.NOT_FOUND,
                    "Análisis no encontrado",
                    ex.getMessage(),
                    "Vuelve al inicio y analiza el documento de nuevo.");
        }

        log.error("Error de análisis: {}", ex.getMessage());
        return errorView(request, HttpStatus.BAD_GATEWAY,
                "No se pudo completar el análisis",
                ex.getMessage(),
                "Si el problema persiste, comprueba la configuración de DEEPSEEK_API_KEY o "
                        + "escribe a " + quotaService.getContactEmail() + ".");
    }

    /**
     * Recurso no encontrado (URL mal escrita o análisis caducado).
     *
     * @param ex      excepción de recurso no encontrado
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ModelAndView handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("Recurso no encontrado: {}", request.getRequestURI());
        return errorView(request, HttpStatus.NOT_FOUND,
                "Página no encontrada",
                "La dirección a la que intentas acceder no existe.",
                null);
    }

    /**
     * Cualquier error no contemplado. Se registra completo en el log para diagnóstico, pero
     * al usuario solo se le muestra un mensaje genérico.
     *
     * @param ex      excepción inesperada
     * @param request petición en curso
     * @return la vista o el fragmento de error
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error inesperado en {}", request.getRequestURI(), ex);
        return errorView(request, HttpStatus.INTERNAL_SERVER_ERROR,
                "Algo ha ido mal",
                "Se ha producido un error inesperado al procesar tu solicitud.",
                "Vuelve a intentarlo en unos instantes. Si el problema continúa, escribe a "
                        + quotaService.getContactEmail() + ".");
    }

    private boolean isHtmxRequest(HttpServletRequest request) {
        return request.getHeader(HTMX_REQUEST_HEADER) != null;
    }

    private ModelAndView errorView(HttpServletRequest request, HttpStatus status,
                                   String title, String message, String detail) {
        boolean htmx = isHtmxRequest(request);

        ModelAndView modelAndView = new ModelAndView(htmx ? ERROR_FRAGMENT : ERROR_VIEW);
        // HTMX solo sustituye contenido si la respuesta es correcta.
        modelAndView.setStatus(htmx ? HttpStatus.OK : status);
        modelAndView.addObject("statusCode", status.value());
        modelAndView.addObject("title", title);
        modelAndView.addObject("message", message);
        modelAndView.addObject("detail", detail);
        modelAndView.addObject("contactEmail", quotaService.getContactEmail());
        return modelAndView;
    }
}
