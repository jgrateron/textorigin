package com.textorigin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración web y de ejecución asíncrona.
 *
 * <p>Dos responsabilidades:</p>
 * <ul>
 *   <li>Servir los recursos estáticos y <strong>HTMX desde el propio WebJar</strong>, sin
 *       depender de una CDN. El proyecto funciona así en redes cerradas o sin salida a
 *       Internet, algo habitual en centros educativos.</li>
 *   <li>Definir el pool de hilos que analiza los segmentos en paralelo, con el tamaño
 *       controlado por {@code textorigin.analysis.concurrent-segments}.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Ruta pública del WebJar de HTMX. */
    private static final String HTMX_WEBJAR_PATH = "/webjars/htmx.org/1.9.12/dist/htmx.min.js";

    /** Número de segmentos que se analizan simultáneamente. */
    private final int concurrentSegments;

    public WebConfig(@Value("${textorigin.analysis.concurrent-segments:5}") int concurrentSegments) {
        this.concurrentSegments = concurrentSegments;
    }

    /**
     * Publica los recursos de los WebJars bajo {@code /webjars/**}.
     *
     * @param registry registro de manejadores de recursos
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/")
                .setCachePeriod(3600);
        log.debug("WebJars servidos desde /webjars/** (HTMX en {})", HTMX_WEBJAR_PATH);
    }

    /**
     * Pool de hilos dedicado al análisis de segmentos.
     *
     * <p>Se dimensiona al número de segmentos concurrentes configurado. El orquestador del
     * análisis no bloquea ningún hilo de este pool (encadena los segmentos con callbacks),
     * de modo que todas las hebras quedan disponibles para llamar a DeepSeek.</p>
     *
     * @return el ejecutor usado por {@code DeepSeekAnalysisService}
     */
    @Bean(name = "analysisExecutor")
    public ThreadPoolTaskExecutor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrentSegments);
        executor.setMaxPoolSize(concurrentSegments);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("textorigin-analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("Pool de análisis configurado con {} hilos concurrentes", concurrentSegments);
        return executor;
    }
}
