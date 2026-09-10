package com.textorigin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.textorigin.config.DeepSeekConfig;
import com.textorigin.dto.DeepSeekRequest;
import com.textorigin.dto.DeepSeekResponse;
import com.textorigin.dto.SegmentResultDto;
import com.textorigin.exception.AnalysisException;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.model.SegmentAnalysis;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Analiza los segmentos de un documento con DeepSeek.
 *
 * <p>El flujo de un análisis es el siguiente:</p>
 * <ol>
 *   <li>{@link #analyzeAsync(DocumentAnalysis, Runnable)} se invoca desde el controlador y
 *       devuelve el control inmediatamente: no bloquea la respuesta HTTP.</li>
 *   <li>Cada segmento se envía a DeepSeek en paralelo, con el prompt de cadena de pensamiento
 *       de {@code prompts/deepseek-analysis.txt}, usando el pool de hilos configurado.</li>
 *   <li>Cada respuesta se normaliza y se publica en la lista de resultados del análisis, de
 *       modo que la página de progreso va mostrando avances reales.</li>
 *   <li>Al terminar todos los segmentos se cierra el análisis y, solo entonces, se ejecuta el
 *       callback que descuenta la cuota.</li>
 * </ol>
 *
 * <p><strong>Reintentos:</strong> cada segmento se intenta hasta
 * {@code textorigin.analysis.retry-attempts} veces con espera exponencial
 * ({@code retry-delay-ms * 2^(intento-1)}), de modo que un fallo puntual de red o un error 429
 * de la API no arruine el análisis completo.</p>
 *
 * <p><strong>Vías de llamada:</strong> se usa el {@link ChatClient} de Spring AI cuando está
 * disponible y, si no lo está, se recurre a una petición HTTP directa en formato OpenAI con
 * {@link DeepSeekRequest}/{@link DeepSeekResponse}.</p>
 */
@Slf4j
@Service
public class DeepSeekAnalysisService {

    /** Marcador sustituido por el texto del segmento dentro del prompt. */
    private static final String TEXT_PLACEHOLDER = "{text}";

    /** Marcador que separa las instrucciones (mensaje de sistema) del texto (mensaje de usuario). */
    private static final String TEXT_SECTION_MARKER = "===TEXTO A ANALIZAR===";

    private final ObjectProvider<ChatClient> chatClientProvider;
    private final RestClient deepSeekRestClient;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor analysisExecutor;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:deepseek-flash}")
    private String model;

    @Value("${textorigin.model.prefer-spring-ai:false}")
    private boolean preferSpringAi;

    @Value("${spring.ai.openai.chat.options.temperature:0.2}")
    private Double temperature;

    @Value("${textorigin.analysis.retry-attempts:3}")
    private int retryAttempts;

    @Value("${textorigin.analysis.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Value("classpath:prompts/deepseek-analysis.txt")
    private Resource promptResource;

    /** Instrucciones del analista, enviadas como mensaje de sistema. */
    private String systemPrompt;

    /** Plantilla del mensaje de usuario, con el marcador {@value #TEXT_PLACEHOLDER}. */
    private String userTemplate;

    public DeepSeekAnalysisService(ObjectProvider<ChatClient> chatClientProvider,
                                   @Qualifier("deepSeekRestClient") RestClient deepSeekRestClient,
                                   ObjectMapper objectMapper,
                                   @Qualifier("analysisExecutor") ThreadPoolTaskExecutor analysisExecutor) {
        this.chatClientProvider = chatClientProvider;
        this.deepSeekRestClient = deepSeekRestClient;
        this.objectMapper = objectMapper;
        this.analysisExecutor = analysisExecutor;
    }

    /**
     * Carga el prompt desde el classpath y lo parte en sus dos zonas: las instrucciones (mensaje
     * de sistema) y la plantilla del texto (mensaje de usuario). Separarlas es la primera barrera
     * frente al contenido del documento que intente pasar por instrucciones.
     */
    @PostConstruct
    void loadPromptTemplate() {
        try (InputStream input = promptResource.getInputStream()) {
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int markerIndex = prompt.indexOf(TEXT_SECTION_MARKER);
            if (markerIndex < 0) {
                throw new IllegalStateException(
                        "El prompt de análisis no contiene el marcador " + TEXT_SECTION_MARKER);
            }
            systemPrompt = prompt.substring(0, markerIndex).strip();
            userTemplate = prompt.substring(markerIndex + TEXT_SECTION_MARKER.length()).strip();
            if (!userTemplate.contains(TEXT_PLACEHOLDER)) {
                throw new IllegalStateException(
                        "El prompt de análisis no contiene el marcador " + TEXT_PLACEHOLDER);
            }
            log.info("Prompt de análisis cargado desde '{}' ({} caracteres de instrucciones, {} de texto)",
                    promptResource.getFilename(), systemPrompt.length(), userTemplate.length());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar el prompt de análisis del classpath", e);
        }
    }

    /**
     * Lanza el análisis de un documento en segundo plano.
     *
     * @param analysis  análisis recién creado, con los segmentos en estado pendiente
     * @param onSuccess callback que se ejecuta solo si el análisis termina con resultados;
     *                  se usa para descontar la cuota del usuario
     */
    public void analyzeAsync(DocumentAnalysis analysis, Runnable onSuccess) {
        analysis.setStatus(DocumentAnalysis.Status.IN_PROGRESS);
        long startedAt = System.currentTimeMillis();

        log.info("Análisis iniciado: id={} documento='{}' segmentos={}",
                analysis.getId(), analysis.getDocumentName(), analysis.getTotalCount());

        CompletableFuture<?>[] pending = analysis.getSegments().stream()
                .map(segment -> CompletableFuture.runAsync(
                                () -> processSegment(analysis, segment), analysisExecutor)
                        .exceptionally(error -> {
                            log.error("Error inesperado al procesar el segmento {} del análisis {}",
                                    segment.getIndex(), analysis.getId(), error);
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        // Se encadena con whenComplete en lugar de bloquear con join(): el hilo que termine el
        // último segmento es el que cierra el análisis, así que no se retiene ningún hilo del pool.
        CompletableFuture.allOf(pending)
                .whenComplete((ignored, error) -> finalizeAnalysis(analysis, onSuccess, startedAt));
    }

    /**
     * Analiza un único segmento y publica el resultado en el análisis.
     *
     * <p>Los fallos no se propagan: se registran en el propio segmento para que el resto del
     * documento pueda completarse y el profesor vea exactamente qué fragmento falló.</p>
     */
    private void processSegment(DocumentAnalysis analysis, SegmentAnalysis pending) {
        int index = pending.getIndex();
        long startedAt = System.currentTimeMillis();

        try {
            SegmentResultDto result = analyseSegment(pending.getText(), index, analysis.getId());
            long duration = System.currentTimeMillis() - startedAt;
            analysis.getSegments().set(index, result.toSegmentAnalysis(index, pending.getText(), duration));
            log.debug("Segmento {} del análisis {} analizado en {} ms (score={})",
                    index, analysis.getId(), duration, result.normalizedScore());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startedAt;
            log.warn("El segmento {} del análisis {} no pudo analizarse en {} ms: {}",
                    index, analysis.getId(), duration, e.getMessage());
            analysis.getSegments().set(index, SegmentAnalysis.builder()
                    .index(index)
                    .text(pending.getText())
                    .errorMessage(userFacingMessage(e))
                    .durationMs(duration)
                    .build());
        }
    }

    /**
     * Envía un segmento al modelo, con reintentos y espera exponencial.
     *
     * @param text       texto del segmento
     * @param index      índice del segmento (para las trazas)
     * @param analysisId identificador del análisis (para las trazas)
     * @return el resultado normalizado del modelo
     * @throws AnalysisException si se agotan los reintentos
     */
    private SegmentResultDto analyseSegment(String text, int index, String analysisId) {
        requireApiKey();

        String userPrompt = userTemplate.replace(TEXT_PLACEHOLDER, text);
        AnalysisException lastError = null;

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                String raw = callModel(systemPrompt, userPrompt);
                return parseResponse(raw, index, analysisId);
            } catch (AnalysisException e) {
                lastError = e;
                if (attempt < retryAttempts) {
                    long delay = retryDelayMs * (1L << (attempt - 1));
                    log.warn("Intento {}/{} fallido para el segmento {} del análisis {}: {}. Reintento en {} ms",
                            attempt, retryAttempts, index, analysisId, e.getMessage(), delay);
                    sleep(delay);
                }
            }
        }
        throw lastError != null ? lastError
                : new AnalysisException("No se pudo analizar el segmento tras " + retryAttempts + " intentos.");
    }

    /**
     * Elige la vía de llamada al modelo.
     *
     * <p>Por defecto se usa la petición HTTP directa: es la única que puede enviar parámetros
     * propios de DeepSeek, como desactivar el modo de razonamiento
     * ({@code thinking: {"type": "disabled"}}), que la versión 1.0.0 de Spring AI no admite y
     * cuyo valor por defecto —razonar— ignoraría {@code temperature} y gastaría tokens de salida
     * que este análisis no aprovecha. Con {@code textorigin.model.prefer-spring-ai=true} se usa
     * el {@link ChatClient} cuando existe; ambas vías siguen operativas.</p>
     */
    private String callModel(String systemPrompt, String userPrompt) {
        ChatClient chatClient = chatClientProvider.getIfAvailable();
        if (preferSpringAi) {
            if (chatClient != null) {
                log.debug("Llamada a DeepSeek por la vía de Spring AI (prefer-spring-ai=true)");
                return callWithSpringAi(chatClient, systemPrompt, userPrompt);
            }
            log.debug("Se prefiere Spring AI pero no hay ChatClient; se usa la vía HTTP directa");
        }
        return callWithRestClient(systemPrompt, userPrompt);
    }

    private String callWithSpringAi(ChatClient chatClient, String systemPrompt, String userPrompt) {
        try {
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new AnalysisException("DeepSeek devolvió una respuesta vacía.");
            }
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                log.debug("Consumo de tokens (Spring AI): {}", response.getMetadata().getUsage().getTotalTokens());
            }
            String content = response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new AnalysisException("DeepSeek devolvió una respuesta vacía.");
            }
            return content;
        } catch (AnalysisException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AnalysisException(friendlyMessage("Fallo al llamar a DeepSeek: " + rootMessage(e)), e);
        }
    }

    private String callWithRestClient(String systemPrompt, String userPrompt) {
        DeepSeekRequest request = DeepSeekRequest.forPrompt(model, systemPrompt, userPrompt, temperature);
        try {
            DeepSeekResponse response = deepSeekRestClient.post()
                    .uri(DeepSeekConfig.CHAT_COMPLETIONS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(DeepSeekResponse.class);

            if (response == null || !response.hasContent()) {
                throw new AnalysisException("DeepSeek devolvió una respuesta vacía.");
            }
            log.debug("Consumo de tokens (HTTP directo): {}", response.totalTokens());
            return response.firstContent();
        } catch (RestClientResponseException e) {
            throw new AnalysisException(friendlyMessage("DeepSeek respondió con el código HTTP "
                    + e.getStatusCode().value() + ": " + shorten(e.getResponseBodyAsString(), 200)), e);
        } catch (ResourceAccessException e) {
            throw new AnalysisException("No se pudo conectar con DeepSeek (red o tiempo de espera agotado): "
                    + rootMessage(e), e);
        }
    }

    /**
     * Extrae el JSON de la respuesta del modelo y lo convierte en el DTO de resultado.
     *
     * @param raw        respuesta en bruto del modelo
     * @param index      índice del segmento, para las trazas
     * @param analysisId identificador del análisis, para las trazas
     * @return el resultado normalizado
     * @throws AnalysisException si la respuesta no contiene un JSON válido
     */
    private SegmentResultDto parseResponse(String raw, int index, String analysisId) {
        String json = extractJson(raw);
        try {
            SegmentResultDto result = objectMapper.readValue(json, SegmentResultDto.class);
            log.debug("Respuesta del segmento {} del análisis {} parseada (score={})",
                    index, analysisId, result.normalizedScore());
            return result;
        } catch (JsonProcessingException e) {
            log.warn("Respuesta no parseable para el segmento {} del análisis {}: {}",
                    index, analysisId, shorten(raw, 200));
            throw new AnalysisException("El modelo devolvió una respuesta con un formato inesperado.", e);
        }
    }

    /**
     * Aísla el objeto JSON de la respuesta, por si el modelo lo envuelve en texto o en un
     * bloque de código Markdown (```json ... ```), algo habitual pese a pedir JSON puro.
     *
     * @param raw respuesta en bruto
     * @return la subcadena que contiene el objeto JSON
     * @throws AnalysisException si no se encuentra ningún objeto JSON
     */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AnalysisException("DeepSeek devolvió una respuesta vacía.");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) {
                text = text.substring(firstBreak + 1);
            }
            int closing = text.lastIndexOf("```");
            if (closing >= 0) {
                text = text.substring(0, closing);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AnalysisException("La respuesta del modelo no contiene un objeto JSON.");
        }
        return text.substring(start, end + 1);
    }

    /** Cierra el análisis y, si hubo resultados, descuenta la cuota del usuario. */
    private void finalizeAnalysis(DocumentAnalysis analysis, Runnable onSuccess, long startedAt) {
        analysis.setCompletedAt(LocalDateTime.now());
        analysis.setDurationMs(System.currentTimeMillis() - startedAt);

        boolean anySuccess = analysis.getSegments().stream().anyMatch(SegmentAnalysis::isCompleted);

        if (anySuccess) {
            log.info("Análisis completado: id={} segmentos={} errores={} scoreGlobal={} duración={} ms",
                    analysis.getId(), analysis.getTotalCount(), analysis.getErrorCount(),
                    analysis.getGlobalScoreRounded(), analysis.getDurationMs());
            // Última escritura del análisis: al ser 'status' volatile, publica el resto de resultados.
            analysis.setStatus(DocumentAnalysis.Status.COMPLETED);
            if (onSuccess != null) {
                try {
                    onSuccess.run();
                } catch (RuntimeException e) {
                    log.error("Error al registrar el consumo del análisis {}", analysis.getId(), e);
                }
            }
        } else {
            String reason = analysis.getSegments().stream()
                    .filter(SegmentAnalysis::hasError)
                    .map(SegmentAnalysis::getErrorMessage)
                    .findFirst()
                    .orElse("No se pudo analizar ningún segmento del documento.");
            analysis.setErrorMessage(reason);
            analysis.setStatus(DocumentAnalysis.Status.FAILED);
            log.error("Análisis fallido: id={} motivo={}", analysis.getId(), reason);
        }
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AnalysisException(
                    "La clave de API de DeepSeek no está configurada. Define la variable de entorno "
                            + "DEEPSEEK_API_KEY y reinicia la aplicación.");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("Análisis interrumpido durante la espera entre reintentos.", e);
        }
    }

    /**
     * Traduce los fallos más habituales de la API a un mensaje que el profesorado pueda
     * entender y trasladar a quien administre la instalación. Si no reconoce el error,
     * devuelve el mensaje original.
     */
    private static String friendlyMessage(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("401") || lower.contains("authentication fails")
                || lower.contains("invalid api key") || lower.contains("incorrect api key")) {
            return "La clave de API de DeepSeek no es válida o ha caducado. "
                    + "Revisa la variable de entorno DEEPSEEK_API_KEY del servidor.";
        }
        if (lower.contains("402") || lower.contains("insufficient balance")) {
            return "La cuenta de DeepSeek no tiene saldo suficiente para completar el análisis.";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "DeepSeek está limitando las peticiones en este momento. "
                    + "Espera unos minutos y vuelve a intentarlo.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "DeepSeek ha tardado demasiado en responder. Vuelve a intentarlo.";
        }
        return raw;
    }

    /** Versión del mensaje de error apta para mostrar al usuario. */
    private static String userFacingMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "No se pudo analizar este fragmento.";
        }
        return shorten(message, 300);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : shorten(message, 200);
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
}
