package com.textorigin.service;

import com.textorigin.model.DocumentWarning;
import com.textorigin.model.SanitizedText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elimina del texto los caracteres Unicode invisibles que pueden usarse para esconder o
 * partir instrucciones dirigidas al modelo.
 *
 * <p>Se quitan el guion blando, los espacios y uniones de ancho cero, los controles y
 * aislantes de dirección de escritura, los formatos obsoletos, las anotaciones interlineales y
 * el bloque de «tag characters» (U+E0000–U+E007F), que permite esconder ASCII completo a la
 * vista. Los tag chars se decodifican para poder enseñar el mensaje que ocultaban. No se tocan
 * las marcas direccionales legítimas (U+200E/U+200F) ni los selectores de variación.</p>
 *
 * <p>En este dominio (prosa en español o inglés) ninguno de esos caracteres tiene función
 * ortográfica, así que retirarlos no altera lo que el profesor lee; la eliminación no deja
 * marca en el texto, pero siempre genera un aviso en {@link SanitizedText#warnings()}.</p>
 */
@Slf4j
@Service
public class InvisibleCharacterSanitizer {

    /** Máximo de familias distintas que se detallan en el aviso. */
    private static final int MAX_DETAILED_KINDS = 6;

    /** Inicio del bloque de tag characters (su código menos el ASCII que representan). */
    private static final int TAG_CHARACTERS_START = 0xE0000;

    /** Fin del bloque de tag characters. */
    private static final int TAG_CHARACTERS_END = 0xE007F;

    /** Primer carácter ASCII imprimible. */
    private static final char FIRST_PRINTABLE_ASCII = 0x20;

    /** Último carácter ASCII imprimible. */
    private static final char LAST_PRINTABLE_ASCII = 0x7E;

    /**
     * Sanea un texto eliminando los caracteres invisibles.
     *
     * @param text texto de entrada, posiblemente {@code null}
     * @return el texto limpio y, si se eliminó algo, un aviso con el recuento y el mensaje
     *         decodificado cuando lo había
     */
    public SanitizedText sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return SanitizedText.of(text == null ? "" : text);
        }

        StringBuilder clean = new StringBuilder(text.length());
        StringBuilder decodedAscii = new StringBuilder();
        Map<Integer, Integer> removedByCodePoint = new LinkedHashMap<>();
        boolean changed = false;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            if (!isInvisible(codePoint)) {
                clean.appendCodePoint(codePoint);
                continue;
            }
            changed = true;
            removedByCodePoint.merge(codePoint, 1, Integer::sum);
            if (isTagCharacter(codePoint)) {
                char decoded = (char) (codePoint - TAG_CHARACTERS_START);
                if (decoded >= FIRST_PRINTABLE_ASCII && decoded <= LAST_PRINTABLE_ASCII) {
                    decodedAscii.append(decoded);
                }
            }
        }

        if (!changed) {
            return SanitizedText.of(text);
        }

        int removedCount = removedByCodePoint.values().stream().mapToInt(Integer::intValue).sum();
        String detail = "Se han eliminado " + removedCount + " caracteres que no se ven al leer: "
                + describe(removedByCodePoint)
                + ". Pueden usarse para esconder o partir instrucciones sin que se note.";
        log.info("Caracteres invisibles eliminados: {}", describe(removedByCodePoint));

        List<String> excerpts = new ArrayList<>();
        if (!decodedAscii.isEmpty()) {
            excerpts.add(DocumentWarning.truncateExcerpt(decodedAscii.toString()));
        }
        return new SanitizedText(clean.toString(),
                List.of(DocumentWarning.invisibleCharacters(removedCount, detail, excerpts)));
    }

    /** Indica si el code point pertenece a una familia invisible que se elimina. */
    static boolean isInvisible(int codePoint) {
        return codePoint == 0x00AD
                || codePoint == 0x180E
                || (codePoint >= 0x200B && codePoint <= 0x200D)
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2060 && codePoint <= 0x2064)
                || (codePoint >= 0x2066 && codePoint <= 0x206F)
                || codePoint == 0xFEFF
                || (codePoint >= 0xFFF9 && codePoint <= 0xFFFB)
                || isTagCharacter(codePoint);
    }

    /** Indica si el code point pertenece al bloque de tag characters. */
    static boolean isTagCharacter(int codePoint) {
        return codePoint >= TAG_CHARACTERS_START && codePoint <= TAG_CHARACTERS_END;
    }

    /** Histograma legible: «U+200B espacio de ancho cero ×12, U+202E … ×1». */
    private static String describe(Map<Integer, Integer> removedByCodePoint) {
        List<String> entries = removedByCodePoint.entrySet().stream()
                .map(entry -> format(entry.getKey()) + " " + nameOf(entry.getKey()) + " ×" + entry.getValue())
                .toList();
        if (entries.size() <= MAX_DETAILED_KINDS) {
            return String.join(", ", entries);
        }
        int extra = entries.size() - MAX_DETAILED_KINDS;
        return String.join(", ", entries.subList(0, MAX_DETAILED_KINDS)) + " y " + extra + " más";
    }

    private static String format(int codePoint) {
        return String.format("U+%04X", codePoint);
    }

    /** Nombre en español de la familia del code point. */
    private static String nameOf(int codePoint) {
        if (isTagCharacter(codePoint)) {
            return "carácter de etiqueta (ASCII oculto)";
        }
        return switch (codePoint) {
            case 0x00AD -> "guion blando";
            case 0x180E -> "separador vocálico mongol";
            case 0x200B -> "espacio de ancho cero";
            case 0x200C -> "no-unión de ancho cero";
            case 0x200D -> "unión de ancho cero";
            case 0x2060 -> "unión de palabras";
            case 0xFEFF -> "espacio de ancho cero (BOM)";
            default -> {
                if (codePoint >= 0x202A && codePoint <= 0x202E) {
                    yield "control de dirección de escritura";
                }
                if (codePoint >= 0x2061 && codePoint <= 0x2064) {
                    yield "operador invisible";
                }
                if (codePoint >= 0x2066 && codePoint <= 0x2069) {
                    yield "aislante de dirección de escritura";
                }
                if (codePoint >= 0x206A && codePoint <= 0x206F) {
                    yield "formato obsoleto";
                }
                yield "anotación interlineal";
            }
        };
    }
}
