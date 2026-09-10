package com.textorigin.service;

import com.textorigin.dto.TurnstileResponse;
import com.textorigin.exception.CaptchaException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verificación anti-bots del formulario con Cloudflare Turnstile.
 *
 * <p>El widget de la portada entrega un token de un solo uso en el campo
 * {@value #TOKEN_FIELD} del formulario; aquí se canjea contra el endpoint {@code siteverify}
 * de Cloudflare, que es la única comprobación fiable (el widget del navegador es
 * decorativo: cualquiera puede enviar el POST a mano).</p>
 *
 * <p><strong>Cuándo se aplica:</strong> solo si {@code textorigin.captcha.enabled} es
 * {@code true} y hay par de claves ({@code TURNSTILE_SITE_KEY} y
 * {@code TURNSTILE_SECRET_KEY}). Sin claves la aplicación arranca igualmente —misma
 * convención que {@code DEEPSEEK_API_KEY}—, avisa por log y el formulario queda sin
 * verificación, protegido únicamente por la cuota diaria por IP.</p>
 *
 * <p><strong>Degradación:</strong> si Cloudflare responde con error o no se puede contactar,
 * la petición continúa con un aviso en el log (y el fallo queda diagnosticable). Rechazar el
 * análisis porque un servicio ajeno esté caído dejaría la aplicación inutilizable; el límite
 * diario por IP sigue conteniendo el abuso. El token ausente o rechazado por Cloudflare sí
 * corta el envío.</p>
 */
@Slf4j
@Service
public class CaptchaService {

    /** Nombre del campo que Turnstile inyecta en el formulario con el token del visitante. */
    public static final String TOKEN_FIELD = "cf-turnstile-response";

    /** IP de relleno de {@code QuotaService} cuando no se puede determinar la del cliente. */
    private static final String UNKNOWN_IP = "desconocida";

    private final boolean enabled;
    private final String siteKey;
    private final String secretKey;
    private final String verifyUrl;
    private final RestClient captchaRestClient;

    public CaptchaService(@Value("${textorigin.captcha.enabled:true}") boolean enabled,
                          @Value("${textorigin.captcha.site-key:}") String siteKey,
                          @Value("${textorigin.captcha.secret-key:}") String secretKey,
                          @Value("${textorigin.captcha.verify-url:"
                                  + "https://challenges.cloudflare.com/turnstile/v0/siteverify}")
                          String verifyUrl,
                          @Qualifier("captchaRestClient") RestClient captchaRestClient) {
        this.enabled = enabled;
        this.siteKey = siteKey == null ? "" : siteKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.verifyUrl = verifyUrl;
        this.captchaRestClient = captchaRestClient;
        logConfiguration();
    }

    /**
     * Indica si la verificación está operativa: activada por configuración y con el par de
     * claves del widget. Es lo que decide si la plantilla pinta el widget y si los envíos se
     * comprueban.
     *
     * @return {@code true} si el CAPTCHA está activo y configurado
     */
    public boolean isEnabled() {
        return enabled && !siteKey.isEmpty() && !secretKey.isEmpty();
    }

    /**
     * Clave pública del widget, que la plantilla de la portada pinta en el HTML.
     *
     * @return la clave configurada en {@code textorigin.captcha.site-key}
     */
    public String getSiteKey() {
        return siteKey;
    }

    /**
     * Verifica el token de Turnstile de un envío. Debe invocarse antes de comprobar la cuota
     * y de extraer el texto, para que un envío automatizado no consuma ni una cosa ni la otra.
     *
     * @param token    valor del campo {@value #TOKEN_FIELD} del formulario
     * @param clientIp IP del cliente, que se envía a Cloudflare como {@code remoteip} para
     *                 que pueda correlacionar el reto con la conexión
     * @throws CaptchaException si falta el token o Cloudflare lo rechaza
     */
    public void verify(String token, String clientIp) {
        if (!isEnabled()) {
            log.debug("Verificación anti-bots desactivada: la petición continúa sin CAPTCHA");
            return;
        }
        if (token == null || token.isBlank()) {
            throw new CaptchaException("Petición sin token de CAPTCHA");
        }

        TurnstileResponse response = querySiteverify(token.trim(), clientIp);
        if (response == null) {
            log.warn("Verificación anti-bots no disponible: la petición continúa sin comprobar "
                    + "(la cuota diaria sigue aplicándose). ip={}", clientIp);
            return;
        }
        if (!response.isSuccess()) {
            log.warn("CAPTCHA rechazado por Cloudflare: ip={} códigos={}",
                    clientIp, response.getErrorCodes());
            throw new CaptchaException("Cloudflare rechaza el token: " + response.getErrorCodes());
        }

        log.debug("CAPTCHA superado: ip={}", clientIp);
    }

    /**
     * Consulta el endpoint {@code siteverify}.
     *
     * @param token    token de Turnstile ya validado como no vacío
     * @param clientIp IP del cliente
     * @return la respuesta de Cloudflare, o {@code null} si no ha sido posible consultarla
     */
    private TurnstileResponse querySiteverify(String token, String clientIp) {
        try {
            TurnstileResponse response = captchaRestClient.post()
                    .uri(verifyUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody(token, clientIp))
                    .retrieve()
                    .body(TurnstileResponse.class);

            if (response == null) {
                log.warn("Cloudflare ha respondido sin cuerpo a la verificación anti-bots");
            }
            return response;
        } catch (RestClientException e) {
            log.warn("Error al consultar la verificación anti-bots: {}", e.getMessage());
            return null;
        }
    }

    /** Cuerpo del {@code siteverify}: la clave secreta, el token y, si se conoce, la IP. */
    private MultiValueMap<String, String> formBody(String token, String clientIp) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("secret", secretKey);
        body.add("response", token);
        if (clientIp != null && !clientIp.isBlank() && !UNKNOWN_IP.equals(clientIp)) {
            body.add("remoteip", clientIp);
        }
        return body;
    }

    /** Deja en el log el estado de la verificación al arrancar, como hace DeepSeek. */
    private void logConfiguration() {
        if (!enabled) {
            log.info("Verificación anti-bots desactivada por configuración "
                    + "(textorigin.captcha.enabled=false)");
        } else if (isEnabled()) {
            log.info("Verificación anti-bots activa: Cloudflare Turnstile ({})", verifyUrl);
        } else {
            log.warn("================================================================");
            log.warn("CAPTCHA no configurado: faltan TURNSTILE_SITE_KEY y/o TURNSTILE_SECRET_KEY.");
            log.warn("El formulario funcionará sin verificación anti-bots; el límite diario");
            log.warn("por IP sigue aplicándose mientras tanto.");
            log.warn("================================================================");
        }
    }
}
