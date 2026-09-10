package com.textorigin.service;

import com.textorigin.model.DocumentWarning;
import com.textorigin.model.SanitizedText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Neutraliza las frases del documento que intentan dirigirse al modelo en lugar de formar
 * parte del trabajo (prompt injection en texto visible).
 *
 * <p>Cada coincidencia se sustituye por {@link #NEUTRALIZED_MARK}, una marca visible que el
 * profesor ve en el propio párrafo, y se registra un aviso con el fragmento original
 * reproducido: la neutralización nunca es silenciosa. Solo se detectan formas imperativas,
 * de segunda persona o marcadores de rol; las palabras clave sueltas («jailbreak», «system
 * prompt») se dejan intactas porque aparecen en ensayos legítimos sobre IA.</p>
 *
 * <p>También se neutraliza la secuencia de triples comillas y el propio marcador del prompt:
 * un texto que los reproduzca podría cerrar el bloque delimitado que separa las instrucciones
 * del contenido.</p>
 */
@Slf4j
@Service
public class InjectionDefenseService {

    /** Marca visible que sustituye a cada fragmento neutralizado. */
    public static final String NEUTRALIZED_MARK = "[contenido eliminado: posible instrucción dirigida al modelo]";

    /** Máximo de avisos individuales; el resto se agrupa en un aviso con el recuento. */
    private static final int MAX_INDIVIDUAL_WARNINGS = 10;

    /** Tope de caracteres que puede abarcar una sustitución, para no arrasar un párrafo. */
    private static final int MAX_SPAN_CHARS = 200;

    /** Delimitador del bloque de texto del prompt: no puede aparecer en el contenido. */
    private static final String TRIPLE_QUOTE = "\"\"\"";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Familias de frases dirigidas al modelo (español e inglés). */
    private static final List<Pattern> INSTRUCTION_PATTERNS = List.of(
            // Reinicio de instrucciones
            pattern("ignor[aáe]\\s+(?:todas?\\s+)?(?:las\\s+)?(?:instrucciones|indicaciones|reglas|órdenes)"
                    + "(?:\\s+(?:anteriores|previas|de\\s+arriba|del\\s+sistema))?"),
            pattern("olvida(?:r)?\\s+(?:todas?\\s+)?(?:las\\s+)?(?:instrucciones|indicaciones)"),
            pattern("(?:ignore|disregard|forget)\\s+(?:all\\s+)?(?:the\\s+)?"
                    + "(?:previous|prior|above|earlier|foregoing)?\\s*(?:instructions|prompts?|rules|commands)"),
            // Manipulación de la puntuación
            pattern("(?:asigna|asignar|otorga|concede|devuelve|punt[uú]a|pon)(?:\\s+\\p{L}+){0,3}?\\s+(?:un\\s+)?"
                    + "(?:score|puntuaci[oó]n|porcentaje|nota)\\s*(?:de|del|=|:)?\\s*(?:0|100|cero|cien)\\b"),
            pattern("(?:score|puntuaci[oó]n|porcentaje)\\s*[:=]\\s*(?:0|100)\\b"),
            pattern("(?:assign|give|set|return|output)\\s+(?:the\\s+|a\\s+)?(?:score|rating|percentage)\\s+"
                    + "(?:(?:of|to|as)\\s+)?(?:0|100|zero|one\\s+hundred)\\b"),
            // Declaración de autoría humana
            pattern("(?:considera|clasifica|marca|etiqueta|declara|trata)(?:\\s+\\p{L}+){0,3}?\\s+(?:como\\s+)?"
                    + "(?:100\\s*%|cien\\s+por\\s+ciento|totalmente|completamente)?\\s*human[oa]\\b"),
            pattern("(?:este|el)\\s+texto\\s+(?:es|fue|ha\\s+sido)\\s+(?:escrito\\s+)?(?:por\\s+)?(?:un\\s+)?human[oa]\\b"),
            pattern("(?:this|the)\\s+(?:text|essay|document)\\s+(?:is|was)\\s+(?:written\\s+by\\s+a\\s+)?human\\b"),
            pattern("(?:classify|consider|mark)\\s+(?:it|this|the\\s+text)\\s+as\\s+human\\b"),
            // Silencio u ocultación del intento
            pattern("no\\s+(?:menciones|indiques|reveles|digas|informes|incluyas|comentes)(?:\\s+\\p{L}+){0,6}"),
            pattern("do\\s+not\\s+(?:mention|reveal|disclose|tell|include|report)(?:\\s+\\S+){0,6}"),
            pattern("(?:omite|oculta)\\s+(?:esta|estas|la)\\s+(?:instrucci[oó]n|frase|parte|indicaci[oó]n)"),
            // Marcadores de rol
            pattern("(?m)^\\s*(?:system|assistant|developer|sistema|asistente|usuario|user)\\s*:"),
            pattern("\\[/?INST\\]"),
            pattern("<\\|(?:im_start|im_end|system|user|assistant|endoftext)\\|>"),
            pattern("(?m)^\\s*#{2,4}\\s*(?:instrucci[oó]n|instruction|system)"),
            // Fuga del delimitador que separa instrucciones y texto en el prompt
            pattern("(?m)^\\s*=+\\s*texto\\s+a\\s+analizar\\s*=+\\s*$"));

    /**
     * Sustituye por una marca visible las frases dirigidas al modelo que se detecten.
     *
     * @param text texto del documento (ya sin caracteres invisibles), posiblemente {@code null}
     * @return el texto neutralizado y los avisos correspondientes
     */
    public SanitizedText neutralizeInstructions(String text) {
        if (text == null || text.isBlank()) {
            return SanitizedText.of(text == null ? "" : text);
        }

        List<DocumentWarning> warnings = new ArrayList<>();
        StringBuilder working = new StringBuilder(protectDelimiter(text, warnings));

        List<int[]> spans = collectSpans(working.toString());
        if (spans.isEmpty()) {
            return new SanitizedText(working.toString(), warnings);
        }

        String original = working.toString();
        for (int i = spans.size() - 1; i >= 0; i--) {
            int[] span = spans.get(i);
            working.replace(span[0], span[1], replacementFor(original.substring(span[0], span[1])));
        }

        addSpanWarnings(original, spans, warnings);
        log.info("Instrucciones dirigidas al modelo neutralizadas: {} fragmento(s)", spans.size());
        return new SanitizedText(working.toString(), warnings);
    }

    /** Recolecta las coincidencias de todas las familias, ya expandidas y sin solaparse. */
    private static List<int[]> collectSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        for (Pattern pattern : INSTRUCTION_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find() && matcher.end() > matcher.start()) {
                spans.add(new int[] {matcher.start(), expandToClauseEnd(text, matcher.start(), matcher.end())});
            }
        }
        spans.sort(Comparator.comparingInt(span -> span[0]));

        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty() && span[0] < merged.getLast()[1]) {
                int[] previous = merged.getLast();
                previous[1] = Math.max(previous[1], span[1]);
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    /**
     * Amplía el final de la coincidencia hasta el cierre de la frase o de la cláusula, sin
     * pasar de {@link #MAX_SPAN_CHARS} caracteres ni de un salto de línea.
     */
    private static int expandToClauseEnd(String text, int start, int end) {
        int limit = Math.min(text.length(), start + MAX_SPAN_CHARS);
        int cursor = end;
        while (cursor < limit) {
            char current = text.charAt(cursor);
            cursor++;
            if (current == '\n') {
                return cursor - 1;
            }
            if (current == '.' || current == ';' || current == '!' || current == '?') {
                return cursor;
            }
        }
        return cursor;
    }

    /**
     * Conserva el salto de párrafo para que la segmentación posterior no fusione párrafos.
     * Si el fragmento sustituido absorbía una separación de párrafos, se reinserta una; los
     * saltos sobrantes que puedan quedar los colapsa la propia segmentación.
     */
    private static String replacementFor(String original) {
        return original.contains("\n\n") ? NEUTRALIZED_MARK + "\n\n" : NEUTRALIZED_MARK;
    }

    /** Registra un aviso por fragmento neutralizado, con tope y aviso agregado del resto. */
    private static void addSpanWarnings(String text, List<int[]> spans, List<DocumentWarning> warnings) {
        int individual = Math.min(spans.size(), MAX_INDIVIDUAL_WARNINGS);
        for (int i = 0; i < individual; i++) {
            int[] span = spans.get(i);
            warnings.add(DocumentWarning.instructionPhrase(
                    DocumentWarning.truncateExcerpt(text.substring(span[0], span[1]))));
        }
        if (spans.size() > individual) {
            warnings.add(DocumentWarning.moreInstructionPhrases(spans.size() - individual));
        }
    }

    /**
     * Neutraliza el delimitador del prompt: cada secuencia de triples comillas se reduce a una
     * comilla para que el texto no pueda cerrar el bloque que lo delimita.
     */
    private static String protectDelimiter(String text, List<DocumentWarning> warnings) {
        int count = 0;
        int index = text.indexOf(TRIPLE_QUOTE);
        while (index >= 0) {
            count++;
            index = text.indexOf(TRIPLE_QUOTE, index + TRIPLE_QUOTE.length());
        }
        if (count == 0) {
            return text;
        }
        log.info("Secuencias de triples comillas neutralizadas: {}", count);
        warnings.add(DocumentWarning.promptDelimiter(count));
        return text.replace(TRIPLE_QUOTE, "\"");
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, FLAGS);
    }
}
