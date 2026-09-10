# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Comandos

```bash
make help                 # lista todos los objetivos disponibles
make run                  # arranca en http://localhost:8080 (make run PORT=9090)
make build                # mvn clean package -DskipTests
make test                 # mvn -B test
make check-secrets        # busca claves de API antes de publicar

mvn spring-boot:run       # arranque en desarrollo
mvn -B clean package      # build completo: debe terminar sin ningún warning
mvn -B test -Dtest=MiClaseDeTest               # una sola clase de test
mvn -B test -Dtest=MiClaseDeTest#unMetodo      # un solo método

make up / make down / make logs     # Docker Compose
make docker-build / make release    # imagen y publicación en Docker Hub
```

La aplicación **arranca sin `DEEPSEEK_API_KEY`** (avisa por log y explica el problema en la
interfaz al analizar). Solo necesitas la clave para un análisis real.

Hay tests en `src/test/java` (JUnit 5 + AssertJ + MockMvc): `mvn -B test` los ejecuta y
`mvn -B clean package` los incluye. `AnalysisResultsRenderTest` levanta el contexto completo y
pide las páginas con MockMvc: es lo que detecta los errores de SpringEL/Thymeleaf que
`mvn package` no ve, sin necesidad de clave de API.

### Verificar cambios en plantillas

`mvn package` **no detecta** errores de Thymeleaf: aparecen solo al renderizar. Para validar
un cambio en `templates/`, arranca la aplicación y pide la página con `curl`, comprobando el
código HTTP. El error típico en el log es
`Exception evaluating SpringEL expression: "x.y"`, que significa que falta el getter `getY()`
en la clase del modelo (las plantillas acceden a propiedades reales, no a campos).

## Arquitectura

Flujo de un análisis, de principio a fin:

```
POST /analysis/analyze  (AnalysisController)
  └─ QuotaService.checkQuota()            ← antes de gastar tokens
  └─ TextExtractionService                PDF (PDFBox) / DOCX (POI) / TXT → texto normalizado
  └─ TextSegmentationService              párrafos → segmentos (fusiona cortos, parte largos,
                                          agrupa si superan textorigin.analysis.max-segments)
  └─ AnalysisStorageService               análisis en memoria (ConcurrentHashMap por UUID)
  └─ DeepSeekAnalysisService.analyzeAsync()   devuelve el control de inmediato
       └─ CompletableFuture por segmento (pool "analysisExecutor", WebConfig)
       └─ al terminar: callback → QuotaService.registerConsumption()
```

La respuesta HTTP inicial es el fragmento `fragments/analysis-progress`, que se consulta a sí
mismo contra `GET /analysis/{id}/status` cada 1,5 s hasta que el análisis termina; entonces el
endpoint devuelve `fragments/analysis-results`, que sustituye al indicador de progreso.

`GET /report/{id}` genera el PDF al vuelo con `PdfReportService` (PDFBox 3, fuentes estándar 14).

### Invariantes que no son evidentes

- **La autoconfiguración de Spring AI está excluida a propósito** (`spring.autoconfigure.exclude`
  en `application.yml`) y el cliente se cablea a mano en `DeepSeekConfig`. La autoconfiguración
  de Spring AI 1.0 aborta el arranque si `spring.ai.openai.api-key` está vacía —incluso para los
  modelos de audio, imagen o embeddings, que no se usan—. **No la reactives** sin resolver antes
  ese comportamiento. `DeepSeekConfig` también normaliza la URL base quitando el sufijo `/v1`
  para evitar `/v1/v1/chat/completions`.
- **Doble vía de llamada al modelo**: `ChatClient` de Spring AI si el bean existe, y si no una
  petición HTTP directa en formato OpenAI (`DeepSeekRequest`/`DeepSeekResponse`). Ambas rutas
  deben seguir funcionando.
- **Los fragmentos de Thymeleaf no reciben parámetros**: leen variables del modelo (`analysis`,
  `quota`, `segment`, `contactEmail`) para que un controlador pueda devolverlos directamente
  como vista (`return "fragments/analysis-results :: results"`). Mantén esa convención.
- **Cuotas**: hay un único límite diario por IP (`textorigin.quota.max-per-ip-per-day`, 10 por
  defecto) en un `ConcurrentHashMap` de `QuotaService`; no hay contador de sesión. La comprobación
  va antes de llamar a DeepSeek y el consumo se registra **solo** cuando el análisis termina con
  resultados, desde el hilo de fondo y con la IP capturada durante la petición (no se toca la
  `HttpServletRequest` desde ese hilo).
- **Publicación de resultados entre hilos**: los segmentos viven en una `CopyOnWriteArrayList` y
  cada resultado se publica con `set(index, ...)`; `DocumentAnalysis.status` es `volatile` y se
  escribe **el último**, de modo que leer `COMPLETED` garantiza ver todos los resultados.
- **El prompt es un recurso externo** (`prompts/deepseek-analysis.txt`), cargado con
  `@Value("classpath:...")` y con el marcador `{text}` sustituido por `String.replace`.
- **La respuesta del modelo se sanea**: aunque se pide JSON puro, `DeepSeekAnalysisService.extractJson`
  tolera bloques Markdown y texto alrededor, y `SegmentResultDto` normaliza score (0-100), niveles
  inválidos, indicadores duplicados y el literal `"null"`. `SuspiciousFragmentDto` hace lo propio
  con cada cita (sin texto → descartada, comillas envolventes fuera, recorte a 300 caracteres,
  máximo 5 por segmento) y las evidencias humanas comparten el saneado de las listas de texto.
  Un segmento con respuesta inválida se marca como no analizado sin invalidar el resto del informe.
- **El porcentaje es el score**: `SegmentAnalysis.getScoreLabel()` ("62 %") y
  `DocumentAnalysis.getGlobalScoreLabel()` son la única fuente del formato para la web y el PDF;
  no repitas el formato en las plantillas. Los fragmentos del documento los aplana
  `DocumentAnalysis.getAllSuspiciousFragments()` y cada cita lleva su `segmentIndex`, que es lo
  que permite construir el `hx-get` al detalle de su segmento desde el panel agregado.
- **El correo de contacto es configuración** (`textorigin.contact-email`), no una constante:
  lo leen los controladores, el manejador de errores, el PDF y las plantillas.
- **El informe PDF** usa fuentes estándar 14 (sin embeber) y `PdfWriter.sanitize()` sustituye por
  `?` los caracteres que WinAnsi no representa, para que el PDF se genere siempre. Los avisos
  `Using fallback font LiberationSans for base font Helvetica` del log son informativos.

### Convenciones de la interfaz

- **HTMX 1.9 servido desde WebJars** (`/webjars/htmx.org/1.9.12/dist/htmx.min.js`), sin CDN.
  Las URLs de los atributos `hx-*` se generan con `th:attr="hx-get=@{...}"` para que respeten el
  context-path; los valores estáticos (`hx-trigger`, `hx-swap`) van como atributos HTML normales.
- El indicador de cuota se refresca solo: al completarse un análisis, `analysis-results` incluye
  un elemento oculto que pide `GET /quota` y sustituye `#quota-indicator`.
- El JS de arrastrar y soltar (`static/js/dragdrop.js`) inyecta el archivo en el `<input>` real
  mediante `DataTransfer`, que es lo que permite a HTMX incluirlo en el `FormData`.
- `static/js/analysis-state.js` mantiene desactivado el botón «Analizar documento» mientras hay
  un análisis en curso: el POST responde al instante con el panel de progreso, así que
  `hx-disabled-elt` —que solo cubre esa petición— no basta. El estado se recalcula en
  `htmx:afterRequest` según siga o no el `.progress-panel` en la página; ese evento llega
  después de que htmx reactive los elementos de `hx-disabled-elt`, así que no se pisan.

## Estilo del proyecto

- Java 21, Lombok (`@Data`, `@Builder`, `@Slf4j`) y Javadoc en las clases de servicio.
- Todo el texto de cara al usuario va en español, con lenguaje probabilístico y sin afirmar
  nunca que un texto sea de IA (ver las advertencias éticas del `README.md`).
- El build debe quedar **sin warnings**: Lombok se declara como `annotationProcessorPaths` y
  `commons-logging` está excluido de PDFBox (Spring Boot ya aporta `spring-jcl`).
- Las versiones se declaran como propiedades en el `pom.xml`. Al publicar una versión nueva hay
  que actualizarla en **dos sitios**: `pom.xml` y la variable `VERSION` del `Makefile`.
