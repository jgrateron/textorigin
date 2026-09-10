package com.textorigin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configuración del acceso a DeepSeek.
 *
 * <p>DeepSeek expone una API compatible con la de OpenAI, así que se reutiliza el cliente
 * {@link OpenAiApi} de Spring AI apuntándolo a {@code https://api.deepseek.com}. El
 * {@link ChatClient} es el que usa {@code DeepSeekAnalysisService} para lanzar los análisis.</p>
 *
 * <p><strong>¿Por qué se construye el cliente a mano y no se deja actuar al starter?</strong>
 * La autoconfiguración de Spring AI aborta el arranque si {@code spring.ai.openai.api-key}
 * está vacía, lo que impediría levantar la aplicación antes de tener la clave. Aquí los beans
 * se crean solo cuando hay clave ({@link ApiKeyPresentCondition}) y, mientras tanto, la
 * aplicación arranca y muestra un aviso claro en lugar de fallar. Las propiedades se leen de
 * las mismas claves {@code spring.ai.openai.*}, de modo que la configuración sigue siendo la
 * estándar.</p>
 *
 * <p>Además se registra un {@link RestClient} apuntando al mismo endpoint, que sirve como vía
 * alternativa (HTTP directo en formato OpenAI) si el cliente de Spring AI no está disponible.</p>
 */
@Slf4j
@Configuration
public class DeepSeekConfig {

    /** Ruta del endpoint de chat completions, relativa a la URL base. */
    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** Tiempo máximo para establecer la conexión con DeepSeek. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** Tiempo máximo de espera de la respuesta del modelo. */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(2);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-flash}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature:0.2}")
    private Double temperature;

    /**
     * Cliente de chat de Spring AI apuntando a DeepSeek. Solo se crea si hay clave de API.
     *
     * @return el modelo de chat configurado
     */
    @Bean
    @Conditional(ApiKeyPresentCondition.class)
    public OpenAiChatModel deepSeekChatModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizedBaseUrl())
                .apiKey(apiKey.trim())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        log.info("Cliente DeepSeek (Spring AI) configurado: base-url={} modelo={} temperatura={}",
                normalizedBaseUrl(), model, temperature);

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * Cliente de alto nivel usado por el servicio de análisis.
     *
     * @param chatModel modelo de chat de DeepSeek
     * @return el {@link ChatClient} listo para usar
     */
    @Bean
    @Conditional(ApiKeyPresentCondition.class)
    public ChatClient deepSeekChatClient(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Cliente HTTP directo contra la API compatible con OpenAI de DeepSeek.
     *
     * <p>Se registra siempre (también sin clave) para que el servicio de análisis pueda
     * informar con precisión de si el problema es de configuración o de red.</p>
     *
     * @param builder constructor de {@link RestClient} que aporta Spring Boot
     * @return el cliente configurado con la URL base y los tiempos de espera
     */
    @Bean
    public RestClient deepSeekRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        RestClient.Builder configured = builder
                .baseUrl(normalizedBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (hasApiKey()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        return configured.build();
    }

    /**
     * Avisa al arrancar si falta la clave de API, para que el problema se detecte en los logs
     * y no solo al lanzar el primer análisis.
     *
     * @return comprobación ejecutada tras levantar el contexto
     */
    @Bean
    public ApplicationRunner deepSeekConfigurationCheck() {
        return args -> {
            if (hasApiKey()) {
                log.info("DeepSeek listo: modelo '{}' en {} (clave configurada)", model, normalizedBaseUrl());
            } else {
                log.warn("================================================================");
                log.warn("DEEPSEEK_API_KEY no está configurada.");
                log.warn("La aplicación funciona, pero el análisis de documentos fallará.");
                log.warn("Exporta la variable y reinicia:  export DEEPSEEK_API_KEY=\"sk-...\"");
                log.warn("================================================================");
            }
        };
    }

    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Normaliza la URL base: elimina barras finales y el sufijo {@code /v1}, que ya forma
     * parte de {@link #CHAT_COMPLETIONS_PATH}. Así funciona igual si se configura
     * {@code https://api.deepseek.com} o {@code https://api.deepseek.com/v1}, evitando el
     * error habitual de duplicar el prefijo ({@code /v1/v1/chat/completions}).
     *
     * @return la URL base normalizada
     */
    private String normalizedBaseUrl() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - "/v1".length());
        }
        return value;
    }

    /**
     * Condición que activa los beans de DeepSeek solo cuando hay una clave de API utilizable.
     */
    static class ApiKeyPresentCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("spring.ai.openai.api-key", "");
            return key != null && !key.isBlank();
        }
    }
}
