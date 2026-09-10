package com.textorigin.service;

import com.textorigin.model.Segment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide un documento en segmentos analizables.
 *
 * <p>La unidad natural de análisis es el párrafo (separado por una línea en blanco), porque
 * es la unidad con la que trabaja el profesorado y porque permite resaltar el resultado
 * párrafo a párrafo. Sobre esa base se aplican tres correcciones:</p>
 *
 * <ol>
 *   <li><strong>Fusión de párrafos muy cortos</strong> (títulos sueltos, líneas de cortesía):
 *       analizarlos por separado daría resultados ruidosos y gastaría tokens sin aportar nada.</li>
 *   <li><strong>División de párrafos muy largos</strong> en fragmentos de tamaño manejable,
 *       respetando los límites de frase.</li>
 *   <li><strong>Agrupación</strong> cuando el número de fragmentos supera
 *       {@code textorigin.analysis.max-segments}: se reparten en ese número máximo de grupos
 *       equilibrados para no perder texto ni disparar el consumo de tokens.</li>
 * </ol>
 */
@Slf4j
@Service
public class TextSegmentationService {

    /** Los fragmentos por debajo de esta longitud se fusionan con su vecino. */
    private static final int MIN_PARAGRAPH_CHARS = 80;

    /** Los fragmentos por encima de esta longitud se dividen por frases. */
    private static final int MAX_PARAGRAPH_CHARS = 1600;

    /** Separación entre párrafos en el texto normalizado. */
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    /** Número máximo de segmentos que se enviarán al modelo. */
    private final int maxSegments;

    public TextSegmentationService(@Value("${textorigin.analysis.max-segments:50}") int maxSegments) {
        this.maxSegments = maxSegments;
    }

    /**
     * Segmenta un texto normalizado.
     *
     * @param text texto normalizado del documento
     * @return lista de segmentos en orden, con sus desplazamientos y recuentos
     */
    public List<Segment> segment(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String raw : text.split("\n\\s*\n")) {
            String paragraph = cleanParagraph(raw);
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
        }

        if (paragraphs.isEmpty()) {
            log.warn("El texto no contiene párrafos tras la normalización");
            return List.of();
        }

        paragraphs = splitLongParagraphs(mergeTinyParagraphs(paragraphs));

        if (paragraphs.size() > maxSegments) {
            log.info("El documento tiene {} fragmentos; se agrupan en {} segmentos para acotar el consumo",
                    paragraphs.size(), maxSegments);
            paragraphs = groupParagraphs(paragraphs, maxSegments);
        }

        List<Segment> segments = buildSegments(paragraphs, text);
        log.debug("Texto segmentado en {} segmentos ({} caracteres)", segments.size(), text.length());
        return segments;
    }

    /** Cuenta las palabras de un texto. */
    public static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }

    // ==================================================================
    // Preparación de párrafos
    // ==================================================================

    /**
     * Limpia un párrafo: convierte los saltos de línea internos (propios del ajuste de línea
     * de PDF y DOCX) en espacios y colapsa los espacios repetidos.
     *
     * <p>La comparte {@link BibliographyDetector} para interpretar los párrafos igual que la
     * segmentación.</p>
     */
    public static String cleanParagraph(String raw) {
        return raw.replaceAll("[ \\t]*\\n[ \\t]*", " ")
                .replaceAll(" {2,}", " ")
                .strip();
    }

    /** Fusiona los fragmentos demasiado cortos con el párrafo anterior. */
    private List<String> mergeTinyParagraphs(List<String> paragraphs) {
        List<String> merged = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).length() < MIN_PARAGRAPH_CHARS) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + paragraph);
            } else {
                merged.add(paragraph);
            }
        }
        // Si el último fragmento se quedó corto y hay más de uno, se une al anterior.
        if (merged.size() > 1 && merged.get(merged.size() - 1).length() < MIN_PARAGRAPH_CHARS) {
            String last = merged.remove(merged.size() - 1);
            merged.set(merged.size() - 1, merged.get(merged.size() - 1) + " " + last);
        }
        return merged;
    }

    /** Divide los párrafos que exceden el tamaño máximo, respetando los límites de frase. */
    private List<String> splitLongParagraphs(List<String> paragraphs) {
        List<String> result = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= MAX_PARAGRAPH_CHARS) {
                result.add(paragraph);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String sentence : paragraph.split("(?<=[.!?…])\\s+")) {
                if (current.length() > 0 && current.length() + sentence.length() > MAX_PARAGRAPH_CHARS) {
                    result.add(current.toString().strip());
                    current.setLength(0);
                }
                if (sentence.length() > MAX_PARAGRAPH_CHARS) {
                    result.addAll(hardSplit(sentence));
                    continue;
                }
                current.append(sentence).append(' ');
            }
            if (current.length() > 0) {
                result.add(current.toString().strip());
            }
        }
        return result;
    }

    /** Último recurso para frases sin puntuación: corte por el espacio más cercano. */
    private List<String> hardSplit(String sentence) {
        List<String> parts = new ArrayList<>();
        String remaining = sentence;
        while (remaining.length() > MAX_PARAGRAPH_CHARS) {
            int cut = remaining.lastIndexOf(' ', MAX_PARAGRAPH_CHARS);
            if (cut <= 0) {
                cut = MAX_PARAGRAPH_CHARS;
            }
            parts.add(remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
        }
        if (!remaining.isEmpty()) {
            parts.add(remaining);
        }
        return parts;
    }

    /**
     * Reparte los fragmentos en {@code groups} bloques equilibrados conservando el orden.
     * Se usa cuando el documento supera el máximo de segmentos configurado.
     */
    private List<String> groupParagraphs(List<String> paragraphs, int groups) {
        int total = paragraphs.size();
        int baseSize = total / groups;
        int remainder = total % groups;

        List<String> grouped = new ArrayList<>(groups);
        int cursor = 0;
        for (int i = 0; i < groups; i++) {
            int size = baseSize + (i < remainder ? 1 : 0);
            StringBuilder group = new StringBuilder();
            for (int j = 0; j < size; j++) {
                if (group.length() > 0) {
                    group.append(PARAGRAPH_SEPARATOR);
                }
                group.append(paragraphs.get(cursor++));
            }
            grouped.add(group.toString());
        }
        return grouped;
    }

    /** Construye los segmentos calculando su posición dentro del texto original. */
    private List<Segment> buildSegments(List<String> paragraphs, String text) {
        List<Segment> segments = new ArrayList<>(paragraphs.size());
        int searchFrom = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            String paragraph = paragraphs.get(i);
            int start = locate(paragraph, text, searchFrom);
            int end = start < 0 ? -1 : start + paragraph.length();
            if (start >= 0) {
                searchFrom = end;
            }

            segments.add(Segment.builder()
                    .index(i)
                    .text(paragraph)
                    .startOffset(Math.max(start, 0))
                    .endOffset(Math.max(end, 0))
                    .characterCount(paragraph.length())
                    .wordCount(countWords(paragraph))
                    .mergedParagraphs(countMerged(paragraph))
                    .build());
        }
        return segments;
    }

    /** Busca el fragmento en el texto; si se limpió el formato interno, cae al primer fragmento. */
    private int locate(String paragraph, String text, int searchFrom) {
        int position = text.indexOf(paragraph, searchFrom);
        if (position >= 0) {
            return position;
        }
        String probe = paragraph.length() > 60 ? paragraph.substring(0, 60) : paragraph;
        return text.indexOf(probe, searchFrom);
    }

    private int countMerged(String paragraph) {
        return paragraph.split(PARAGRAPH_SEPARATOR, -1).length;
    }
}
