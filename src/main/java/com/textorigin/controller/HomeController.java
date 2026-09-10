package com.textorigin.controller;

import com.textorigin.service.CaptchaService;
import com.textorigin.service.QuotaService;
import com.textorigin.service.TextExtractionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * Página principal de TextOrigin.
 *
 * <p>Muestra el formulario de carga (arrastrar y soltar, o texto pegado) y el indicador de
 * análisis disponibles hoy desde la conexión del visitante.</p>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class HomeController {

    private final QuotaService quotaService;
    private final CaptchaService captchaService;

    /**
     * Página de inicio.
     *
     * @param request petición en curso, para calcular la cuota del visitante
     * @param model   modelo de la vista
     * @return la plantilla {@code index}
     */
    @GetMapping("/")
    public String index(HttpServletRequest request, Model model) {
        addQuotaAttributes(request, model);
        // El fragmento del formulario lee estas variables para pintar el widget de Turnstile
        // solo cuando la verificación está configurada.
        model.addAttribute("captchaEnabled", captchaService.isEnabled());
        model.addAttribute("captchaSiteKey", captchaService.getSiteKey());
        model.addAttribute("supportedExtensions", TextExtractionService.supportedExtensions());
        model.addAttribute("maxFileSizeMb", TextExtractionService.MAX_FILE_SIZE_BYTES / (1024 * 1024));
        model.addAttribute("minTextLength", 100);
        return "index";
    }

    /**
     * Devuelve únicamente el indicador de cuota.
     *
     * <p>Lo solicita la propia página cuando un análisis termina, para que la barra de
     * análisis disponibles se actualice sin recargar.</p>
     *
     * @param request petición en curso
     * @param model   modelo de la vista
     * @return el fragmento del indicador de cuota
     */
    @GetMapping("/quota")
    public String quota(HttpServletRequest request, Model model) {
        addQuotaAttributes(request, model);
        return "fragments/quota-indicator :: indicator";
    }

    /**
     * Comprobación de salud del servicio.
     *
     * <p>La usan el {@code HEALTHCHECK} de la imagen Docker y los balanceadores de carga. Es
     * deliberadamente ligera: no crea sesión, no toca la cuota y no consulta a DeepSeek, de
     * modo que puede invocarse con frecuencia sin efectos secundarios.</p>
     *
     * @return el estado del servicio en JSON
     */
    @GetMapping("/health")
    @ResponseBody
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "application", "textorigin");
    }

    private void addQuotaAttributes(HttpServletRequest request, Model model) {
        model.addAttribute("quota", quotaService.getStatus(request));
        model.addAttribute("contactEmail", quotaService.getContactEmail());
    }
}
