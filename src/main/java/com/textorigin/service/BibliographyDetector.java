package com.textorigin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Excluye del análisis la sección bibliográfica final de un documento.
 *
 * <p>Las listas de referencias tienen una estructura muy regular —entradas uniformes, sin
 * variación en la longitud de las frases ni marcas de estilo personal—, que es justo el patrón
 * que el analizador asocia a texto generado. Analizarlas produce falsos positivos y gasta
 * tokens sin aportar nada, así que se recortan del texto antes de segmentarlo.</p>
 *
 * <p>La detección es deliberadamente conservadora:</p>
 * <ul>
 *   <li>Solo reconoce encabezados que ocupan el párrafo completo y son cortos, como
 *       «Referencias», «7. Bibliografía», «Obras citadas» o «Works cited».</li>
 *   <li>No recorta nada si el encabezado abre el documento (un texto que es solo una
 *       bibliografía se analiza entero) ni si al recortar quedarían menos caracteres que el
 *       mínimo exigido a un documento analizable.</li>
 *   <li>Si tras la bibliografía hay un anexo o apéndice, se conserva y se analiza con el resto.</li>
 * </ul>
 *
 * <p>La parte conservada del texto mantiene sus desplazamientos originales, de modo que la
 * segmentación posterior sigue localizando cada párrafo.</p>
 */
@Slf4j
@Service
public class BibliographyDetector {

    /** Longitud máxima de un encabezado: por encima de ella se considera una frase. */
    private static final int MAX_HEADING_CHARS = 60;

    /** Separación entre párrafos del texto normalizado (misma expresión que la segmentación). */
    private static final String PARAGRAPH_SEPARATOR = "\n\\s*\n";

    /** Encabezados que abren la bibliografía, con numeración o viñeta opcionales delante. */
    private static final Pattern BIBLIOGRAPHY_HEADING = Pattern.compile(
            "^[-–—•·*]?\\s*"
                    + "(?:(?:\\d{1,2}|[IVXLCDM]{1,6})\\s*[.)\\-–—]\\s*)?"
                    + "(?:referencias?(?:\\s+(?:bibliogr[aá]ficas?|citadas))?"
                    + "|bibliograf[ií]a(?:\\s+citada)?"
                    + "|obras\\s+citadas"
                    + "|literatura\\s+citada"
                    + "|fuentes\\s+consultadas"
                    + "|references"
                    + "|bibliography"
                    + "|works\\s+cited"
                    + "|literature\\s+cited)"
                    + "\\s*[:.]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Encabezados que cierran la bibliografía y vuelven a ser analizables (anexos, apéndices). */
    private static final Pattern END_HEADING = Pattern.compile(
            "^[-–—•·*]?\\s*"
                    + "(?:(?:\\d{1,2}|[IVXLCDM]{1,6})\\s*[.)\\-–—]\\s*)?"
                    + "(?:anexos?|ap[eé]ndices?|annexes?|appendix|appendices)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Texto mínimo que debe quedar tras el recorte: el mismo que se exige a un documento. */
    private final int minKeptChars;

    public BibliographyDetector(@Value("${textorigin.analysis.min-text-length:100}") int minTextLength) {
        this.minKeptChars = minTextLength;
    }

    /**
     * Devuelve el texto sin su sección bibliográfica final, si se detecta.
     *
     * @param text texto normalizado del documento
     * @return el mismo texto si no hay una bibliografía reconocible; si la hay, el texto sin
     *         ella, conservando los anexos o apéndices posteriores
     */
    public String stripBibliography(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        int headingStart = -1;
        int endStart = -1;
        String heading = null;

        int cursor = 0;
        boolean firstParagraph = true;
        for (String raw : text.split(PARAGRAPH_SEPARATOR, -1)) {
            int start = text.indexOf(raw, cursor);
            if (start < 0) {
                start = cursor;
            }
            cursor = start + raw.length();

            String paragraph = TextSegmentationService.cleanParagraph(raw);
            if (paragraph.isEmpty()) {
                continue;
            }
            if (headingStart < 0) {
                // El encabezado no puede abrir el documento: un texto que es solo una
                // bibliografía se analiza entero.
                if (!firstParagraph && isHeading(paragraph, BIBLIOGRAPHY_HEADING)) {
                    headingStart = start;
                    heading = paragraph;
                }
                firstParagraph = false;
            } else if (isHeading(paragraph, END_HEADING)) {
                endStart = start;
                break;
            }
        }

        if (headingStart < 0) {
            return text;
        }

        String kept = text.substring(0, headingStart).strip();
        if (kept.length() < minKeptChars) {
            log.info("Encabezado '{}' detectado al principio del texto: no se recorta bibliografía",
                    heading);
            return text;
        }

        String stripped = endStart < 0
                ? kept
                : kept + "\n\n" + text.substring(endStart).strip();
        log.info("Bibliografía excluida del análisis (encabezado '{}'): {} de {} caracteres",
                heading, text.length() - stripped.length(), text.length());
        return stripped;
    }

    /** Indica si el párrafo es un encabezado reconocible: corto y ocupando la línea completa. */
    private static boolean isHeading(String paragraph, Pattern pattern) {
        return paragraph.length() <= MAX_HEADING_CHARS && pattern.matcher(paragraph).matches();
    }
}
