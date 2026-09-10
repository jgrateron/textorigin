package com.textorigin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Documento enviado a analizar.
 *
 * <p>Representa el texto ya extraído (desde PDF, DOCX, TXT o pegado directamente)
 * junto con sus metadatos de origen. Es inmutable a efectos prácticos: se construye
 * una vez tras la extracción y no se modifica durante el análisis.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    /** Origen del texto. */
    public enum Source {
        /** Texto extraído de un archivo subido (.pdf, .docx o .txt). */
        UPLOAD,
        /** Texto pegado directamente en el formulario. */
        PASTED
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm");

    /** Identificador del documento (UUID). */
    private String id;

    /** Nombre original del archivo, o una etiqueta descriptiva si el texto fue pegado. */
    private String fileName;

    /** Tipo MIME declarado por el navegador (puede ser genérico para .docx). */
    private String contentType;

    /** De dónde procede el texto. */
    private Source source;

    /** Texto normalizado que se va a analizar. */
    private String text;

    /** Número de caracteres del texto normalizado. */
    private int characterCount;

    /** Número de palabras del texto normalizado. */
    private int wordCount;

    /** Número de párrafos detectados. */
    private int paragraphCount;

    /** Momento en que se recibió el documento. */
    private LocalDateTime createdAt;

    /** Fecha de recepción formateada para mostrar en la interfaz. */
    public String getFormattedCreatedAt() {
        return createdAt == null ? "" : DATE_FORMAT.format(createdAt);
    }

    /** Extensión del archivo en minúsculas, o cadena vacía si no procede de un archivo. */
    public String getExtension() {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
