package com.textorigin.service;

import com.textorigin.exception.TextExtractionException;
import com.textorigin.exception.TextExtractionException.Reason;
import com.textorigin.model.Document;
import com.textorigin.model.DocumentWarning;
import com.textorigin.model.HiddenSpan;
import com.textorigin.model.SanitizedText;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Extrae el texto plano de los documentos que sube el profesorado.
 *
 * <p>Formatos soportados:</p>
 * <ul>
 *   <li><strong>.pdf</strong> — Apache PDFBox 3 ({@link Loader#loadPDF(byte[])}), descartando el
 *       texto oculto con {@link HiddenTextPdfStripper}.</li>
 *   <li><strong>.docx</strong> — Apache POI ({@link XWPFDocument}), incluidos los párrafos que
 *       viven dentro de tablas y los runs con formato oculto ({@code w:vanish}, blanco o
 *       diminuto).</li>
 *   <li><strong>.txt</strong> — texto plano, detectando UTF-8 y recurriendo a Windows-1252 si la
 *       decodificación falla (habitual en archivos generados en Windows).</li>
 * </ul>
 *
 * <p>Sea cual sea el origen, el texto pasa por {@link InvisibleCharacterSanitizer} (caracteres
 * Unicode que no se ven), por {@link #normalize(String)} y por una comprobación de longitud
 * mínima. Los hallazgos viajan en el {@link SanitizedText} que devuelven
 * {@link #extractTextWithFindings} y {@link #validatePastedTextWithFindings}: el aviso al
 * profesor se construye con ellos y nunca se descarta texto en silencio.</p>
 *
 * <p><strong>Guarda de proporción:</strong> si el texto «oculto» supera la mitad del documento
 * (típico de las capas OCR de un PDF escaneado, o de un intento de dejar el análisis sin
 * contenido), no se descarta nada: se analiza el documento completo y se avisa.</p>
 */
@Slf4j
@Service
public class TextExtractionService {

    /** Tamaño máximo aceptado por archivo: 10 MB. */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** Extensiones admitidas. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    /** Porcentaje de texto oculto a partir del cual no se descarta nada. */
    private static final int HIDDEN_RATIO_PERCENT = 50;

    /** Máximo de extractos que se citan por cada aviso de formato oculto. */
    private static final int MAX_EXCERPTS_PER_WARNING = 3;

    /** Máximo de localizaciones que se resumen en un aviso. */
    private static final int MAX_LOCATIONS = 5;

    /** Longitud mínima del texto para poder analizarlo. */
    private final int minTextLength;

    private final InvisibleCharacterSanitizer invisibleCharacterSanitizer;

    public TextExtractionService(@Value("${textorigin.analysis.min-text-length:100}") int minTextLength,
                                 InvisibleCharacterSanitizer invisibleCharacterSanitizer) {
        this.minTextLength = minTextLength;
        this.invisibleCharacterSanitizer = invisibleCharacterSanitizer;
    }

    /**
     * Extrae, sanea, normaliza y valida el texto de un archivo subido.
     *
     * @param file archivo recibido en el formulario
     * @return el texto listo para segmentar y los avisos de las defensas
     * @throws TextExtractionException si el archivo es demasiado grande, tiene un formato no
     *                                 soportado, está dañado o su texto es insuficiente
     */
    public SanitizedText extractTextWithFindings(MultipartFile file) {
        validateFile(file);
        String extension = extensionOf(file.getOriginalFilename());
        byte[] bytes = readBytes(file);

        RawExtraction raw = switch (extension) {
            case "pdf" -> extractFromPdf(bytes);
            case "docx" -> extractFromDocx(bytes);
            case "txt" -> RawExtraction.of(decodePlainText(bytes));
            default -> throw new TextExtractionException(
                    "Formato no soportado: ." + extension + ". Sube un archivo PDF, DOCX o TXT.",
                    Reason.UNSUPPORTED_FORMAT);
        };

        return finish(raw, "'" + file.getOriginalFilename() + "' (" + extension + ")");
    }

    /**
     * Sanea, normaliza y valida el texto pegado directamente en el formulario.
     *
     * @param text texto pegado por el usuario
     * @return el texto listo para segmentar y los avisos de las defensas
     * @throws TextExtractionException si no hay texto o es demasiado corto
     */
    public SanitizedText validatePastedTextWithFindings(String text) {
        return finish(RawExtraction.of(text), "texto pegado");
    }

    /**
     * Versión de {@link #extractTextWithFindings} que solo devuelve el texto.
     *
     * @param file archivo recibido en el formulario
     * @return texto normalizado y listo para segmentar
     */
    public String extractText(MultipartFile file) {
        return extractTextWithFindings(file).text();
    }

    /**
     * Versión de {@link #validatePastedTextWithFindings} que solo devuelve el texto.
     *
     * @param text texto pegado por el usuario
     * @return texto normalizado y listo para segmentar
     */
    public String validatePastedText(String text) {
        return validatePastedTextWithFindings(text).text();
    }

    /**
     * Construye el documento de dominio a partir del texto ya extraído.
     *
     * @param text        texto normalizado
     * @param fileName    nombre original del archivo o etiqueta descriptiva
     * @param contentType tipo MIME declarado
     * @param source      origen del texto
     * @return el documento listo para analizar
     */
    public Document createDocument(String text, String fileName, String contentType, Document.Source source) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .fileName(fileName)
                .contentType(contentType)
                .source(source)
                .text(text)
                .characterCount(text.length())
                .wordCount(TextSegmentationService.countWords(text))
                .paragraphCount(countParagraphs(text))
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Normaliza el texto para que el análisis y el resaltado sean consistentes:
     * unifica los saltos de línea, elimina espacios duros y de control, recorta los
     * espacios sobrantes al final de cada línea, reduce las líneas en blanco
     * consecutivas a una sola separación de párrafo y elimina el BOM.
     *
     * @param text texto de entrada, posiblemente {@code null}
     * @return texto normalizado, nunca {@code null}
     */
    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text
                .replace("﻿", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(' ', ' ')
                .replace(' ', ' ')
                .replace(' ', ' ')
                .replace("\t", " ");

        normalized = normalized.lines()
                .map(String::stripTrailing)
                .collect(Collectors.joining("\n"));

        normalized = normalized.replaceAll(" {2,}", " ")
                .replaceAll("\n{3,}", "\n\n");

        return normalized.strip();
    }

    /** Cuenta los párrafos de un texto normalizado. */
    public int countParagraphs(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.split("\n\\s*\n").length;
    }

    // ==================================================================
    // Saneado y cierre común
    // ==================================================================

    /** Aplica el saneado de invisibles, la normalización y la validación de longitud. */
    private SanitizedText finish(RawExtraction raw, String description) {
        SanitizedText sanitized = invisibleCharacterSanitizer.sanitize(raw.text());

        List<DocumentWarning> warnings = new ArrayList<>(raw.warnings());
        warnings.addAll(sanitized.warnings());

        String text = normalize(sanitized.text());
        log.info("Texto de {}: {} caracteres, {} párrafos, {} avisos de defensa",
                description, text.length(), countParagraphs(text), warnings.size());
        validateLength(text);
        return new SanitizedText(text, warnings);
    }

    // ==================================================================
    // Lectura por formato
    // ==================================================================

    /**
     * Extrae el PDF con {@link HiddenTextPdfStripper} y, si la proporción de texto «oculto»
     * delata una capa OCR (o un documento casi todo oculto), lo extrae completo y avisa.
     */
    private RawExtraction extractFromPdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.getNumberOfPages() == 0) {
                throw new TextExtractionException("El PDF no contiene ninguna página.", Reason.UNREADABLE_FILE);
            }
            try {
                HiddenTextPdfStripper stripper =
                        new HiddenTextPdfStripper(HiddenTextPdfStripper.PageProfile.measure(pdf));
                String text = stripper.getText(pdf);
                log.debug("PDF procesado con {} páginas: {} caracteres visibles y {} ocultos",
                        pdf.getNumberOfPages(), stripper.getVisibleCharCount(), stripper.getHiddenCharCount());

                if (exceedsHiddenRatio(stripper.getHiddenCharCount(), stripper.getVisibleCharCount())) {
                    int percent = hiddenPercent(stripper.getHiddenCharCount(), stripper.getVisibleCharCount());
                    log.warn("El {} % del texto del PDF está marcado como oculto (¿capa OCR?): se analiza completo",
                            percent);
                    return new RawExtraction(plainPdfText(pdf), List.of(DocumentWarning.hiddenTextMajority(percent)));
                }
                return new RawExtraction(text,
                        hiddenFormatWarnings(stripper.getHiddenSpans(), stripper.getHiddenCharCount()));
            } catch (RuntimeException e) {
                // La inspección del formato nunca puede impedir el análisis.
                log.warn("No se pudo inspeccionar el texto oculto del PDF ({}); se extrae completo", e.getMessage());
                return new RawExtraction(plainPdfText(pdf),
                        List.of(DocumentWarning.defenseUnavailable("no se pudo inspeccionar el formato del PDF")));
            }
        } catch (InvalidPasswordException e) {
            throw new TextExtractionException(
                    "El PDF está protegido con contraseña y no se puede leer.", Reason.UNREADABLE_FILE, e);
        } catch (IOException e) {
            throw new TextExtractionException(
                    "No se pudo leer el PDF. Comprueba que el archivo no esté dañado.", Reason.UNREADABLE_FILE, e);
        }
    }

    /** Extracción sin defensas, usada como respaldo cuando no se puede inspeccionar el formato. */
    private static String plainPdfText(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(pdf);
    }

    /** Extrae el DOCX con o sin sus runs ocultos, según el estado de la guarda de proporción. */
    private RawExtraction extractFromDocx(byte[] bytes) {
        HiddenCollector hidden = new HiddenCollector();
        String visibleText = scanDocx(bytes, false, hidden);

        if (exceedsHiddenRatio(hidden.charCount(), visibleText.length())) {
            int percent = hiddenPercent(hidden.charCount(), visibleText.length());
            log.warn("El {} % del texto del DOCX está marcado como oculto: se analiza completo", percent);
            return new RawExtraction(scanDocx(bytes, true, new HiddenCollector()),
                    List.of(DocumentWarning.hiddenTextMajority(percent)));
        }
        return new RawExtraction(visibleText, hiddenFormatWarnings(hidden.samples(), hidden.charCount()));
    }

    /**
     * Recorre el cuerpo del DOCX —párrafos, tablas y celdas con tablas anidadas— y compone su
     * texto. Con {@code includeHidden} se conserva todo; si no, los runs ocultos se descartan y
     * se recogen en el colector.
     */
    private String scanDocx(byte[] bytes, boolean includeHidden, HiddenCollector hidden) {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            int paragraphIndex = 0;
            int tableIndex = 0;
            for (IBodyElement element : docx.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    paragraphIndex++;
                    appendParagraph(builder, paragraph, "párrafo " + paragraphIndex, includeHidden, hidden);
                } else if (element instanceof XWPFTable table) {
                    tableIndex++;
                    appendTable(builder, table, "tabla " + tableIndex, includeHidden, hidden);
                }
            }
        } catch (IOException e) {
            throw new TextExtractionException(
                    "No se pudo leer el documento DOCX. Comprueba que el archivo no esté dañado.",
                    Reason.UNREADABLE_FILE, e);
        } catch (RuntimeException e) {
            // POI lanza excepciones no comprobadas (OOXML mal formado, zip corrupto...).
            throw new TextExtractionException(
                    "El archivo DOCX no tiene un formato válido.", Reason.UNREADABLE_FILE, e);
        }
        return builder.toString();
    }

    private static void appendParagraph(StringBuilder builder, XWPFParagraph paragraph, String location,
                                        boolean includeHidden, HiddenCollector hidden) {
        String text = paragraphText(paragraph, location, includeHidden, hidden);
        if (!text.isBlank()) {
            builder.append(text.strip()).append("\n\n");
        }
    }

    private static String paragraphText(XWPFParagraph paragraph, String location, boolean includeHidden,
                                        HiddenCollector hidden) {
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText == null || runText.isEmpty()) {
                continue;
            }
            String reason = includeHidden ? null : hiddenRunReason(run);
            if (reason == null) {
                text.append(runText);
            } else {
                hidden.add(location, reason, runText);
            }
        }
        return text.toString();
    }

    private static void appendTable(StringBuilder builder, XWPFTable table, String location,
                                    boolean includeHidden, HiddenCollector hidden) {
        int rowIndex = 0;
        for (XWPFTableRow row : table.getRows()) {
            rowIndex++;
            String rowLocation = location + ", fila " + rowIndex;
            String rowText = row.getTableCells().stream()
                    .map(cell -> cellText(cell, rowLocation, includeHidden, hidden).strip())
                    .collect(Collectors.joining(" | "));
            if (!rowText.isBlank()) {
                builder.append(rowText).append("\n\n");
            }
        }
    }

    private static String cellText(XWPFTableCell cell, String location, boolean includeHidden,
                                   HiddenCollector hidden) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String cellParagraphText = paragraphText(paragraph, location, includeHidden, hidden);
            if (!cellParagraphText.isBlank()) {
                text.append(cellParagraphText.strip()).append(' ');
            }
        }
        for (XWPFTable nested : cell.getTables()) {
            StringBuilder nestedText = new StringBuilder();
            appendTable(nestedText, nested, location, includeHidden, hidden);
            text.append(nestedText);
        }
        return text.toString();
    }

    /** Motivo por el que un run de DOCX se considera oculto, o {@code null} si es visible. */
    private static String hiddenRunReason(XWPFRun run) {
        if (run.isVanish()) {
            return "texto oculto (w:vanish)";
        }
        if (HiddenFormatRules.isWhiteish(run.getColor())) {
            return "color blanco";
        }
        Double fontSize = run.getFontSizeAsDouble();
        if (fontSize != null && HiddenFormatRules.isTooSmall(fontSize)) {
            return "tamaño diminuto";
        }
        return null;
    }

    /**
     * Decodifica texto plano intentando primero UTF-8. Si el archivo no es UTF-8 válido
     * (típico en documentos guardados en Windows con acentos), se reintenta con Windows-1252.
     */
    private String decodePlainText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            log.debug("El archivo TXT no es UTF-8 válido; se decodifica como Windows-1252");
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    // ==================================================================
    // Avisos de contenido oculto
    // ==================================================================

    /**
     * Agrupa los fragmentos descartados por motivo y prepara un aviso por grupo, con hasta
     * {@value #MAX_EXCERPTS_PER_WARNING} extractos del texto que ocultaban.
     */
    private static List<DocumentWarning> hiddenFormatWarnings(List<HiddenSpan> spans, int hiddenCharCount) {
        if (hiddenCharCount <= 0) {
            return List.of();
        }
        Map<String, List<HiddenSpan>> byReason = new LinkedHashMap<>();
        for (HiddenSpan span : spans) {
            if (span.hasText()) {
                byReason.computeIfAbsent(span.reason(), reason -> new ArrayList<>()).add(span);
            }
        }

        List<DocumentWarning> warnings = new ArrayList<>();
        for (Map.Entry<String, List<HiddenSpan>> entry : byReason.entrySet()) {
            List<HiddenSpan> group = entry.getValue();
            int groupChars = group.stream().mapToInt(span -> span.text().length()).sum();
            int count = byReason.size() == 1 ? hiddenCharCount : groupChars;
            warnings.add(DocumentWarning.hiddenFormat(entry.getKey(), count,
                    summarizeLocations(group), hiddenExcerpts(group)));
        }
        if (warnings.isEmpty()) {
            warnings.add(DocumentWarning.hiddenFormat("formato oculto", hiddenCharCount, "", List.of()));
        }
        return warnings;
    }

    /** Localizaciones distintas del grupo, resumidas para el aviso. */
    private static String summarizeLocations(List<HiddenSpan> group) {
        return group.stream()
                .map(HiddenSpan::location)
                .distinct()
                .limit(MAX_LOCATIONS)
                .collect(Collectors.joining(", "));
    }

    /** Extractos del texto oculto, en trozos legibles de como mucho {@value DocumentWarning#MAX_EXCERPT_CHARS} caracteres. */
    private static List<String> hiddenExcerpts(List<HiddenSpan> group) {
        String collapsed = group.stream()
                .map(HiddenSpan::text)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .strip();

        List<String> excerpts = new ArrayList<>();
        int cursor = 0;
        while (cursor < collapsed.length() && excerpts.size() < MAX_EXCERPTS_PER_WARNING) {
            int end = Math.min(collapsed.length(), cursor + DocumentWarning.MAX_EXCERPT_CHARS);
            if (end < collapsed.length()) {
                int space = collapsed.lastIndexOf(' ', end);
                if (space > cursor) {
                    end = space;
                }
            }
            excerpts.add(DocumentWarning.truncateExcerpt(collapsed.substring(cursor, end)));
            cursor = end + 1;
        }
        return excerpts;
    }

    /** Indica si el texto oculto alcanza el porcentaje que invalida la defensa. */
    private static boolean exceedsHiddenRatio(int hiddenChars, int visibleChars) {
        long total = (long) hiddenChars + visibleChars;
        return total > 0 && hiddenChars * 100L >= total * HIDDEN_RATIO_PERCENT;
    }

    /** Porcentaje (redondeado) de texto oculto sobre el total. */
    private static int hiddenPercent(int hiddenChars, int visibleChars) {
        long total = (long) hiddenChars + visibleChars;
        return total == 0 ? 0 : (int) Math.round(hiddenChars * 100.0 / total);
    }

    // ==================================================================
    // Validaciones
    // ==================================================================

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new TextExtractionException(
                    "No se ha recibido ningún archivo. Sube un PDF, DOCX o TXT, o pega el texto directamente.",
                    Reason.EMPTY_INPUT);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new TextExtractionException(
                    "El archivo ocupa " + readableSize(file.getSize())
                            + " y el máximo permitido es 10 MB. Divídelo en partes más pequeñas.",
                    Reason.FILE_TOO_LARGE);
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            String shown = extension.isEmpty() ? "desconocido" : "." + extension;
            throw new TextExtractionException(
                    "Formato no soportado (" + shown + "). Sube un archivo PDF, DOCX o TXT.",
                    Reason.UNSUPPORTED_FORMAT);
        }
    }

    private void validateLength(String text) {
        if (text == null || text.isBlank()) {
            throw new TextExtractionException(
                    "No se ha podido extraer texto del documento. Si es un PDF escaneado, "
                            + "necesita reconocimiento óptico de caracteres (OCR), que TextOrigin no realiza.",
                    Reason.EMPTY_INPUT);
        }
        if (text.length() < minTextLength) {
            throw new TextExtractionException(
                    "El texto tiene " + text.length() + " caracteres y se necesitan al menos "
                            + minTextLength + " para que el análisis sea mínimamente fiable.",
                    Reason.TEXT_TOO_SHORT);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new TextExtractionException(
                    "No se pudo leer el archivo subido. Inténtalo de nuevo.", Reason.UNREADABLE_FILE, e);
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String readableSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Formatos admitidos, para mostrarlos en la interfaz. */
    public static List<String> supportedExtensions() {
        return List.of(".pdf", ".docx", ".txt");
    }

    // ==================================================================
    // Tipos internos
    // ==================================================================

    /** Texto extraído y avisos de las defensas, antes de normalizar. */
    private record RawExtraction(String text, List<DocumentWarning> warnings) {

        RawExtraction {
            text = text == null ? "" : text;
            warnings = List.copyOf(warnings);
        }

        static RawExtraction of(String text) {
            return new RawExtraction(text, List.of());
        }
    }

    /** Acumula los fragmentos ocultos descartados, con un tope de muestras. */
    private static final class HiddenCollector {

        private static final int MAX_SAMPLES = 200;

        private final List<HiddenSpan> samples = new ArrayList<>();
        private int charCount;

        void add(String location, String reason, String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            charCount += text.length();
            if (samples.size() < MAX_SAMPLES) {
                samples.add(new HiddenSpan(location, reason, text));
            }
        }

        List<HiddenSpan> samples() {
            return samples;
        }

        int charCount() {
            return charCount;
        }
    }
}
