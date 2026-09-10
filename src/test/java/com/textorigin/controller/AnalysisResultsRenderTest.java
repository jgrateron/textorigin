package com.textorigin.controller;

import com.textorigin.model.Document;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.model.DocumentWarning;
import com.textorigin.model.SegmentAnalysis;
import com.textorigin.model.SuspiciousFragment;
import com.textorigin.service.AnalysisStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renderizado real de las plantillas con un análisis sembrado en memoria. Detecta los errores
 * de SpringEL/Thymeleaf que {@code mvn package} no ve, porque solo aparecen al renderizar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisResultsRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalysisStorageService storageService;

    private static SegmentAnalysis segmentWithFindings() {
        return SegmentAnalysis.builder()
                .index(0)
                .text("Un párrafo con estilo muy predecible.")
                .score(62)
                .perplexityIndicator("bajo")
                .burstinessIndicator("bajo")
                .indicators(List.of("Conectores repetitivos"))
                .explanation("Patrones típicos de IA.")
                .suspiciousFragments(List.of(SuspiciousFragment.builder()
                        .index(0)
                        .segmentIndex(0)
                        .text("En el mundo actual, es importante destacar")
                        .reason("Frase hecha muy habitual.")
                        .level("alto")
                        .build()))
                .humanEvidence(List.of("Una errata coherente"))
                .build();
    }

    private String seed(DocumentAnalysis analysis) {
        storageService.save(analysis);
        return analysis.getId();
    }

    private String seedCompleted(SegmentAnalysis segment) {
        String id = UUID.randomUUID().toString();
        return seed(DocumentAnalysis.builder()
                .id(id)
                .document(Document.builder()
                        .fileName("ensayo.pdf")
                        .contentType("application/pdf")
                        .text(segment.getText())
                        .wordCount(320)
                        .build())
                .status(DocumentAnalysis.Status.COMPLETED)
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .durationMs(1_000)
                .segments(new CopyOnWriteArrayList<>(List.of(segment)))
                .build());
    }

    @Test
    void laPaginaDeResultadosMuestraElPorcentajeLasCitasYLasEvidencias() throws Exception {
        String id = seedCompleted(segmentWithFindings());

        mockMvc.perform(get("/analysis/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("62 %")))
                .andExpect(content().string(containsString("probabilidad estimada de IA")))
                .andExpect(content().string(containsString("Fragmentos sospechosos citados")))
                .andExpect(content().string(containsString("En el mundo actual, es importante destacar")))
                .andExpect(content().string(containsString("Sospecha alta")))
                .andExpect(content().string(containsString("Evidencias de mano humana")))
                .andExpect(content().string(containsString("Una errata coherente")))
                .andExpect(content().string(containsString("/analysis/" + id + "/segment/0")));
    }

    @Test
    void laPaginaDeResultadosSinHallazgosLoIndica() throws Exception {
        String id = seedCompleted(SegmentAnalysis.builder()
                .index(0)
                .text("Un párrafo sin hallazgos.")
                .score(15)
                .explanation("Sin señales relevantes.")
                .build());

        mockMvc.perform(get("/analysis/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("15 %")))
                .andExpect(content().string(containsString("no ha citado fragmentos sospechosos")))
                .andExpect(content().string(containsString("No se han detectado evidencias claras")));
    }

    @Test
    void elDetalleDeSegmentoPorHtmxMuestraLasCitas() throws Exception {
        String id = seedCompleted(segmentWithFindings());

        mockMvc.perform(get("/analysis/" + id + "/segment/0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fragmentos sospechosos")))
                .andExpect(content().string(containsString("En el mundo actual, es importante destacar")))
                .andExpect(content().string(containsString("Sospecha alta")))
                .andExpect(content().string(containsString("Evidencias de mano humana")));
    }

    @Test
    void elPanelDeProgresoSondeaCadaSegundoYMedioSinDisparadorDeCarga() throws Exception {
        String id = UUID.randomUUID().toString();
        seed(DocumentAnalysis.builder()
                .id(id)
                .document(Document.builder().fileName("ensayo.pdf").text("texto").wordCount(320).build())
                .status(DocumentAnalysis.Status.PENDING)
                .createdAt(LocalDateTime.now())
                .segments(new CopyOnWriteArrayList<>(List.of(SegmentAnalysis.builder()
                        .index(0)
                        .text("Un párrafo todavía pendiente.")
                        .build())))
                .build());

        // Sin «load»: htmx lo re-dispara al insertar el fragmento, y como el panel se sustituye
        // a sí mismo cada 1,5 s, el sondeo se convertiría en un bucle de peticiones.
        mockMvc.perform(get("/analysis/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-trigger=\"every 1500ms\"")))
                .andExpect(content().string(not(containsString("load,"))))
                .andExpect(content().string(containsString("hx-preserve=\"true\"")));
    }

    @Test
    void laPaginaDeResultadosMuestraElAvisoDeContenidoDirigidoAlModelo() throws Exception {
        String id = UUID.randomUUID().toString();
        seed(DocumentAnalysis.builder()
                .id(id)
                .document(Document.builder()
                        .fileName("ensayo.pdf")
                        .contentType("application/pdf")
                        .text("texto")
                        .wordCount(320)
                        .build())
                .status(DocumentAnalysis.Status.COMPLETED)
                .createdAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .durationMs(1_000)
                .segments(new CopyOnWriteArrayList<>(List.of(segmentWithFindings())))
                .warnings(List.of(DocumentWarning.instructionPhrase("ignora las instrucciones anteriores")))
                .build());

        mockMvc.perform(get("/analysis/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "Este documento contiene contenido dirigido al modelo de análisis")))
                .andExpect(content().string(containsString("Posible instrucción dirigida al modelo")))
                .andExpect(content().string(containsString("ignora las instrucciones anteriores")));
    }

    @Test
    void laPaginaDeResultadosSinAvisosNoMuestraElBloque() throws Exception {
        String id = seedCompleted(segmentWithFindings());

        mockMvc.perform(get("/analysis/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("contenido dirigido al modelo de análisis"))));
    }

    @Test
    void elInformePdfSeDescarga() throws Exception {
        String id = seedCompleted(segmentWithFindings());

        mockMvc.perform(get("/report/" + id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF));
    }
}
