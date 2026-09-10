package com.textorigin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.textorigin.model.SuspiciousFragment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Fragmento sospechoso tal y como lo devuelve el modelo dentro de {@link SegmentResultDto}.
 *
 * <p>Sigue el mismo patrón defensivo que {@link SegmentResultDto}: la respuesta de un modelo de
 * lenguaje nunca es totalmente fiable, así que aquí se recortan las citas demasiado largas, se
 * descartan las que no traen texto y se validan los niveles contra el vocabulario permitido.
 * Las citas se muestran al profesorado tal cual llegaron, sin verificar que sean literales: el
 * prompt lo pide, pero el modelo puede parafrasear.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = SuspiciousFragmentDto.LenientDeserializer.class)
public class SuspiciousFragmentDto {

    /** Longitud máxima de una cita; las más largas se recortan con puntos suspensivos. */
    private static final int MAX_TEXT_LENGTH = 300;

    /** Longitud máxima del motivo señalado por el modelo. */
    private static final int MAX_REASON_LENGTH = 240;

    /** Motivo de reserva cuando el modelo no explica la sospecha. */
    static final String DEFAULT_REASON = "Patrón lingüístico señalado por el modelo en este fragmento.";

    /** Valores admitidos para el nivel de sospecha. */
    private static final Set<String> ALLOWED_LEVELS = Set.of("bajo", "medio", "alto");

    /** Cita textual del segmento. */
    private String text;

    /** Motivo por el que el fragmento resulta sospechoso. */
    private String reason;

    /** Nivel de sospecha: {@code bajo}, {@code medio} o {@code alto}. */
    private String level;

    /** Cita saneada, o {@code null} si no es utilizable (vacía, solo espacios o el literal "null"). */
    public String normalizedText() {
        String value = clean(text);
        if (value == null) {
            return null;
        }
        return truncate(stripWrappingQuotes(value), MAX_TEXT_LENGTH);
    }

    /** Motivo saneado, con un texto de reserva si el modelo no lo proporcionó. */
    public String normalizedReason() {
        String value = clean(reason);
        if (value == null) {
            return DEFAULT_REASON;
        }
        return truncate(value, MAX_REASON_LENGTH);
    }

    /** Nivel validado, o {@code null} si no es un valor admitido. */
    public String normalizedLevel() {
        String value = clean(level);
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return ALLOWED_LEVELS.contains(normalized) ? normalized : null;
    }

    /** Indica si el fragmento tiene una cita utilizable. */
    public boolean isUsable() {
        return normalizedText() != null;
    }

    /**
     * Convierte el fragmento en el modelo de dominio aplicando la normalización.
     *
     * @param segmentIndex índice del segmento del documento al que pertenece la cita
     * @param index        posición del fragmento dentro del segmento
     * @return el fragmento listo para almacenar
     */
    public SuspiciousFragment toFragment(int segmentIndex, int index) {
        return SuspiciousFragment.builder()
                .index(index)
                .segmentIndex(segmentIndex)
                .text(normalizedText())
                .reason(normalizedReason())
                .level(normalizedLevel())
                .build();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() || "null".equalsIgnoreCase(clean) ? null : clean;
    }

    /** Quita las comillas que el modelo añade alrededor de la cita, para no duplicarlas al mostrarla. */
    private static String stripWrappingQuotes(String value) {
        if (value.length() < 2) {
            return value;
        }
        String first = value.substring(0, 1);
        String last = value.substring(value.length() - 1);
        if (("\"".equals(first) && "\"".equals(last))
                || ("“".equals(first) && "”".equals(last))
                || ("«".equals(first) && "»".equals(last))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).stripTrailing() + "…";
    }

    /**
     * Deserializador tolerante: acepta el objeto esperado y también una cita suelta en forma de
     * cadena, de modo que una respuesta con el formato ligeramente torcido no invalide el
     * análisis completo del segmento.
     */
    public static class LenientDeserializer extends JsonDeserializer<SuspiciousFragmentDto> {

        @Override
        public SuspiciousFragmentDto deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.readValueAsTree();
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isTextual()) {
                return SuspiciousFragmentDto.builder().text(node.asText()).build();
            }
            if (node.isObject()) {
                return SuspiciousFragmentDto.builder()
                        .text(textOf(node, "text"))
                        .reason(textOf(node, "reason"))
                        .level(textOf(node, "level"))
                        .build();
            }
            return null;
        }

        private static String textOf(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }
    }
}
