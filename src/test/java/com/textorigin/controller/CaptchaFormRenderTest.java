package com.textorigin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verificación anti-bots en el formulario: con el par de claves configurado la portada pinta
 * el widget de Turnstile y un envío sin token se rechaza antes de comprobar la cuota y de
 * llamar a DeepSeek.
 *
 * <p>Se usan las claves de prueba de Cloudflare (siempre dejan pasar) para no depender de una
 * cuenta real; ningún caso de este test llega a consultar {@code siteverify}.</p>
 */
@SpringBootTest(properties = {
        "textorigin.captcha.site-key=1x00000000000000000000AA",
        "textorigin.captcha.secret-key=1x0000000000000000000000000000000AA"
})
@AutoConfigureMockMvc
class CaptchaFormRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void laPortadaPintaElWidgetCuandoHayClaves() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cf-turnstile")))
                .andExpect(content().string(containsString("1x00000000000000000000AA")))
                .andExpect(content().string(
                        containsString("challenges.cloudflare.com/turnstile/v0/api.js")))
                .andExpect(content().string(containsString("/js/captcha.js")));
    }

    @Test
    void unEnvioSinTokenSeRechazaComoFragmentoDeError() throws Exception {
        mockMvc.perform(multipart("/analysis/analyze")
                        .param("text", "Un texto de prueba que supera la longitud mínima exigida.")
                        .header("HX-Request", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Verificación de seguridad")))
                .andExpect(content().string(containsString("Marca la casilla de verificación")));
    }

    @Test
    void unEnvioSinTokenSinHtmxDevuelveLaPaginaDeError() throws Exception {
        mockMvc.perform(multipart("/analysis/analyze")
                        .param("text", "Un texto de prueba que supera la longitud mínima exigida."))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Verificación de seguridad")));
    }

    @Test
    void elScriptDelCaptchaSeSirve() throws Exception {
        String script = mockMvc.perform(get("/js/captcha.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(script).contains("turnstile.reset");
    }
}
