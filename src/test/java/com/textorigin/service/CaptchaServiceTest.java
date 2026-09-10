package com.textorigin.service;

import com.textorigin.exception.CaptchaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verificación de tokens de Cloudflare Turnstile contra el endpoint {@code siteverify}, con
 * el servidor de Cloudflare simulado: éxito, rechazo, token ausente y la degradación
 * deliberada (fail-open) cuando no se puede consultar.
 */
class CaptchaServiceTest {

    private static final String VERIFY_URL =
            "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final String SITE_KEY = "1x00000000000000000000AA";
    private static final String SECRET_KEY = "0x4AAAAAAATEST-secret";
    private static final String TOKEN = "token-de-prueba";
    private static final String CLIENT_IP = "203.0.113.7";

    private MockRestServiceServer server;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        // El constructor de RestClient se comparte con el servidor simulado: así el servicio
        // cree hablar con Cloudflare cuando en realidad responde la prueba.
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private CaptchaService service(boolean enabled, String siteKey, String secretKey) {
        return new CaptchaService(enabled, siteKey, secretKey, VERIFY_URL, builder.build());
    }

    private CaptchaService activeService() {
        return service(true, SITE_KEY, SECRET_KEY);
    }

    @Test
    void unTokenValidoSuperaLaVerificacion() {
        server.expect(requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("secret=" + SECRET_KEY)))
                .andExpect(content().string(containsString("response=" + TOKEN)))
                .andExpect(content().string(containsString("remoteip=" + CLIENT_IP)))
                .andRespond(withSuccess("{\"success\": true}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> activeService().verify(TOKEN, CLIENT_IP)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void unTokenRechazadoPorCloudflareLanzaExcepcion() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess(
                        "{\"success\": false, \"error-codes\": [\"invalid-input-response\"]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> activeService().verify(TOKEN, CLIENT_IP))
                .isInstanceOf(CaptchaException.class)
                .hasMessageContaining("invalid-input-response");
        server.verify();
    }

    @Test
    void sinTokenLanzaExcepcionSinConsultarCloudflare() {
        assertThatThrownBy(() -> activeService().verify(null, CLIENT_IP))
                .isInstanceOf(CaptchaException.class)
                .hasMessageContaining("sin token");
        assertThatThrownBy(() -> activeService().verify("   ", CLIENT_IP))
                .isInstanceOf(CaptchaException.class);
        server.verify();
    }

    @Test
    void unErrorDelServidorDeCloudflareDejaPasarSinToken() {
        server.expect(requestTo(VERIFY_URL)).andRespond(withServerError());

        assertThatCode(() -> activeService().verify(TOKEN, CLIENT_IP)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void unFalloDeRedDejaPasar() {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withException(new IOException("tiempo de espera agotado")));

        assertThatCode(() -> activeService().verify(TOKEN, CLIENT_IP)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void desactivadoPorConfiguracionDejaPasarSinConsultar() {
        CaptchaService disabled = service(false, SITE_KEY, SECRET_KEY);

        assertThat(disabled.isEnabled()).isFalse();
        assertThatCode(() -> disabled.verify(TOKEN, CLIENT_IP)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void sinParDeClavesDejaPasarSinConsultar() {
        CaptchaService sinClaves = service(true, "", SECRET_KEY);

        assertThat(sinClaves.isEnabled()).isFalse();
        assertThatCode(() -> sinClaves.verify(TOKEN, CLIENT_IP)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void laIpDesconocidaNoSeEnviaACloudflare() {
        server.expect(requestTo(VERIFY_URL))
                .andExpect(content().string(not(containsString("remoteip"))))
                .andRespond(withSuccess("{\"success\": true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(VERIFY_URL))
                .andExpect(content().string(not(containsString("remoteip"))))
                .andRespond(withSuccess("{\"success\": true}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> activeService().verify(TOKEN, "desconocida"))
                .doesNotThrowAnyException();
        assertThatCode(() -> activeService().verify(TOKEN, null)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void laClavePublicaSeExponeParaLaPlantilla() {
        assertThat(activeService().getSiteKey()).isEqualTo(SITE_KEY);
    }
}
