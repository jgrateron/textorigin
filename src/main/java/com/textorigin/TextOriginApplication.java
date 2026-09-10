package com.textorigin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punto de entrada de TextOrigin.
 *
 * <p>TextOrigin es una aplicación web que ayuda al profesorado a valorar si un texto
 * (ensayo, artículo, trabajo académico) presenta indicios de haber sido generado por IA.
 * El análisis se apoya en un modelo de lenguaje servido por DeepSeek y es siempre
 * <strong>orientativo</strong>: nunca constituye una prueba de uso de IA.</p>
 *
 * <p>{@code @EnableScheduling} habilita las tareas programadas de la aplicación, en particular
 * la limpieza diaria de las cuotas por IP en {@code QuotaService} y la purga de análisis
 * antiguos en {@code AnalysisStorageService}.</p>
 */
@SpringBootApplication
@EnableScheduling
public class TextOriginApplication {

    public static void main(String[] args) {
        SpringApplication.run(TextOriginApplication.class, args);
    }
}
