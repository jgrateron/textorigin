package com.textorigin.service;

import com.textorigin.exception.TextExtractionException;
import com.textorigin.exception.TextExtractionException.Reason;
import com.textorigin.model.Document;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Extrae el texto plano de los documentos que sube el profesorado.
 *
 * <p>Formatos soportados:</p>
 * <ul>
 *   <li><strong>.pdf</strong> — Apache PDFBox 3 ({@link Loader#loadPDF(byte[])}).</li>
 *   <li><strong>.docx</strong> — Apache POI ({@link XWPFDocument}), incluidos los párrafos
 *       que viven dentro de tablas.</li>
 *   <li><strong>.txt</strong> — texto plano, detectando UTF-8 y recurriendo a Windows-1252
 *       si la decodificación falla (habitual en archivos generados en Windows).</li>
 * </ul>
 *
 * <p>Todos los caminos terminan en {@link #normalize(String)} y en una comprobación de
 * longitud mínima, de modo que el resto de la aplicación siempre trabaja con texto
 * normalizado y suficientemente largo como para que el análisis tenga sentido.</p>
 */
@Slf4j
@Service
public class TextExtractionService {

    /** Tamaño máximo aceptado por archivo: 10 MB. */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** Extensiones admitidas. */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    /** Longitud mínima del texto para poder analizarlo. */
    private final int minTextLength;

    public TextExtractionService(@Value("${textorigin.analysis.min-text-length:100}") int minTextLength) {
        this.minTextLength = minTextLength;
    }

    /**
     * Extrae, normaliza y valida el texto de un archivo subido.
     *
     * @param file archivo recibido en el formulario
     * @return texto normalizado y listo para segmentar
     * @throws TextExtractionException si el archivo es demasiado grande, tiene un formato no
     *                                 soportado, está dañado o su texto es insuficiente
     */
    public String extractText(MultipartFile file) {
        validateFile(file);
        String extension = extensionOf(file.getOriginalFilename());
        byte[] bytes = readBytes(file);

        String rawText = switch (extension) {
            case "pdf" -> extractFromPdf(bytes);
            case "docx" -> extractFromDocx(bytes);
            case "txt" -> decodePlainText(bytes);
            default -> throw new TextExtractionException(
                    "Formato no soportado: ." + extension + ". Sube un archivo PDF, DOCX o TXT.",
                    Reason.UNSUPPORTED_FORMAT);
        };

        String text = normalize(rawText);
        log.info("Texto extraído de '{}' ({}): {} caracteres, {} párrafos",
                file.getOriginalFilename(), extension, text.length(), countParagraphs(text));
        validateLength(text);
        return text;
    }

    /**
     * Normaliza y valida el texto pegado directamente en el formulario.
     *
     * @param text texto pegado por el usuario
     * @return texto normalizado y listo para segmentar
     * @throws TextExtractionException si no hay texto o es demasiado corto
     */
    public String validatePastedText(String text) {
        String normalized = normalize(text);
        validateLength(normalized);
        log.info("Texto pegado validado: {} caracteres, {} párrafos",
                normalized.length(), countParagraphs(normalized));
        return normalized;
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
    // Lectura por formato
    // ==================================================================

    private String extractFromPdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.getNumberOfPages() == 0) {
                throw new TextExtractionException("El PDF no contiene ninguna página.", Reason.UNREADABLE_FILE);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(pdf);
            log.debug("PDF procesado con {} páginas", pdf.getNumberOfPages());
            return text;
        } catch (InvalidPasswordException e) {
            throw new TextExtractionException(
                    "El PDF está protegido con contraseña y no se puede leer.", Reason.UNREADABLE_FILE, e);
        } catch (IOException e) {
            throw new TextExtractionException(
                    "No se pudo leer el PDF. Comprueba que el archivo no esté dañado.", Reason.UNREADABLE_FILE, e);
        }
    }

    private String extractFromDocx(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        try (XWPFDocument docx = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : docx.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        builder.append(text.strip()).append("\n\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    appendTable(table, builder);
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

    private void appendTable(XWPFTable table, StringBuilder builder) {
        for (XWPFTableRow row : table.getRows()) {
            String rowText = row.getTableCells().stream()
                    .map(cell -> cell.getText() == null ? "" : cell.getText().strip())
                    .collect(Collectors.joining(" | "));
            if (!rowText.isBlank()) {
                builder.append(rowText).append("\n\n");
            }
        }
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
}
