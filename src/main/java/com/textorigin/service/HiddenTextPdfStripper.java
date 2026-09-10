package com.textorigin.service;

import com.textorigin.model.HiddenSpan;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extrae el texto visible de un PDF y descarta el que está oculto, para que no llegue al modelo.
 *
 * <p>Se considera oculto, por este orden: el texto con modo de renderizado invisible
 * ({@code Tr 3}), el dibujado por debajo de dos puntos, el pintado en blanco sobre una página
 * que tiene texto de otro color y el situado fuera del área de la página. La clase de
 * {@link TextPosition} no expone ni el color ni el modo de renderizado: se leen del estado
 * gráfico del motor en el momento de procesar cada glifo, que es cuando son válidos.</p>
 *
 * <p>Ante cualquier duda se conserva el texto: si el tamaño de fuente no se puede resolver o la
 * correspondencia entre caracteres y posiciones no es uno a uno, el fragmento se trata como
 * visible. La decisión de <em>cuánto</em> texto oculto es razonable descartar no se toma aquí,
 * sino en {@code TextExtractionService}, que conoce el total del documento.</p>
 */
class HiddenTextPdfStripper extends PDFTextStripper {

    /** Tolerancia (en puntos) para no marcar texto legítimo que roza el borde de la página. */
    private static final float PAGE_TOLERANCE_PT = 2f;

    /** Máximo de fragmentos ocultos que se conservan como muestra de lo descartado. */
    private static final int MAX_SAMPLES = 200;

    private final PageProfile profile;
    private final Map<TextPosition, String> hiddenReasons = new IdentityHashMap<>();
    private final List<HiddenSpan> hiddenSpans = new ArrayList<>();

    private int hiddenCharCount;
    private int visibleCharCount;

    HiddenTextPdfStripper(PageProfile profile) {
        this.profile = profile;
        setSortByPosition(true);
        registerFillColorOperators(this);
    }

    /**
     * El motor de extracción de texto de PDFBox no procesa los operadores de color, así que su
     * estado gráfico siempre dice «negro»: hay que registrarlos para poder ver el texto blanco.
     * Solo se registran los espacios de color de dispositivo (escala de grises, RGB y CMYK),
     * que son los que usan los generadores habituales; un blanco definido en un espacio de
     * color personalizado no se detectaría.
     */
    private static void registerFillColorOperators(PDFTextStripper stripper) {
        stripper.addOperator(new SetNonStrokingDeviceGrayColor(stripper));
        stripper.addOperator(new SetNonStrokingDeviceRGBColor(stripper));
        stripper.addOperator(new SetNonStrokingDeviceCMYKColor(stripper));
    }

    /** Fragmentos descartados, en orden de lectura (hasta {@value #MAX_SAMPLES}). */
    List<HiddenSpan> getHiddenSpans() {
        return List.copyOf(hiddenSpans);
    }

    /** Motivos distintos encontrados, en orden de aparición y sin repeticiones. */
    List<String> getHiddenReasons() {
        return hiddenSpans.stream().map(HiddenSpan::reason).distinct().toList();
    }

    /** Caracteres descartados por estar ocultos. */
    int getHiddenCharCount() {
        return hiddenCharCount;
    }

    /** Caracteres conservados por ser visibles. */
    int getVisibleCharCount() {
        return visibleCharCount;
    }

    @Override
    protected void processTextPosition(TextPosition text) {
        String reason = hiddenReason(text);
        if (reason != null) {
            hiddenReasons.put(text, reason);
        }
        super.processTextPosition(text);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (positions == null || positions.size() != text.length()) {
            // Sin correspondencia uno a uno no se puede decidir con seguridad: se conserva.
            visibleCharCount += text.length();
            super.writeString(text, positions);
            return;
        }

        int index = 0;
        while (index < text.length()) {
            String reason = hiddenReasons.get(positions.get(index));
            int end = index + 1;
            while (end < text.length() && Objects.equals(hiddenReasons.get(positions.get(end)), reason)) {
                end++;
            }

            String chunk = text.substring(index, end);
            if (reason == null) {
                visibleCharCount += chunk.length();
                super.writeString(chunk, positions.subList(index, end));
            } else {
                addHiddenSpan(chunk, reason);
            }
            index = end;
        }
    }

    /** Motivo por el que un glifo se considera oculto, o {@code null} si es visible. */
    private String hiddenReason(TextPosition position) {
        PDGraphicsState state = getGraphicsState();
        RenderingMode mode = state == null || state.getTextState() == null
                ? null
                : state.getTextState().getRenderingMode();
        if (mode != null && !mode.isFill() && !mode.isStroke()) {
            return "modo de renderizado invisible";
        }
        if (HiddenFormatRules.isTooSmall(position.getFontSizeInPt())) {
            return "tamaño diminuto";
        }
        if (isWhiteFill(state) && profile.whiteIsHidden(getCurrentPageNo())) {
            return "color blanco";
        }
        if (isOutsidePage(position)) {
            return "fuera del área de la página";
        }
        return null;
    }

    private void addHiddenSpan(String chunk, String reason) {
        hiddenCharCount += chunk.length();
        if (hiddenSpans.size() < MAX_SAMPLES) {
            hiddenSpans.add(new HiddenSpan("página " + getCurrentPageNo(), reason, chunk));
        }
    }

    /** Indica si la tinta de relleno actual es blanca. */
    private static boolean isWhiteFill(PDGraphicsState state) {
        if (state == null) {
            return false;
        }
        PDColor color = state.getNonStrokingColor();
        return color != null && HiddenFormatRules.isWhiteish(color.getComponents());
    }

    /** Indica si el glifo cae fuera del área de la página (con tolerancia). */
    private static boolean isOutsidePage(TextPosition position) {
        float pageWidth = position.getPageWidth();
        float pageHeight = position.getPageHeight();
        if (pageWidth <= 0 || pageHeight <= 0) {
            return false;
        }
        float x = position.getXDirAdj();
        float y = position.getYDirAdj();
        return x + position.getWidthDirAdj() < -PAGE_TOLERANCE_PT
                || x > pageWidth + PAGE_TOLERANCE_PT
                || y < -PAGE_TOLERANCE_PT
                || y > pageHeight + PAGE_TOLERANCE_PT;
    }

    /**
     * Perfil de color del documento: indica en qué páginas el texto blanco puede considerarse
     * oculto. En una página sin ningún carácter de otro color (fondo oscuro, portada sobre una
     * imagen) el blanco es la tinta legítima, así que no se descarta nada.
     */
    record PageProfile(boolean[] whiteIsHidden) {

        /** Perfil que no considera oculto el blanco en ninguna página. */
        static PageProfile none() {
            return new PageProfile(new boolean[0]);
        }

        /** Indica si en esa página (numerada desde 1) el blanco cuenta como oculto. */
        boolean whiteIsHidden(int pageNumber) {
            return pageNumber >= 1 && pageNumber <= whiteIsHidden.length && whiteIsHidden[pageNumber - 1];
        }

        /** Recorre el PDF contando caracteres blancos y no blancos por página. */
        static PageProfile measure(PDDocument pdf) throws IOException {
            ColorCounter counter = new ColorCounter();
            counter.getText(pdf);
            return new PageProfile(counter.whiteIsHiddenByPage());
        }
    }

    /** Pasada de medición: cuenta por página los caracteres blancos y los que no lo son. */
    private static final class ColorCounter extends PDFTextStripper {

        private final Map<Integer, int[]> countsByPage = new HashMap<>();

        ColorCounter() {
            registerFillColorOperators(this);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            int[] counts = countsByPage.computeIfAbsent(getCurrentPageNo(), page -> new int[2]);
            counts[isWhiteFill(getGraphicsState()) ? 0 : 1]++;
            // No se llama a super: esta pasada solo cuenta, no construye texto.
        }

        boolean[] whiteIsHiddenByPage() {
            int lastPage = countsByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            boolean[] result = new boolean[lastPage];
            for (Map.Entry<Integer, int[]> entry : countsByPage.entrySet()) {
                int page = entry.getKey();
                if (page >= 1 && page <= lastPage) {
                    result[page - 1] = entry.getValue()[1] > 0;
                }
            }
            return result;
        }
    }
}
