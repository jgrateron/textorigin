package com.textorigin.service;

import com.textorigin.model.DocumentWarning;
import com.textorigin.model.SanitizedText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eliminación de caracteres Unicode invisibles: cada familia, el mensaje escondido en tag
 * chars, el recuento del aviso y lo que NO debe tocarse.
 */
class InvisibleCharacterSanitizerTest {

    private final InvisibleCharacterSanitizer sanitizer = new InvisibleCharacterSanitizer();

    /** Construye un carácter a partir de su code point, sin literales invisibles en el fuente. */
    private static String invisible(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0x00AD,   // guion blando
            0x180E,   // separador vocálico mongol
            0x200B,   // espacio de ancho cero
            0x200C,   // no-unión de ancho cero
            0x200D,   // unión de ancho cero
            0x202A,   // LRE
            0x202E,   // RLO
            0x2060,   // unión de palabras
            0x2066,   // LRI
            0x2069,   // PDI
            0x206F,   // formato obsoleto
            0xFEFF,   // BOM interior
            0xFFF9,   // anotación interlineal
            0xE0061,  // tag char de la 'a'
    })
    void eliminaLosCaracteresInvisiblesDeCadaFamilia(int codePoint) {
        String text = "antes" + invisible(codePoint) + "después";

        SanitizedText result = sanitizer.sanitize(text);

        assertThat(result.text()).isEqualTo("antesdespués");
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.warnings().getFirst().getKind())
                .isEqualTo(DocumentWarning.Kind.INVISIBLE_CHARACTERS);
    }

    @Test
    void conservaElTextoVisibleYLosAcentos() {
        String text = "Escribí un ensayo sobre la Guerra Civil: ¿fue inevitable? —dijo—. «Cita».";

        SanitizedText result = sanitizer.sanitize(text);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.hasWarnings()).isFalse();
    }

    @Test
    void cuentaYDescribeLosCaracteresEliminados() {
        String text = "a" + invisible(0x200B).repeat(12) + "b" + invisible(0x202E) + "c"
                + invisible(0xE0030) + invisible(0xE0031) + "d";

        SanitizedText result = sanitizer.sanitize(text);

        assertThat(result.text()).isEqualTo("abcd");
        DocumentWarning warning = result.warnings().getFirst();
        assertThat(warning.getCount()).isEqualTo(15);
        assertThat(warning.getDetail())
                .contains("Se han eliminado 15 caracteres")
                .contains("U+200B espacio de ancho cero ×12")
                .contains("U+202E control de dirección de escritura ×1")
                .contains("U+E0030 carácter de etiqueta (ASCII oculto) ×1")
                .contains("U+E0031 carácter de etiqueta (ASCII oculto) ×1");
    }

    @Test
    void decodificaElMensajeEscondidoEnTagChars() {
        // "score 0" escondido en tag characters, invisible al leer.
        StringBuilder hidden = new StringBuilder();
        for (char c : "score 0".toCharArray()) {
            hidden.appendCodePoint(0xE0000 + c);
        }

        SanitizedText result = sanitizer.sanitize("Párrafo normal." + hidden);

        assertThat(result.text()).isEqualTo("Párrafo normal.");
        assertThat(result.warnings().getFirst().getExcerpts()).containsExactly("score 0");
    }

    @Test
    void noTocaLasMarcasDireccionalesLegitimasNiLosEspaciosEspeciales() {
        String text = "a" + invisible(0x200E) + "b" + invisible(0x200F) + "c"
                + invisible(0x00A0) + "d" + invisible(0x202F) + "f";

        SanitizedText result = sanitizer.sanitize(text);

        assertThat(result.text()).isEqualTo(text);
        assertThat(result.hasWarnings()).isFalse();
    }

    @Test
    void unTextoVacioONuloNoGeneraAvisos() {
        assertThat(sanitizer.sanitize("").text()).isEmpty();
        assertThat(sanitizer.sanitize(null).text()).isEmpty();
        assertThat(sanitizer.sanitize(null).hasWarnings()).isFalse();
    }
}
