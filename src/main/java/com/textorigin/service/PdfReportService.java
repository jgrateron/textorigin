package com.textorigin.service;

import com.textorigin.exception.AnalysisException;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.model.SegmentAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Genera el informe PDF de un análisis con Apache PDFBox 3.
 *
 * <p>El informe se compone de cuatro secciones más la portada y el pie de página:</p>
 * <ol>
 *   <li>Portada: título, fecha, identificador del análisis y score global.</li>
 *   <li>Resumen ejecutivo: veredicto orientativo y recuento de segmentos por categoría.</li>
 *   <li>Texto analizado: cada párrafo con fondo del color de su categoría.</li>
 *   <li>Análisis segmento por segmento: tabla con score, indicadores y explicación.</li>
 *   <li>Advertencias y limitaciones metodológicas.</li>
 * </ol>
 *
 * <p>Se usan exclusivamente las fuentes estándar Helvetica, que cubren los caracteres
 * acentuados del español mediante la codificación WinAnsi. Cualquier carácter no
 * representable (emojis, alfabetos no latinos) se sustituye por {@code ?} para que el
 * documento nunca falle al generarse.</p>
 */
@Slf4j
@Service
public class PdfReportService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm", new Locale("es", "ES"));

    /** Correo de contacto impreso en el informe, configurable con {@code textorigin.contact-email}. */
    @Value("${textorigin.contact-email:jgrateron@gmail.com}")
    private String contactEmail;

    /**
     * Genera el informe en memoria.
     *
     * @param analysis análisis completado
     * @return el contenido del PDF
     * @throws AnalysisException si el documento no puede generarse
     */
    public byte[] generateReport(DocumentAnalysis analysis) {
        if (analysis == null) {
            throw new AnalysisException("No hay datos para generar el informe.");
        }

        try (PDDocument document = new PDDocument()) {
            PdfWriter writer = new PdfWriter(document, contactEmail);
            writer.writeCover(analysis);
            writer.writeExecutiveSummary(analysis);
            writer.writeAnnotatedText(analysis);
            writer.writeSegmentTable(analysis);
            writer.writeMethodologicalNotes();
            writer.finish();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);

            byte[] bytes = output.toByteArray();
            log.info("Informe PDF generado: id={} documento='{}' segmentos={} páginas={} bytes={}",
                    analysis.getId(), analysis.getDocumentName(), analysis.getTotalCount(),
                    document.getNumberOfPages(), bytes.length);
            return bytes;

        } catch (IOException e) {
            log.error("Error al generar el informe PDF del análisis {}", analysis.getId(), e);
            throw new AnalysisException("No se pudo generar el informe PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Nombre sugerido del archivo de descarga.
     *
     * @param analysis análisis del que se genera el informe
     * @return el nombre del archivo PDF
     */
    public String buildFileName(DocumentAnalysis analysis) {
        return "textorigin-informe-" + analysis.getId() + ".pdf";
    }

    // ==================================================================
    // Escritor de PDF
    // ==================================================================

    /**
     * Pequeño motor de maquetación sobre PDFBox: gestiona el cursor vertical, los saltos de
     * página automáticos, el ajuste de línea y el pie de página de todas las páginas.
     */
    private static final class PdfWriter {

        // --- Geometría de la página A4 ---
        private static final float MARGIN = 50f;
        private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
        private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
        private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
        private static final float FOOTER_BASELINE = 44f;
        private static final float BOTTOM_LIMIT = 70f;

        // --- Paleta corporativa (misma que la interfaz web) ---
        private static final float[] PRIMARY = {0.102f, 0.302f, 0.478f};
        private static final float[] PRIMARY_LIGHT = {0.176f, 0.424f, 0.639f};
        private static final float[] ACCENT = {0.961f, 0.620f, 0.043f};
        private static final float[] SUCCESS = {0.063f, 0.725f, 0.506f};
        private static final float[] DANGER = {0.937f, 0.267f, 0.267f};
        private static final float[] WHITE = {1f, 1f, 1f};
        private static final float[] NEUTRAL_50 = {0.976f, 0.980f, 0.984f};
        private static final float[] NEUTRAL_100 = {0.953f, 0.957f, 0.965f};
        private static final float[] NEUTRAL_300 = {0.820f, 0.835f, 0.859f};
        private static final float[] NEUTRAL_500 = {0.420f, 0.447f, 0.502f};
        private static final float[] NEUTRAL_700 = {0.216f, 0.255f, 0.318f};
        private static final float[] NEUTRAL_900 = {0.067f, 0.094f, 0.153f};
        private static final float[] TINT_HUMAN = {0.878f, 0.965f, 0.933f};
        private static final float[] TINT_DOUBTFUL = {0.996f, 0.953f, 0.839f};
        private static final float[] TINT_AI = {0.996f, 0.886f, 0.886f};
        private static final float[] TINT_PENDING = {0.953f, 0.957f, 0.965f};

        private final PDDocument document;
        private final String contactEmail;
        private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDFont oblique = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);

        private PDPage page;
        private PDPageContentStream stream;
        private float cursorY;

        PdfWriter(PDDocument document, String contactEmail) throws IOException {
            this.document = document;
            this.contactEmail = contactEmail;
            newPage();
        }

        // ------------------------------------------------------------------
        // Portada
        // ------------------------------------------------------------------

        void writeCover(DocumentAnalysis analysis) throws IOException {
            float bandHeight = 132f;
            fillRect(0, PAGE_HEIGHT - bandHeight, PAGE_WIDTH, bandHeight, PRIMARY);
            drawText("TextOrigin", bold, 28, MARGIN, PAGE_HEIGHT - 62, WHITE);
            drawText("Identifica el origen de un texto. Decide con criterio.",
                    regular, 11.5f, MARGIN, PAGE_HEIGHT - 84, WHITE);
            drawText("Informe de análisis", bold, 17, MARGIN, PAGE_HEIGHT - 112, WHITE);

            cursorY = PAGE_HEIGHT - bandHeight - 46;
            drawText("Datos del análisis", bold, 13f, MARGIN, cursorY, PRIMARY);
            cursorY -= 22;

            writeKeyValue("Documento", analysis.getDocumentName());
            writeKeyValue("Fecha del análisis", analysis.getFormattedCompletedAt().isEmpty()
                    ? analysis.getFormattedCreatedAt() : analysis.getFormattedCompletedAt());
            writeKeyValue("Identificador (UUID)", analysis.getId());
            writeKeyValue("Segmentos analizados", analysis.getProcessedCount() + " de " + analysis.getTotalCount()
                    + (analysis.getErrorCount() > 0 ? " (" + analysis.getErrorCount() + " sin analizar)" : ""));
            writeKeyValue("Duración", analysis.getFormattedDuration().isEmpty()
                    ? "no disponible" : analysis.getFormattedDuration());

            cursorY -= 6;
            writeScoreCard(analysis);

            cursorY -= 10;
            List<String> disclaimer = wrap(
                    "Este informe ha sido generado automáticamente por TextOrigin a partir de un modelo de "
                            + "lenguaje. Es un documento orientativo: no constituye prueba de uso de IA y no "
                            + "debe utilizarse como único fundamento de ninguna decisión académica.",
                    oblique, 8.5f, CONTENT_WIDTH);
            for (String line : disclaimer) {
                drawText(line, oblique, 8.5f, MARGIN, cursorY, NEUTRAL_500);
                cursorY -= 11;
            }
        }

        private void writeScoreCard(DocumentAnalysis analysis) throws IOException {
            Integer score = analysis.getGlobalScoreRounded();
            float textX = MARGIN + 190;
            float textWidth = CONTENT_WIDTH - 200;

            List<String> verdictLines = wrap(analysis.getVerdict(), bold, 10.5f, textWidth);
            List<String> hintLines = wrap(analysis.getVerdictHint(), regular, 9f, textWidth);
            float needed = 30 + verdictLines.size() * 14f + 6 + hintLines.size() * 12f + 12;
            float height = Math.max(88f, needed);

            ensureSpace(height + 16);
            float top = cursorY;
            float bottom = top - height;

            fillRect(MARGIN, bottom, CONTENT_WIDTH, height, tintFor(analysis.getGlobalCategory()));
            fillRect(MARGIN, bottom, 5, height, accentFor(analysis.getGlobalCategory()));

            drawText("Score global", regular, 9.5f, MARGIN + 20, top - 20, NEUTRAL_700);
            drawText(score == null ? "n/d" : score + "/100", bold, 30, MARGIN + 20, bottom + 20,
                    accentFor(analysis.getGlobalCategory()));
            drawText(labelFor(analysis.getGlobalCategory()),
                    regular, 8.5f, MARGIN + 20, bottom + 10, NEUTRAL_700);

            float lineY = top - 28;
            for (String line : verdictLines) {
                drawText(line, bold, 10.5f, textX, lineY, NEUTRAL_900);
                lineY -= 14;
            }
            lineY -= 6;
            for (String line : hintLines) {
                drawText(line, regular, 9f, textX, lineY, NEUTRAL_700);
                lineY -= 12;
            }

            cursorY = bottom - 14;
        }

        private void writeKeyValue(String label, String value) throws IOException {
            float labelWidth = 150f;
            float fontSize = 10f;
            float leading = 14f;
            List<String> lines = wrap(value == null || value.isBlank() ? "—" : value,
                    regular, fontSize, CONTENT_WIDTH - labelWidth);

            ensureSpace(lines.size() * leading + 6);
            drawText(label, bold, fontSize, MARGIN, cursorY, NEUTRAL_700);
            for (String line : lines) {
                drawText(line, regular, fontSize, MARGIN + labelWidth, cursorY, NEUTRAL_900);
                cursorY -= leading;
            }
            cursorY -= 3;
        }

        // ------------------------------------------------------------------
        // Sección 1: resumen ejecutivo
        // ------------------------------------------------------------------

        void writeExecutiveSummary(DocumentAnalysis analysis) throws IOException {
            writeSectionTitle("1. Resumen ejecutivo");

            writeParagraph("Documento analizado: " + analysis.getDocumentName()
                    + ". Se han dividido " + analysis.getDocument().getWordCount()
                    + " palabras en " + analysis.getTotalCount() + " segmentos, analizados "
                    + "individualmente con el modelo deepseek-chat.", regular, 10f, NEUTRAL_900);

            cursorY -= 6;
            writeCategoryBoxes(analysis);
            cursorY -= 10;

            Integer score = analysis.getGlobalScoreRounded();
            writeParagraph("Score global: " + (score == null ? "no disponible" : score + " sobre 100")
                    + ". " + analysis.getVerdict() + " " + analysis.getVerdictHint(),
                    regular, 10f, NEUTRAL_900);
        }

        private void writeCategoryBoxes(DocumentAnalysis analysis) throws IOException {
            String[] labels = {"Probablemente humano", "Dudoso", "Alta sospecha IA", "Sin analizar"};
            int[] values = {analysis.getHumanCount(), analysis.getDoubtfulCount(),
                    analysis.getAiCount(), analysis.getErrorCount()};
            float[][] accents = {SUCCESS, ACCENT, DANGER, NEUTRAL_500};
            float[][] tints = {TINT_HUMAN, TINT_DOUBTFUL, TINT_AI, TINT_PENDING};

            float gap = 10f;
            float boxWidth = (CONTENT_WIDTH - 3 * gap) / 4;
            float boxHeight = 56f;

            ensureSpace(boxHeight + 12);
            float top = cursorY;
            float bottom = top - boxHeight;

            for (int i = 0; i < labels.length; i++) {
                float x = MARGIN + i * (boxWidth + gap);
                fillRect(x, bottom, boxWidth, boxHeight, tints[i]);
                fillRect(x, bottom, boxWidth, 3, accents[i]);
                drawText(String.valueOf(values[i]), bold, 20, x + 10, bottom + 22, accents[i]);
                drawText(labels[i], regular, 8.5f, x + 10, bottom + 11, NEUTRAL_700);
            }
            cursorY = bottom - 6;
        }

        // ------------------------------------------------------------------
        // Sección 2: texto anotado
        // ------------------------------------------------------------------

        void writeAnnotatedText(DocumentAnalysis analysis) throws IOException {
            writeSectionTitle("2. Texto analizado");
            writeLegend();
            cursorY -= 4;

            for (SegmentAnalysis segment : analysis.getSegments()) {
                writeHighlightedSegment(segment);
            }
        }

        private void writeLegend() throws IOException {
            String[] labels = {"Probablemente humano (<40)", "Dudoso (40-70)", "Alta sospecha de IA (>70)"};
            float[][] accents = {SUCCESS, ACCENT, DANGER};
            float y = cursorY;
            float x = MARGIN;

            for (int i = 0; i < labels.length; i++) {
                fillRect(x, y - 2, 9, 9, accents[i]);
                drawText(labels[i], regular, 8.5f, x + 14, y, NEUTRAL_700);
                x += 14 + width(labels[i], regular, 8.5f) + 16;
            }
            cursorY -= 18;
        }

        private void writeHighlightedSegment(SegmentAnalysis segment) throws IOException {
            float fontSize = 10f;
            float leading = 13.6f;
            float padding = 7f;
            float badgeWidth = 34f;
            float gap = 9f;
            float textX = MARGIN + badgeWidth + gap;
            float textWidth = PAGE_WIDTH - MARGIN - textX - padding;

            List<String> lines = wrap(segment.getText(), regular, fontSize, textWidth);
            float blockHeight = lines.size() * leading + 2 * padding;

            ensureSpace(blockHeight + 12);
            float top = cursorY + fontSize * 0.8f + padding;
            float bottom = top - blockHeight;

            fillRect(MARGIN, bottom, CONTENT_WIDTH, blockHeight, tintFor(segment.getCategory()));

            // Insignia con el score del segmento
            float badgeY = top - padding - 13;
            fillRect(MARGIN + 1, badgeY, badgeWidth, 13, accentFor(segment.getCategory()));
            drawText(segment.getScoreLabel(), bold, 7.5f, MARGIN + 5, badgeY + 4, WHITE);

            float lineY = cursorY;
            for (String line : lines) {
                drawText(line, regular, fontSize, textX, lineY, NEUTRAL_900);
                lineY -= leading;
            }

            cursorY = bottom - 9;
        }

        // ------------------------------------------------------------------
        // Sección 3: tabla de segmentos
        // ------------------------------------------------------------------

        private static final float TABLE_FONT = 9f;
        private static final float TABLE_LEADING = 12.5f;
        private static final float TABLE_PADDING = 5f;

        /** Índice de la columna que muestra el score (se pinta en negrita y con color). */
        private static final int SCORE_COLUMN_INDEX = 1;

        void writeSegmentTable(DocumentAnalysis analysis) throws IOException {
            writeSectionTitle("3. Análisis segmento por segmento");

            float[] widths = {26f, 44f, 122f, CONTENT_WIDTH - 26f - 44f - 122f};
            String[] headers = {"#", "Score", "Indicadores", "Explicación"};
            drawTableHeader(widths, headers);

            boolean alternate = false;
            for (SegmentAnalysis segment : analysis.getSegments()) {
                List<Cell> cells = layoutCells(segment, widths);
                float height = rowHeight(cells) + (segment.hasFalsePositiveWarning() ? TABLE_LEADING + 8 : 0);

                if (cursorY - height < BOTTOM_LIMIT) {
                    newPage();
                    drawTableHeader(widths, headers);
                }
                drawRow(cells, segment, height, alternate);
                alternate = !alternate;
            }
        }

        /** Una celda ya ajustada a su ancho, con su posición horizontal. */
        private record Cell(List<String> lines, float x, float width) {
        }

        private List<Cell> layoutCells(SegmentAnalysis segment, float[] widths) throws IOException {
            List<List<String>> contents = List.of(
                    List.of(String.valueOf(segment.getIndex() + 1)),
                    List.of(segment.getScoreLabel()),
                    wrap(indicatorsText(segment), regular, TABLE_FONT, widths[2] - 10),
                    wrap(explanationText(segment), regular, TABLE_FONT, widths[3] - 10));

            List<Cell> cells = new ArrayList<>(widths.length);
            float x = MARGIN;
            for (int i = 0; i < widths.length; i++) {
                cells.add(new Cell(contents.get(i), x, widths[i]));
                x += widths[i];
            }
            return cells;
        }

        private float rowHeight(List<Cell> cells) {
            int lines = cells.stream().mapToInt(cell -> cell.lines().size()).max().orElse(1);
            return Math.max(22f, lines * TABLE_LEADING + 2 * TABLE_PADDING);
        }

        private void drawTableHeader(float[] widths, String[] headers) throws IOException {
            float height = 20f;
            ensureSpace(height + 8);
            fillRect(MARGIN, cursorY - 6, CONTENT_WIDTH, height, PRIMARY);

            float x = MARGIN;
            for (int i = 0; i < headers.length; i++) {
                drawText(headers[i], bold, 9.5f, x + 5, cursorY, WHITE);
                x += widths[i];
            }
            cursorY -= height;
        }

        private void drawRow(List<Cell> cells, SegmentAnalysis segment, float height,
                             boolean alternate) throws IOException {
            float top = cursorY + TABLE_FONT * 0.8f;
            float bottom = top - height;
            float textHeight = rowHeight(cells);
            float rowBottom = top - textHeight;

            if (alternate) {
                fillRect(MARGIN, rowBottom, CONTENT_WIDTH, textHeight, NEUTRAL_50);
            }

            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                boolean isScore = (i == SCORE_COLUMN_INDEX);
                float lineY = cursorY - TABLE_PADDING;
                for (String line : cell.lines()) {
                    drawText(line, isScore ? bold : regular, TABLE_FONT, cell.x() + 5, lineY,
                            isScore ? accentFor(segment.getCategory()) : NEUTRAL_900);
                    lineY -= TABLE_LEADING;
                }
            }

            // Advertencia de falso positivo, destacada bajo la fila
            if (segment.hasFalsePositiveWarning()) {
                float warningY = rowBottom - TABLE_LEADING - 3;
                fillRect(MARGIN, rowBottom - TABLE_LEADING - 6, CONTENT_WIDTH, TABLE_LEADING + 6, TINT_DOUBTFUL);
                List<String> warningLines = wrap("Posible falso positivo: " + segment.getFalsePositiveWarning(),
                        oblique, 8.5f, CONTENT_WIDTH - 12);
                float wy = rowBottom - 8;
                for (String line : warningLines) {
                    drawText(line, oblique, 8.5f, MARGIN + 6, wy, NEUTRAL_700);
                    wy -= 11;
                }
                rowBottom -= warningLines.size() * 11f + 6;
            }

            // Separador inferior de la fila
            stream.setStrokingColor(color(NEUTRAL_300));
            stream.setLineWidth(0.5f);
            stream.moveTo(MARGIN, rowBottom);
            stream.lineTo(PAGE_WIDTH - MARGIN, rowBottom);
            stream.stroke();

            cursorY = rowBottom;
        }

        private String indicatorsText(SegmentAnalysis segment) {
            if (!segment.hasIndicators()) {
                return segment.hasError() ? "—" : "sin indicadores";
            }
            return String.join("\n", segment.getIndicators());
        }

        private String explanationText(SegmentAnalysis segment) {
            if (segment.hasError()) {
                return segment.getErrorMessage();
            }
            return segment.getExplanation() == null ? "Sin explicación." : segment.getExplanation();
        }

        // ------------------------------------------------------------------
        // Sección 4: advertencias metodológicas
        // ------------------------------------------------------------------

        void writeMethodologicalNotes() throws IOException {
            writeSectionTitle("4. Advertencias y limitaciones metodológicas");

            writeParagraph("Este informe es orientativo y no constituye prueba de uso de IA. "
                    + "Antes de extraer conclusiones, ten en cuenta lo siguiente:", regular, 10f, NEUTRAL_900);
            cursorY -= 4;

            writeBullet("Los resultados pueden contener falsos positivos, especialmente en escritores "
                    + "no nativos, en estilos muy formales o técnicos, y en textos muy revisados o "
                    + "corregidos varias veces.");
            writeBullet("Los modelos de detección trabajan con probabilidades, no con certezas. Un score "
                    + "alto indica parecido con patrones habituales de la IA, no autoría demostrada.");
            writeBullet("Un texto humano puede puntuar alto y un texto generado por IA puede puntuar bajo, "
                    + "sobre todo si ha sido reescrito o adaptado después.");
            writeBullet("El análisis se realiza párrafo a párrafo, sin acceso a metadatos del archivo, "
                    + "historial de edición ni al proceso de escritura del estudiante.");
            writeBullet("Los resultados no se conservan en disco: viven en memoria y se eliminan "
                    + "automáticamente unas horas después del análisis.");
            writeBullet("Uso recomendado: utilizar el informe como punto de partida para un diálogo con el "
                    + "estudiante sobre su proceso de escritura, nunca como veredicto automático.");

            cursorY -= 8;
            writeParagraph("Para cualquier duda sobre la interpretación de este informe, escribe a "
                    + contactEmail + ".", oblique, 9f, NEUTRAL_500);
        }

        private void writeBullet(String text) throws IOException {
            float indent = 14f;
            List<String> lines = wrap(text, regular, 9.5f, CONTENT_WIDTH - indent - 4);
            ensureSpace(lines.size() * 13f + 6);

            drawText("•", bold, 9.5f, MARGIN + 2, cursorY, PRIMARY);
            for (String line : lines) {
                drawText(line, regular, 9.5f, MARGIN + indent, cursorY, NEUTRAL_700);
                cursorY -= 13f;
            }
            cursorY -= 3;
        }

        // ------------------------------------------------------------------
        // Pie de página y utilidades de dibujo
        // ------------------------------------------------------------------

        /** Cierra el flujo de contenido y numera todas las páginas con el aviso legal. */
        void finish() throws IOException {
            closeStream();
            int totalPages = document.getNumberOfPages();

            for (int i = 0; i < totalPages; i++) {
                PDPage current = document.getPage(i);
                try (PDPageContentStream footer = new PDPageContentStream(document, current,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {

                    footer.setStrokingColor(color(NEUTRAL_300));
                    footer.setLineWidth(0.6f);
                    footer.moveTo(MARGIN, FOOTER_BASELINE + 12);
                    footer.lineTo(PAGE_WIDTH - MARGIN, FOOTER_BASELINE + 12);
                    footer.stroke();

                    footerText(footer, "TextOrigin · Informe orientativo: no constituye prueba de uso de IA.",
                            MARGIN, FOOTER_BASELINE);
                    footerText(footer, contactEmail, MARGIN, FOOTER_BASELINE - 10);

                    String pageLabel = "Página " + (i + 1) + " de " + totalPages;
                    float labelWidth = footerWidth(pageLabel);
                    footerText(footer, pageLabel, PAGE_WIDTH - MARGIN - labelWidth, FOOTER_BASELINE);
                }
            }
        }

        private void footerText(PDPageContentStream footer, String text, float x, float y) throws IOException {
            footer.beginText();
            footer.setFont(regular, 7.5f);
            footer.setNonStrokingColor(color(NEUTRAL_500));
            footer.newLineAtOffset(x, y);
            footer.showText(sanitize(text, regular));
            footer.endText();
        }

        private float footerWidth(String text) throws IOException {
            return width(sanitize(text, regular), regular, 7.5f);
        }

        private void writeSectionTitle(String title) throws IOException {
            ensureSpace(72);
            cursorY -= 24;
            fillRect(MARGIN, cursorY - 7, CONTENT_WIDTH, 24, NEUTRAL_100);
            fillRect(MARGIN, cursorY - 7, 4, 24, PRIMARY);
            drawText(title, bold, 13f, MARGIN + 12, cursorY, PRIMARY);
            cursorY -= 26;
        }

        private void writeParagraph(String text, PDFont font, float size, float[] rgb) throws IOException {
            List<String> lines = wrap(text, font, size, CONTENT_WIDTH);
            float leading = size * 1.45f;

            for (String line : lines) {
                ensureSpace(leading);
                drawText(line, font, size, MARGIN, cursorY, rgb);
                cursorY -= leading;
            }
        }

        private void newPage() throws IOException {
            closeStream();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            cursorY = PAGE_HEIGHT - MARGIN;
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private void ensureSpace(float height) throws IOException {
            if (cursorY - height < BOTTOM_LIMIT) {
                newPage();
            }
        }

        private void drawText(String text, PDFont font, float size, float x, float y, float[] rgb)
                throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color(rgb));
            stream.newLineAtOffset(x, y);
            stream.showText(sanitize(text, font));
            stream.endText();
        }

        private void fillRect(float x, float y, float width, float height, float[] rgb) throws IOException {
            stream.setNonStrokingColor(color(rgb));
            stream.addRect(x, y, width, height);
            stream.fill();
        }

        private float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        private static PDColor color(float[] rgb) {
            return new PDColor(rgb, PDDeviceRGB.INSTANCE);
        }

        /**
         * Sustituye los caracteres que la fuente Helvetica (WinAnsi) no puede representar,
         * para que el informe siempre se genere aunque el texto analizado incluya emojis u
         * otros alfabetos.
         */
        private static String sanitize(String text, PDFont font) {
            if (text == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                if (character == '\n' || character == '\r' || character == '\t') {
                    builder.append(' ');
                    continue;
                }
                try {
                    font.encode(String.valueOf(character));
                    builder.append(character);
                } catch (IOException | IllegalArgumentException e) {
                    builder.append('?');
                }
            }
            return builder.toString();
        }

        /** Ajusta un texto al ancho indicado respetando los saltos de línea explícitos. */
        private List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            String clean = sanitize(text, font);

            for (String paragraph : clean.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.trim().split("\\s+")) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (width(candidate, font, size) <= maxWidth) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    // Palabra más ancha que la columna: se parte por caracteres.
                    String rest = word;
                    while (width(rest, font, size) > maxWidth && rest.length() > 1) {
                        int cut = rest.length() - 1;
                        while (cut > 1 && width(rest.substring(0, cut), font, size) > maxWidth) {
                            cut--;
                        }
                        lines.add(rest.substring(0, cut));
                        rest = rest.substring(cut);
                    }
                    line.append(rest);
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                }
            }

            return lines.isEmpty() ? List.of("") : lines;
        }

        private static float[] tintFor(SegmentAnalysis.Category category) {
            return switch (category) {
                case HUMAN -> TINT_HUMAN;
                case DOUBTFUL -> TINT_DOUBTFUL;
                case AI -> TINT_AI;
                case ERROR, PENDING -> TINT_PENDING;
            };
        }

        private static float[] accentFor(SegmentAnalysis.Category category) {
            return switch (category) {
                case HUMAN -> SUCCESS;
                case DOUBTFUL -> ACCENT;
                case AI -> DANGER;
                case ERROR, PENDING -> NEUTRAL_500;
            };
        }

        private static String labelFor(SegmentAnalysis.Category category) {
            return switch (category) {
                case HUMAN -> "probablemente humano";
                case DOUBTFUL -> "dudoso";
                case AI -> "alta sospecha de IA";
                case ERROR, PENDING -> "sin datos";
            };
        }
    }
}
