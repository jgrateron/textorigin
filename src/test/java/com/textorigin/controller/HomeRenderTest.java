package com.textorigin.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renderizado de la página principal: formulario, indicador de cuota diaria y los recursos
 * que mantienen desactivado el botón de envío mientras hay un análisis en curso.
 *
 * <p>El CAPTCHA se desactiva explícitamente: sin claves configuradas el widget no debe
 * pintarse. La portada con claves la cubre {@link CaptchaFormRenderTest}.</p>
 */
@SpringBootTest(properties = "textorigin.captcha.enabled=false")
@AutoConfigureMockMvc
class HomeRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void laPortadaMuestraElFormularioYElLimiteDiario() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"analysis-form\"")))
                .andExpect(content().string(containsString("hx-disabled-elt")))
                .andExpect(content().string(containsString("Límite diario de tu conexión")))
                .andExpect(content().string(containsString("/js/analysis-state.js")));
    }

    @Test
    void sinCaptchaConfiguradoNoSePintaElWidgetNiSeCargaSuScript() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("class=\"cf-turnstile\""))))
                .andExpect(content().string(not(containsString("challenges.cloudflare.com"))));
    }

    @Test
    void elScriptDelEstadoDelFormularioSeSirve() throws Exception {
        String script = mockMvc.perform(get("/js/analysis-state.js"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(script).contains("htmx:afterRequest");
    }
}
