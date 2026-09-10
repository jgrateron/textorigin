package com.textorigin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Respuesta de chat completion en el formato compatible con OpenAI devuelta por DeepSeek.
 *
 * <p>Únicamente se modelan los campos que utiliza TextOrigin: el contenido del primer
 * {@code choice} y el consumo de tokens, que se registra en el log para poder estimar
 * el gasto asociado a cada análisis.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepSeekResponse {

    /** Identificador de la completion. */
    private String id;

    /** Tipo de objeto devuelto ({@code chat.completion}). */
    private String object;

    /** Marca temporal de creación (epoch en segundos). */
    private Long created;

    /** Modelo que ha generado la respuesta. */
    private String model;

    /** Alternativas generadas. TextOrigin trabaja con la primera. */
    private List<Choice> choices;

    /** Consumo de tokens de la llamada. */
    private Usage usage;

    /** Una alternativa de respuesta. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        /** Posición de la alternativa dentro de la lista. */
        private Integer index;

        /** Mensaje generado. */
        private Message message;

        /** Motivo por el que el modelo dejó de generar. */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /** Mensaje devuelto por el modelo. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        /** Rol del emisor, normalmente {@code assistant}. */
        private String role;

        /** Contenido textual generado. */
        private String content;
    }

    /** Consumo de tokens de la llamada. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        /** Tokens del prompt enviado. */
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;

        /** Tokens generados en la respuesta. */
        @JsonProperty("completion_tokens")
        private Integer completionTokens;

        /** Suma de tokens de prompt y respuesta. */
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }

    /** Indica si la respuesta trae contenido utilizable. */
    public boolean hasContent() {
        return firstContent() != null && !firstContent().isBlank();
    }

    /** Contenido del primer {@code choice}, o {@code null} si no hay ninguno. */
    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.get(0) == null) {
            return null;
        }
        Message message = choices.get(0).getMessage();
        return message == null ? null : message.getContent();
    }

    /** Motivo de finalización del primer {@code choice}. */
    public String firstFinishReason() {
        if (choices == null || choices.isEmpty() || choices.get(0) == null) {
            return null;
        }
        return choices.get(0).getFinishReason();
    }

    /** Total de tokens consumidos, o 0 si la respuesta no lo indica. */
    public int totalTokens() {
        if (usage == null || usage.getTotalTokens() == null) {
            return 0;
        }
        return usage.getTotalTokens();
    }
}
