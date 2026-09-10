package com.textorigin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Respuesta del endpoint {@code siteverify} de Cloudflare Turnstile.
 *
 * <p>Únicamente se modelan los campos que utiliza TextOrigin: si el reto se ha superado y,
 * cuando no es así, los códigos de error que se registran en el log para poder diagnosticar
 * la causa (token caducado, clave secreta inválida, dominio no permitido...).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnstileResponse {

    /** Indica si el token es válido y el visitante ha superado el reto. */
    private boolean success;

    /**
     * Códigos de error de Cloudflare cuando {@code success} es falso (por ejemplo
     * {@code invalid-input-response} o {@code timeout-or-duplicate}).
     */
    @JsonProperty("error-codes")
    private List<String> errorCodes;
}
