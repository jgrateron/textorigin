package com.textorigin.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Datos en bruto recibidos desde el formulario de la página principal.
 *
 * <p>Es el punto de entrada del flujo de análisis: el controlador lo construye a partir del
 * formulario, decide si hay archivo o texto pegado y delega la extracción en
 * {@code TextExtractionService}. También sirve para registrar en el log qué se ha recibido
 * sin volcar el contenido del texto.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequest {

    /** Nombre del archivo subido, si lo hay. */
    private String fileName;

    /** Tipo MIME del archivo subido, si lo hay. */
    private String contentType;

    /** Texto pegado directamente por el usuario, si lo hay. */
    private String pastedText;

    /** Indica si la petición incluía un archivo. */
    private boolean filePresent;

    /** Origen efectivo del texto, una vez resuelta la precedencia archivo &gt; texto pegado. */
    private Document.Source source;

    /** Tamaño en bytes del archivo, o 0 si no hay archivo. */
    private long fileSize;

    /** Descripción breve para trazas de log, sin incluir el contenido del documento. */
    public String describe() {
        if (filePresent) {
            return "archivo='" + fileName + "' (" + fileSize + " bytes, tipo=" + contentType + ")";
        }
        int length = pastedText == null ? 0 : pastedText.length();
        return "texto pegado (" + length + " caracteres)";
    }
}
