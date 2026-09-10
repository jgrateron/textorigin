package com.textorigin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Petición HTTP directa: el mensaje de sistema lleva las instrucciones y el de usuario el texto
 * del documento, que es la separación en la que se apoya la defensa anti prompt-injection.
 */
class DeepSeekRequestTest {

    @Test
    void laPeticionIncluyeElMensajeDeSistemaAntesDelTexto() {
        DeepSeekRequest request = DeepSeekRequest.forPrompt(
                "deepseek-flash", "Eres un analista experto.", "Analiza este texto.", 0.2);

        assertThat(request.getMessages()).hasSize(2);
        assertThat(request.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(request.getMessages().get(0).getContent()).isEqualTo("Eres un analista experto.");
        assertThat(request.getMessages().get(1).getRole()).isEqualTo("user");
        assertThat(request.getMessages().get(1).getContent()).isEqualTo("Analiza este texto.");
        assertThat(request.getResponseFormat()).containsEntry("type", "json_object");
        assertThat(request.getStream()).isFalse();
        assertThat(request.getTemperature()).isEqualTo(0.2);
        assertThat(request.getThinking()).containsEntry("type", "disabled");
    }

    @Test
    void sinPromptDeSistemaSoloEnviaElMensajeDeUsuario() {
        assertThat(DeepSeekRequest.forPrompt("deepseek-flash", "solo texto", 0.2).getMessages())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getRole()).isEqualTo("user");
                    assertThat(message.getContent()).isEqualTo("solo texto");
                });

        assertThat(DeepSeekRequest.forPrompt("deepseek-flash", "   ", "solo texto", 0.2).getMessages())
                .hasSize(1);
    }

    @Test
    void elJsonEnviaElModoDeRazonamientoDesactivadoYElFormatoJson() throws Exception {
        DeepSeekRequest request = DeepSeekRequest.forPrompt(
                "deepseek-flash", "Eres un analista experto.", "Analiza este texto.", 0.2);

        String json = new ObjectMapper().writeValueAsString(request);

        assertThat(json).contains("\"thinking\":{\"type\":\"disabled\"}");
        assertThat(json).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(json).contains("\"stream\":false");
    }
}
