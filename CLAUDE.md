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
  └─ CaptchaService.verify()              ← anti-bots (Cloudflare Turnstile), antes que todo
  └─ QuotaService.checkQuota()            ← antes de gastar tokens
  └─ TextExtractionService                PDF (PDFBox) / DOCX (POI) / TXT → texto normalizado
                                          · HiddenTextPdfStripper descarta el texto oculto del PDF
                                          · los runs ocultos (w:vanish, blanco, diminuto) no entran
                                          · InvisibleCharacterSanitizer quita lo que no se ve
                                          · guarda: >50 % «oculto» (capa OCR) ⇒ se analiza completo
  └─ BibliographyDetector.stripBibliography()   recorta la bibliografía final antes de segmentar
  └─ InjectionDefenseService.neutralizeInstructions()  marca las frases dirigidas al modelo
  └─ TextSegmentationService              párrafos → segmentos (fusiona cortos, parte largos,
                                          agrupa si superan textorigin.analysis.max-segments)
  └─ AnalysisStorageService               análisis en memoria (ConcurrentHashMap por UUID)
                                          · DocumentAnalysis.warnings se fija antes de save()
  └─ DeepSeekAnalysisService.analyzeAsync()   devuelve el control de inmediato
       └─ prompt partido en system (instrucciones) y user (texto) por el marcador
          ===TEXTO A ANALIZAR=== (PromptContractTest lo verifica)
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
- **Doble vía de llamada al modelo**: la petición HTTP directa en formato OpenAI
  (`DeepSeekRequest`/`DeepSeekResponse`) es la **vía preferida** porque es la única que puede
  enviar parámetros propios de DeepSeek: la petición va con `thinking: {"type": "disabled"}`
  (los V4 razonan por defecto, y al razonar se ignora `temperature` y se gastan tokens de salida
  inútiles aquí). El `ChatClient` de Spring AI sigue operativo y se usa con
  `textorigin.model.prefer-spring-ai=true`; en su versión 1.0.0 no admite ese parámetro, así que
  por esa vía el modelo razonaría. Ambas rutas deben seguir funcionando, y el informe PDF imprime
  el modelo realmente configurado (`spring.ai.openai.chat.options.model`), no un literal.
- **Los fragmentos de Thymeleaf no reciben parámetros**: leen variables del modelo (`analysis`,
  `quota`, `segment`, `contactEmail`) para que un controlador pueda devolverlos directamente
  como vista (`return "fragments/analysis-results :: results"`). Mantén esa convención.
- **Cuotas**: hay un único límite diario por IP (`textorigin.quota.max-per-ip-per-day`, 10 por
  defecto) en un `ConcurrentHashMap` de `QuotaService`; no hay contador de sesión. La comprobación
  va antes de llamar a DeepSeek y el consumo se registra **solo** cuando el análisis termina con
  resultados, desde el hilo de fondo y con la IP capturada durante la petición (no se toca la
  `HttpServletRequest` desde ese hilo).
- **Verificación anti-bots (Cloudflare Turnstile)**: `CaptchaService.verify()` va **antes** de
  `checkQuota()` para que un envío automatizado no consuma cuota ni llegue a extraer texto. La
  comprobación real es la del servidor (el widget del navegador es decorativo): se canjea el
  token del campo `CaptchaService.TOKEN_FIELD` (`cf-turnstile-response`) contra `siteverify`.
  Invariantes: (1) la aplicación **arranca sin claves** (misma convención que
  `DEEPSEEK_API_KEY`): avisa por log y el formulario queda solo con la cuota — el widget y el
  script de Cloudflare no se pintan si `isEnabled()` es falso; (2) la degradación ante fallo de
  red, tiempo de espera o error de Cloudflare es **fail-open** con aviso (rechazar por una
  caída ajena dejaría la aplicación inutilizable; la cuota sigue conteniendo el abuso), pero un
  token ausente o rechazado sí corta el envío; (3) el token es de un solo uso: `static/js/captcha.js`
  llama a `turnstile.reset()` en `htmx:afterRequest` **solo** para las peticiones de
  `#analysis-form`, porque el sondeo de estado dispara ese mismo evento cada 1,5 s; (4) el
  script de Cloudflare es la única dependencia externa de la interfaz y solo se carga cuando
  hay claves — no hay que "arreglarlo" para que use el WebJar; (5) cualquier cambio en el
  nombre del campo, el widget o el JS debe ir acompañado de sus casos en `CaptchaServiceTest`
  (con `MockRestServiceServer`) y `CaptchaFormRenderTest` (usa las claves de prueba de
  Cloudflare, que siempre dejan pasar, y ningún caso llega a consultar `siteverify`).
- **Publicación de resultados entre hilos**: los segmentos viven en una `CopyOnWriteArrayList` y
  cada resultado se publica con `set(index, ...)`; `DocumentAnalysis.status` es `volatile` y se
  escribe **el último**, de modo que leer `COMPLETED` garantiza ver todos los resultados.
- **El prompt es un recurso externo** (`prompts/deepseek-analysis.txt`), cargado con
  `@Value("classpath:...")` y con el marcador `{text}` sustituido por `String.replace`.
- **La bibliografía final no se analiza**: `BibliographyDetector` la recorta del texto antes
  de crear el `Document`, de modo que estadísticas, segmentos e informe describen exactamente
  el texto analizado. Solo reconoce encabezados completos y cortos (hasta 60 caracteres, con
  numeración o viñeta opcional) de una lista cerrada de variantes en español e inglés; no
  recorta si el encabezado abre el documento ni si quedarían menos caracteres que
  `textorigin.analysis.min-text-length`, y conserva los anexos o apéndices posteriores. Al ser
  heurístico, cualquier cambio en los patrones debe ir acompañado de sus casos en
  `BibliographyDetectorTest`. Comparte `TextSegmentationService.cleanParagraph` con la
  segmentación para interpretar los párrafos igual que ella.
- **Defensas anti prompt-injection** (`InvisibleCharacterSanitizer`, `HiddenTextPdfStripper`,
  `HiddenFormatRules`, `InjectionDefenseService`): se aplican **antes** de llamar al modelo y son
  independientes de él, porque una inyección que funcione pediría al modelo no revelarla.
  Invariantes: (1) ninguna defensa puede impedir un análisis — cada una va en `try/catch
  (RuntimeException)` y, si falla, el documento se analiza completo con el aviso
  `DocumentWarning.defenseUnavailable`; (2) la neutralización **nunca es silenciosa**: cada
  hallazgo se reproduce en `DocumentAnalysis.warnings` (inmutable, fijado antes de `save()`) y lo
  muestran `fragments/document-warnings.html` y la franja sin numerar del PDF; (3) la guarda de
  proporción del 50 % evita quedarse sin texto cuando el «oculto» es una capa OCR; (4) los
  umbrales (2 pt, blanco ≥ 0.95, ±2 pt fuera de página) y el tope de 200 caracteres por
  sustitución son deliberadamente estrictos: cualquier cambio en los patrones o umbrales debe ir
  con sus casos en `InjectionDefenseServiceTest` / `HiddenTextPdfStripperTest`; (5) el prompt se
  parte en dos zonas con el marcador `===TEXTO A ANALIZAR===` (`PromptContractTest` lo verifica) y
  las dos vías de llamada siguen funcionando: `.system(...).user(...)` en Spring AI y el mensaje
  `Message.system` en la petición HTTP directa.
- **El motor de extracción de PDFBox no procesa los operadores de color**: `PDFTextStripper` usa
  `LegacyPDFStreamEngine`, que no registra `rg`/`g`/`k`, así que su estado gráfico siempre dice
  «negro». Por eso `HiddenTextPdfStripper` los registra a mano con `addOperator(...)`; sin eso,
  la detección de texto blanco es imposible. El motor sí trae el modo de renderizado
  (`SetTextRenderingMode`), que es lo que hace funcionar el criterio `Tr 3`.
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
  un análisis en curso **y mientras se muestran resultados completados** (`.results__banner`):
  el POST responde al instante con el panel de progreso, así que `hx-disabled-elt` —que solo
  cubre esa petición— no basta, y unos resultados en pantalla no deben poder dispararse de
  nuevo sin volver por «Analizar otro documento» (o recargar). Un análisis fallido sí reactiva
  el botón, porque no hay banner desde el que volver. El estado se recalcula en
  `htmx:afterRequest`, que htmx dispara después del intercambio y después de reactivar los
  elementos de `hx-disabled-elt`, así que no se pisan.

## Estilo del proyecto

- Java 21, Lombok (`@Data`, `@Builder`, `@Slf4j`) y Javadoc en las clases de servicio.
- Todo el texto de cara al usuario va en español, con lenguaje probabilístico y sin afirmar
  nunca que un texto sea de IA (ver las advertencias éticas del `README.md`).
- El build debe quedar **sin warnings**: Lombok se declara como `annotationProcessorPaths` y
  `commons-logging` está excluido de PDFBox (Spring Boot ya aporta `spring-jcl`).
- Las versiones se declaran como propiedades en el `pom.xml`. Al publicar una versión nueva hay
  que actualizarla en **dos sitios**: `pom.xml` y la variable `VERSION` del `Makefile`.
