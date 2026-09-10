package com.textorigin.service;

import com.textorigin.exception.AnalysisException;
import com.textorigin.model.Document;
import com.textorigin.model.DocumentAnalysis;
import com.textorigin.model.Segment;
import com.textorigin.model.SegmentAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Almacén en memoria de los análisis.
 *
 * <p>TextOrigin no usa base de datos: cada análisis vive en un {@link ConcurrentHashMap}
 * indexado por un UUID que aparece en la URL de resultados y en el informe PDF. Los
 * análisis se consideran efímeros y se eliminan automáticamente pasado un tiempo, de modo
 * que la memoria no crece sin límite en una instancia de larga vida.</p>
 */
@Slf4j
@Service
public class AnalysisStorageService {

    /** Tiempo que se conserva un análisis antes de purgarlo. */
    private static final Duration RETENTION = Duration.ofHours(6);

    private final ConcurrentHashMap<String, DocumentAnalysis> analyses = new ConcurrentHashMap<>();

    /**
     * Crea el análisis con sus segmentos en estado pendiente.
     *
     * @param document documento original
     * @param segments segmentos en los que se ha dividido
     * @return el análisis recién creado, todavía sin resultados
     */
    public DocumentAnalysis createAnalysis(Document document, List<Segment> segments) {
        List<SegmentAnalysis> pending = segments.stream()
                .map(segment -> SegmentAnalysis.builder()
                        .index(segment.getIndex())
                        .text(segment.getText())
                        .build())
                .collect(CopyOnWriteArrayList::new, CopyOnWriteArrayList::add, CopyOnWriteArrayList::addAll);

        return DocumentAnalysis.builder()
                .id(UUID.randomUUID().toString())
                .document(document)
                .status(DocumentAnalysis.Status.PENDING)
                .segments(pending)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Guarda un análisis en memoria.
     *
     * @param analysis análisis a guardar
     * @return el mismo análisis, para encadenar llamadas
     */
    public DocumentAnalysis save(DocumentAnalysis analysis) {
        analyses.put(analysis.getId(), analysis);
        log.info("Análisis almacenado: id={} documento='{}' segmentos={}",
                analysis.getId(), analysis.getDocumentName(), analysis.getTotalCount());
        return analysis;
    }

    /**
     * Busca un análisis por su identificador.
     *
     * @param id UUID del análisis
     * @return el análisis, o vacío si no existe o ya ha sido purgado
     */
    public Optional<DocumentAnalysis> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(analyses.get(id));
    }

    /**
     * Busca un análisis y falla si no existe.
     *
     * @param id UUID del análisis
     * @return el análisis solicitado
     * @throws AnalysisException si el identificador no corresponde a ningún análisis activo
     */
    public DocumentAnalysis getRequired(String id) {
        return find(id).orElseThrow(() -> {
            log.warn("Análisis no encontrado: id={}", id);
            return AnalysisException.notFound(
                    "El análisis solicitado no existe o ha caducado. Los análisis se conservan "
                            + "6 horas en memoria; vuelve a analizar el documento si lo necesitas.");
        });
    }

    /** Elimina un análisis concreto. */
    public void remove(String id) {
        if (analyses.remove(id) != null) {
            log.info("Análisis eliminado: id={}", id);
        }
    }

    /** Número de análisis conservados en memoria. */
    public int size() {
        return analyses.size();
    }

    /** Elimina los análisis que superan el tiempo de retención. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 3_600_000L)
    public void purgeExpired() {
        LocalDateTime limit = LocalDateTime.now().minus(RETENTION);
        int before = analyses.size();
        analyses.entrySet().removeIf(entry -> entry.getValue().getCreatedAt() != null
                && entry.getValue().getCreatedAt().isBefore(limit));
        int removed = before - analyses.size();
        if (removed > 0) {
            log.info("Purga de análisis: {} eliminados, {} en memoria (retención {} horas)",
                    removed, analyses.size(), RETENTION.toHours());
        }
    }
}
