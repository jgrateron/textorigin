package com.textorigin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorte automático de la sección bibliográfica antes de segmentar: encabezados admitidos,
 * anexos posteriores y casos en los que no debe recortarse nada.
 */
class BibliographyDetectorTest {

    /** Cuerpo de ejemplo, con la longitud mínima que exige el recorte. */
    private static final String BODY = "El presente trabajo analiza el uso de las fuentes primarias "
            + "en la historiografía reciente y discute sus límites metodológicos con cierto detalle.";

    private final BibliographyDetector detector = new BibliographyDetector(100);

    @Test
    void recortaLasReferenciasFinales() {
        String text = BODY + "\n\nReferencias\n\nSmith, J. (2020). Título del artículo. Revista, 12(3), 45-67."
                + "\n\nPérez, L. (2019). Otro título. Editorial.";

        assertThat(detector.stripBibliography(text)).isEqualTo(BODY);
    }

    @Test
    void reconoceEncabezadosNumeradosYEnMayusculas() {
        String text = BODY + "\n\n7. BIBLIOGRAFÍA\n\nSmith, J. (2020). Título del artículo.";

        assertThat(detector.stripBibliography(text)).isEqualTo(BODY);
    }

    @Test
    void reconoceEncabezadosEnInglesYSinTilde() {
        String worksCited = BODY + "\n\nWorks Cited\n\nSmith, J. (2020). Title. Journal.";
        String bibliografia = BODY + "\n\nBibliografia:\n\nSmith, J. (2020). Título.";

        assertThat(detector.stripBibliography(worksCited)).isEqualTo(BODY);
        assertThat(detector.stripBibliography(bibliografia)).isEqualTo(BODY);
    }

    @Test
    void conservaElAnexoPosteriorALaBibliografia() {
        String annex = "Anexo I: Entrevistas\n\nEntrevista realizada el 3 de marzo.";
        String text = BODY + "\n\nBibliografía\n\nSmith, J. (2020). Título.\n\n" + annex;

        assertThat(detector.stripBibliography(text)).isEqualTo(BODY + "\n\n" + annex);
    }

    @Test
    void noRecortaUnDocumentoSinBibliografia() {
        String text = BODY + "\n\nConclusión\n\nEl trabajo cierra con una reflexión final.";

        assertThat(detector.stripBibliography(text)).isEqualTo(text);
    }

    @Test
    void noRecortaCuandoLaMencionNoEsUnEncabezado() {
        String text = BODY + "\n\nEl autor revisa las referencias bibliográficas de la obra y las "
                + "comenta una a una con detalle, señalando sus aciertos y sus errores."
                + "\n\nY el análisis continúa con más matices.";

        assertThat(detector.stripBibliography(text)).isEqualTo(text);
    }

    @Test
    void noRecortaCuandoLaBibliografiaAbreElDocumento() {
        String text = "Referencias\n\nSmith, J. (2020). Título del artículo. Revista, 12(3), 45-67.";

        assertThat(detector.stripBibliography(text)).isEqualTo(text);
    }

    @Test
    void noRecortaSiQuedariaSinTextoAnalizable() {
        String text = "Título del trabajo\n\nReferencias\n\nSmith, J. (2020). Título del artículo.";

        assertThat(detector.stripBibliography(text)).isEqualTo(text);
    }
}
