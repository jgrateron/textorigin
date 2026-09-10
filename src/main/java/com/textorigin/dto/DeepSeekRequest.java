package com.textorigin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Petición de chat completion en el formato compatible con OpenAI que expone DeepSeek.
 *
 * <p>Se utiliza en la ruta HTTP directa de {@code DeepSeekAnalysisService}, que actúa como
 * alternativa cuando el cliente de Spring AI no está disponible (por ejemplo, si no hay
 * ninguna clave de API configurada en el arranque). El payload es idéntico al que enviaría
 * el SDK oficial:</p>
 *
 * <pre>
 * POST https://api.deepseek.com/v1/chat/completions
 * { "model": "deepseek-chat", "messages": [...], "temperature": 0.2 }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeepSeekRequest {

    /** Identificador del modelo, por ejemplo {@code deepseek-chat}. */
    private String model;

    /** Conversación enviada al modelo. */
    private List<Message> messages;

    /** Temperatura de muestreo. Valores bajos hacen la respuesta más determinista. */
    private Double temperature;

    /** Si la respuesta debe emitirse en streaming. TextOrigin siempre usa {@code false}. */
    private Boolean stream;

    /** Formato de respuesta solicitado, por ejemplo {@code {"type":"json_object"}}. */
    @JsonProperty("response_format")
    private Map<String, Object> responseFormat;

    /** Número máximo de tokens de la respuesta. */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** Un mensaje de la conversación. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {

        /** Rol del emisor: {@code system}, {@code user} o {@code assistant}. */
        private String role;

        /** Contenido textual del mensaje. */
        private String content;

        /** Construye un mensaje de sistema. */
        public static Message system(String content) {
            return Message.builder().role("system").content(content).build();
        }

        /** Construye un mensaje de usuario. */
        public static Message user(String content) {
            return Message.builder().role("user").content(content).build();
        }
    }

    /**
     * Construye la petición de análisis de un segmento concreto.
     *
     * @param model       modelo a invocar
     * @param prompt      prompt completo, ya sustituido el texto del segmento
     * @param temperature temperatura de muestreo
     * @return la petición lista para serializar
     */
    public static DeepSeekRequest forPrompt(String model, String prompt, Double temperature) {
        return DeepSeekRequest.builder()
                .model(model)
                .messages(List.of(Message.user(prompt)))
                .temperature(temperature)
                .stream(false)
                .responseFormat(Map.of("type", "json_object"))
                .build();
    }
}
