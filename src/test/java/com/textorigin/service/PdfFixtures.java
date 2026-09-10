package com.textorigin.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Documentos PDF generados en memoria para los tests de extracción: sin binarios en el
 * repositorio y con control total sobre el formato oculto.
 */
final class PdfFixtures {

    /** Longitud de las líneas de los párrafos de prueba, para que quepan en la página. */
    private static final int LINE_WIDTH = 60;

    /** Formas de ocultar el texto disponibles en los fixtures. */
    enum HiddenStyle {
        /** Modo de renderizado invisible ({@code Tr 3}). */
        INVISIBLE_RENDERING,
        /** Texto blanco sobre una página con texto negro. */
        WHITE,
        /** Texto de un punto. */
        TINY,
        /** Texto dibujado fuera del área de la página. */
        OUTSIDE_PAGE
    }

    private PdfFixtures() {
    }

    /** PDF con un único párrafo visible y nada oculto. */
    static byte[] plain(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                writeText(stream, font, 12, 700, text);
            }
            return toBytes(document);
        }
    }

    /** PDF con un párrafo visible y un fragmento oculto en el estilo indicado. */
    static byte[] withVisibleAndHidden(String visible, String hidden, HiddenStyle style) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                writeText(stream, font, 12, 700, visible);
                applyStyle(stream, style);
                writeText(stream, font, fontSizeFor(style), offsetFor(style), hidden);
            }
            return toBytes(document);
        }
    }

    /** PDF cuya única página solo tiene texto blanco (fondo oscuro o portada sobre imagen). */
    static byte[] withOnlyWhiteText(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(1f, 1f, 1f);
                writeText(stream, font, 12, 700, text);
            }
            return toBytes(document);
        }
    }

    private static void applyStyle(PDPageContentStream stream, HiddenStyle style) throws IOException {
        switch (style) {
            case INVISIBLE_RENDERING -> stream.setRenderingMode(RenderingMode.NEITHER);
            case WHITE -> stream.setNonStrokingColor(1f, 1f, 1f);
            case TINY, OUTSIDE_PAGE -> {
                // El tamaño y la posición se aplican al escribir el texto.
            }
        }
    }

    private static float fontSizeFor(HiddenStyle style) {
        return style == HiddenStyle.TINY ? 1f : 12f;
    }

    private static float offsetFor(HiddenStyle style) {
        return style == HiddenStyle.OUTSIDE_PAGE ? -30f : 650f;
    }

    private static void writeText(PDPageContentStream stream, PDFont font, float size, float y, String text)
            throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(50, y);
        // El texto se ajusta en líneas cortas: una sola línea larga se saldría de la página y la
        // propia defensa la marcaría como texto fuera del área de la página.
        for (String line : wrap(text, LINE_WIDTH)) {
            stream.showText(line);
            stream.newLineAtOffset(0, -(size + 2));
        }
        stream.endText();
    }

    /** Reparte el texto en líneas de como mucho {@code width} caracteres, por palabras. */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static byte[] toBytes(PDDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }
}
