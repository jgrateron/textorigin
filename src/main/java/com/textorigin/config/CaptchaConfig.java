package com.textorigin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Cliente HTTP para la verificación anti-bots de Cloudflare Turnstile.
 *
 * <p>La consulta a {@code siteverify} ocurre dentro del hilo de una petición del usuario, así
 * que el cliente se construye a mano con tiempos de espera cortos: si Cloudflare no responde
 * pronto, es preferible dejar pasar la petición con un aviso (la cuota diaria por IP sigue
 * aplicándose) que mantener el formulario bloqueado.</p>
 */
@Slf4j
@Configuration
public class CaptchaConfig {

    /** Tiempo máximo para establecer la conexión con Cloudflare. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** Tiempo máximo de espera de la respuesta de Cloudflare. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Cliente usado por {@code CaptchaService} para consultar el endpoint {@code siteverify}.
     *
     * @param builder constructor de {@link RestClient} que aporta Spring Boot
     * @return el cliente con los tiempos de espera configurados
     */
    @Bean
    public RestClient captchaRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        log.debug("Cliente de verificación anti-bots configurado (conexión {} ms, espera {} ms)",
                CONNECT_TIMEOUT.toMillis(), READ_TIMEOUT.toMillis());
        return builder.requestFactory(requestFactory).build();
    }
}
