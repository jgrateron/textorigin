# TextOrigin

**Identifica el origen de un texto. Decide con criterio.**

TextOrigin es una aplicación web para que el profesorado valore si un texto (ensayo, artículo,
trabajo académico) presenta indicios de haber sido generado por IA. Sube un PDF, un DOCX o un
TXT —o pega el texto directamente— y obtienes un mapa **párrafo a párrafo** con un porcentaje
orientativo, las **citas textuales de los fragmentos sospechosos** con su motivo, las posibles
**evidencias de mano humana**, una explicación en lenguaje sencillo y un informe en PDF.

> **Aviso importante:** TextOrigin es una herramienta **orientativa**. Sus resultados **no
> constituyen prueba de uso de IA** y pueden contener falsos positivos, especialmente en
> escritores no nativos o en estilos muy formales. Está pensada para abrir un diálogo con el
> estudiante sobre su proceso de escritura, nunca para emitir un veredicto automático.

---

## Índice

1. [Características](#características)
2. [Requisitos previos](#requisitos-previos)
3. [Puesta en marcha](#puesta-en-marcha)
4. [Configurar la API key de DeepSeek](#configurar-la-api-key-de-deepseek)
5. [Docker](#docker)
6. [Tareas con el Makefile](#tareas-con-el-makefile)
7. [Estructura del proyecto](#estructura-del-proyecto)
8. [Cómo funciona el análisis](#cómo-funciona-el-análisis)
9. [Sistema de cuotas](#sistema-de-cuotas)
10. [Verificación anti-bots (CAPTCHA)](#verificación-anti-bots-captcha)
11. [Configuración](#configuración)
12. [Despliegue detrás de un proxy inverso](#despliegue-detrás-de-un-proxy-inverso)
13. [Publicar el proyecto](#publicar-el-proyecto)
14. [Notas de implementación](#notas-de-implementación)
15. [Limitaciones conocidas y advertencias éticas](#limitaciones-conocidas-y-advertencias-éticas)

---

## Características

- **Lista para desplegar**: `Dockerfile` multi-etapa, `docker-compose.yml` y `Makefile`.
  La clave de API se inyecta siempre como variable de entorno, nunca en la imagen ni en el código.
- **Carga flexible**: arrastrar y soltar (PDF, DOCX, TXT hasta 10 MB) o pegar el texto.
- **Extracción de texto** con Apache PDFBox 3 (PDF), Apache POI 5 (DOCX, incluidas tablas) y
  detección de codificación en TXT (UTF-8 con respaldo a Windows-1252).
- **Análisis por párrafos** con DeepSeek mediante un prompt que devuelve JSON estructurado: el
  porcentaje estimado, los indicadores, hasta 5 citas literales por párrafo con su nivel de
  sospecha y las señales de autoría humana.
- **Resultados visuales**: cada párrafo se resalta en verde (< 40 %), amarillo (40-70 %) o rojo
  (> 70 %), y al pulsarlo se despliega su análisis detallado.
- **Citas agregadas**: un panel reúne todos los fragmentos sospechosos del documento y abre el
  detalle de su segmento al pulsarlos; otro lista las evidencias de mano humana.
- **Informe PDF** con portada, resumen ejecutivo, texto anotado, tabla segmento a segmento,
  fragmentos sospechosos citados, evidencias de mano humana y advertencias metodológicas.
- **Sin base de datos y sin autenticación**: todo vive en memoria, con purga automática.
- **Interfaz HTMX**: interactividad sin recargar la página y sin JavaScript complejo.
- **HTMX servido localmente** desde WebJars: funciona en redes cerradas y sin CDN.
- **Verificación anti-bots opcional** (Cloudflare Turnstile, sin cookies): protege el
  formulario de los envíos automatizados antes de gastar cuota o tokens.

---

## Requisitos previos

| Requisito | Versión | Notas |
|---|---|---|
| Java | **21** o superior | `java -version` — no necesario si usas Docker |
| Maven | 3.8+ | `mvn -version` — no necesario si usas Docker |
| Clave de API de DeepSeek | — | Ver el apartado siguiente |
| Docker + Compose | opcional | Solo para el despliegue en contenedor |

No se necesita base de datos, servidor de aplicaciones ni Node.js: Maven descarga todo,
incluido HTMX. Si eliges la vía Docker, **ni siquiera necesitas Java ni Maven instalados**:
la imagen se compila sola.

---

## Puesta en marcha

```bash
# 1. Configura la clave de DeepSeek (ver el apartado siguiente)
export DEEPSEEK_API_KEY="sk-tu-clave-aqui"

# 2. Arranca la aplicación
cd textorigin
mvn spring-boot:run
```

La aplicación queda disponible en **http://localhost:8080**.

Para empaquetarla como jar ejecutable:

```bash
mvn clean package
java -jar target/textorigin-1.0.0.jar
```

La aplicación **arranca aunque no haya clave configurada**: en ese caso muestra un aviso en el
log y, al intentar analizar, un mensaje claro en la interfaz indicando que falta
`DEEPSEEK_API_KEY`. No se consume cuota en los análisis fallidos.

También puedes arrancar con Docker, sin instalar Java ni Maven: consulta el
[apartado de Docker](#docker).

---

## Configurar la API key de DeepSeek

1. Crea una cuenta en <https://platform.deepseek.com> y genera una clave de API.
2. Exporta la clave como variable de entorno **antes** de arrancar:

   ```bash
   export DEEPSEEK_API_KEY="sk-..."
   ```

   Para que persista entre sesiones, añádela a tu `~/.bashrc`, `~/.zshrc` o al archivo de
   entorno de tu servicio (por ejemplo, `/etc/systemd/system/textorigin.service` con
   `Environment=DEEPSEEK_API_KEY=sk-...`).

3. Alternativas soportadas por Spring Boot:
   - Línea de comandos: `java -jar target/textorigin-1.0.0.jar --spring.ai.openai.api-key=sk-...`
   - Variables de entorno con el nombre estándar de Spring:
     `SPRING_AI_OPENAI_API_KEY=sk-...`
   - Archivo `application-local.yml` **no versionado** (añádelo a `.gitignore`).

La clave se lee de `spring.ai.openai.api-key`, que en `application.yml` toma su valor de
`DEEPSEEK_API_KEY`. El modelo, el endpoint y la temperatura se configuran en las claves
`spring.ai.openai.*`.

> **Nunca** subas la clave al repositorio ni la escribas en `application.yml`.

---

## Docker

La imagen se construye en dos etapas —Maven + JDK 21 para compilar y JRE 21 Alpine para
ejecutar— y **no contiene la clave de API**: se inyecta en tiempo de ejecución.

### Con Docker Compose (recomendado)

```bash
cp .env.example .env      # edita .env y escribe tu clave
make up                   # equivale a: docker compose up -d --build
```

La aplicación queda en **http://localhost:9091** (`docker-compose.yml` publica el puerto `9091`
del anfitrión sobre el `8080` del contenedor). Otros comandos útiles:

```bash
make logs      # seguir los registros
make ps        # estado y salud del contenedor
make down      # detener y eliminar el contenedor
```

Sin Makefile también funciona:

```bash
DEEPSEEK_API_KEY="sk-..." docker compose up -d --build
```

> `docker-compose.yml` **exige** que `DEEPSEEK_API_KEY` esté definida: si falta, Compose se
> detiene con un mensaje claro en lugar de arrancar un contenedor que no podría analizar nada.

### Con Docker directamente

```bash
# Construir la imagen
make docker-build     # o: docker build -t jgrateron/textorigin:1.0.0 -t jgrateron/textorigin:latest .

# Ejecutar la imagen recién construida
docker run --rm -p 8080:8080 -e DEEPSEEK_API_KEY="sk-..." jgrateron/textorigin:1.0.0

# Ejecutar la imagen publicada en Docker Hub
docker run --rm -p 8080:8080 -e DEEPSEEK_API_KEY="sk-..." jgrateron/textorigin:latest
```

### Detalles de la imagen

| Aspecto | Valor |
|---|---|
| Imágenes base | `maven:3.9-eclipse-temurin-21` (compilación) · `eclipse-temurin:21-jre-alpine` (ejecución) |
| Usuario | `textorigin`, sin privilegios (el contenedor nunca corre como root) |
| Puerto | `8080` |
| Memoria | `-XX:MaxRAMPercentage=75`, se adapta al límite del contenedor |
| Salud | `HEALTHCHECK` sobre `GET /health` cada 30 s |
| Parada | Apagado ordenado: `docker stop` espera a las peticiones en curso |
| Clave de API | Solo por variable de entorno, nunca dentro de la imagen |

`GET /health` devuelve `{"status":"UP","application":"textorigin"}`. Es deliberadamente ligero
(no crea sesión, no toca la cuota y no llama a DeepSeek), así que sirve también para
balanceadores de carga.

Variables de entorno admitidas por el contenedor (todas opcionales salvo la primera):

| Variable | Propiedad | Por defecto |
|---|---|---|
| `DEEPSEEK_API_KEY` | `spring.ai.openai.api-key` | — (obligatoria) |
| `TEXTOORIGIN_QUOTA_MAX_PER_IP_PER_DAY` | `textorigin.quota.max-per-ip-per-day` | `10` |
| `TEXTOORIGIN_ANALYSIS_CONCURRENT_SEGMENTS` | `textorigin.analysis.concurrent-segments` | `5` |
| `TEXTOORIGIN_CONTACT_EMAIL` | `textorigin.contact-email` | `jgrateron@gmail.com` |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | `spring.ai.openai.chat.options.model` | `deepseek-flash` |
| `TEXTOORIGIN_MODEL_PREFER_SPRING_AI` | `textorigin.model.prefer-spring-ai` | `false` |

> El modelo por defecto es `deepseek-flash`, el nombre actual del modelo de la familia Flash
> (el antiguo `deepseek-chat` quedó retirado el 24/07/2026). La petición envía
> `thinking: {"type": "disabled"}` para conservar el comportamiento de antes: sin modo de
> razonamiento, la `temperature` se aplica y no se gastan tokens de salida en razonar. Por eso
> la llamada se hace por HTTP directo (única vía que admite parámetros propios de DeepSeek);
> con `prefer-spring-ai=true` se usa el cliente de Spring AI, que en su versión 1.0.0 no puede
> desactivar el razonamiento.

---

## Tareas con el Makefile

`make` sin argumentos muestra los objetivos disponibles:

```
$ make help

  TextOrigin · objetivos disponibles
  ──────────────────────────────────────────────────────────────────
  help             Muestra esta ayuda
  info             Muestra la configuración usada por el Makefile
  build            Compila y empaqueta el jar (sin pruebas)
  test             Ejecuta la batería de pruebas
  run              Arranca en local con Maven en el puerto PORT
  up               Levanta el servicio construyendo la imagen
  ...
```

Las tareas más habituales:

| Objetivo | Qué hace |
|---|---|
| `make run` | Arranca en local con Maven en `http://localhost:8080` |
| `make build` | Genera el jar en `target/` |
| `make test` | Ejecuta la batería de pruebas |
| `make up` / `make down` | Levanta o detiene el servicio con Docker Compose |
| `make logs` | Sigue los registros del contenedor |
| `make docker-build` | Construye la imagen con las etiquetas `1.0.0` y `latest` |
| `make docker-push` | Publica la imagen en Docker Hub |
| `make release` | Construye y publica (`docker-build` + `docker-push`) |
| `make check-env` | Avisa si `DEEPSEEK_API_KEY` no está definida |
| `make check-secrets` | Verifica que no se haya colado ninguna clave en el código |
| `make clean-all` | Limpia artefactos, contenedores e imagen local |

Las variables se pueden sobrescribir desde la línea de comandos:

```bash
make docker-build VERSION=1.1.0
make up PORT=9090
make docker-build IMAGE=otra-cuenta/textorigin
```

---

## Publicar el proyecto

### Código fuente en GitHub

Antes del primer `git push`, comprueba que no se cuela ningún secreto:

```bash
make check-secrets      # busca claves de API en el código
```

`.gitignore` ya excluye `.env`, `target/` y los archivos de los entornos de desarrollo.
`.env.example` sí se versiona, pero solo contiene un marcador de posición.

### Imagen en Docker Hub

```bash
make login              # docker login (cuenta: jgrateron)
make release            # construye y publica 1.0.0 y latest en jgrateron/textorigin
```

O paso a paso:

```bash
make docker-build
make docker-push
```

Cualquier persona podrá ejecutarla con:

```bash
docker run -d -p 8080:8080 \
  -e DEEPSEEK_API_KEY="sk-..." \
  --name textorigin \
  --restart unless-stopped \
  jgrateron/textorigin:latest
```

> Recuerda actualizar `VERSION` en el `Makefile` y la versión del `pom.xml` al publicar una
> versión nueva de la imagen.

---

## Estructura del proyecto

```
textorigin/
├── pom.xml
├── README.md
├── Makefile                  # Tareas de desarrollo, Docker y publicación
├── Dockerfile                # Imagen multi-etapa (compila y ejecuta)
├── docker-compose.yml        # Servicio listo para levantar con compose
├── .env.example              # Plantilla de variables de entorno (sin clave real)
├── .dockerignore             # Contexto de construcción mínimo
├── .gitignore                # Excluye .env, target/ y archivos de IDE
└── src/main/
    ├── java/com/textorigin/
    │   ├── TextOriginApplication.java      # Arranque + @EnableScheduling
    │   ├── config/
    │   │   ├── DeepSeekConfig.java         # Cliente de DeepSeek (Spring AI + RestClient)
    │   │   └── WebConfig.java              # WebJars + pool de análisis
    │   ├── controller/
    │   │   ├── HomeController.java         # Página principal, indicador de cuota y /health
    │   │   ├── AnalysisController.java     # Envío, progreso, resultados y detalle
    │   │   ├── ReportController.java       # Descarga del informe PDF
    │   │   └── GlobalExceptionHandler.java # @ControllerAdvice con el manejo de errores
    │   ├── service/
    │   │   ├── TextExtractionService.java     # PDF / DOCX / TXT, sin el contenido oculto
    │   │   ├── HiddenTextPdfStripper.java     # Texto oculto del PDF (blanco, 1 pt, invisible)
    │   │   ├── HiddenFormatRules.java         # Umbrales de tamaño y color compartidos
    │   │   ├── InvisibleCharacterSanitizer.java  # Zero-width, bidi y tag chars
    │   │   ├── InjectionDefenseService.java   # Frases dirigidas al modelo
    │   │   ├── BibliographyDetector.java      # Recorte de la bibliografía final
    │   │   ├── TextSegmentationService.java   # División en párrafos analizables
    │   │   ├── DeepSeekAnalysisService.java   # Llamada al modelo, reintentos, async
    │   │   ├── AnalysisStorageService.java    # Almacén en memoria con purga
    │   │   ├── QuotaService.java              # Límite diario por IP
    │   │   └── PdfReportService.java          # Informe PDF con PDFBox 3
    │   ├── model/
    │   │   ├── Document.java
    │   │   ├── Segment.java
    │   │   ├── SegmentAnalysis.java
    │   │   ├── SuspiciousFragment.java     # Cita textual señalada por el modelo
    │   │   ├── DocumentAnalysis.java
    │   │   ├── DocumentWarning.java        # Aviso de contenido neutralizado
    │   │   ├── HiddenSpan.java             # Fragmento descartado por su formato
    │   │   ├── SanitizedText.java          # Texto saneado + avisos
    │   │   └── AnalysisRequest.java
    │   ├── dto/
    │   │   ├── DeepSeekRequest.java        # Petición en formato OpenAI
    │   │   ├── DeepSeekResponse.java       # Respuesta en formato OpenAI
    │   │   ├── SegmentResultDto.java       # JSON devuelto por el modelo
    │   │   └── SuspiciousFragmentDto.java  # Normalización de cada cita
    │   └── exception/
    │       ├── TextExtractionException.java
    │       ├── QuotaExceededException.java
    │       └── AnalysisException.java
    └── resources/
        ├── application.yml
        ├── prompts/deepseek-analysis.txt   # Prompt del modelo (editable sin recompilar)
        ├── templates/                      # Vistas Thymeleaf
        │   ├── index.html
        │   ├── analysis.html
        │   ├── error.html
        │   └── fragments/                  # 8 fragmentos reutilizables
        └── static/
            ├── css/textorigin.css
            └── js/
                ├── dragdrop.js
                └── analysis-state.js

└── src/test/java/com/textorigin/
    ├── PromptContractTest.java             # El prompt declara el contrato y separa system/user
    ├── controller/AnalysisResultsRenderTest.java   # Renderizado real con MockMvc
    ├── dto/SegmentResultDtoTest.java       # Normalización de la respuesta del modelo
    ├── dto/DeepSeekRequestTest.java        # Mensajes de sistema y de usuario
    ├── model/DocumentAnalysisTest.java     # Agregados de citas, avisos y porcentaje global
    ├── model/DocumentWarningTest.java      # Extractos de los avisos
    └── service/
        ├── PdfReportServiceTest.java       # Informe PDF (texto extraído con PDFBox)
        ├── BibliographyDetectorTest.java   # Recorte de la bibliografía
        ├── InjectionDefenseServiceTest.java    # Neutralización de instrucciones
        ├── InvisibleCharacterSanitizerTest.java # Caracteres invisibles
        ├── HiddenTextPdfStripperTest.java  # Texto oculto del PDF
        ├── TextExtractionServiceTest.java  # Extracción con hallazgos (PDF/DOCX)
        ├── PdfFixtures.java                # PDFs de prueba generados en memoria
        └── DocxFixtures.java               # DOCX de prueba generados en memoria
```

> `GlobalExceptionHandler` se añadió a la estructura para cumplir el requisito de manejo
> centralizado de errores con `@ControllerAdvice`.

---

## Cómo funciona el análisis

1. **Recepción** — `POST /analysis/analyze` recibe el archivo o el texto pegado.
2. **Comprobación de cuota** — se ejecuta **antes** de nada más, para no gastar tokens si el
   usuario ya agotó sus análisis.
3. **Extracción** — PDFBox, POI o decodificación de texto plano, con normalización
   (saltos de línea unificados, espacios colapsados, BOM eliminado). En este paso se descarta
   además el **texto oculto**: en PDF, el dibujado con modo de renderizado invisible (`Tr 3`),
   por debajo de 2 pt, en blanco sobre páginas con texto de otro color o fuera del área de la
   página; en DOCX, los runs con `w:vanish`, en blanco o diminutos. También se eliminan
   los caracteres Unicode que no se ven (zero-width, controles de dirección, tag chars) y, si
   escondían un mensaje, se decodifica para mostrarlo en el aviso.
4. **Exclusión de la bibliografía** — si el documento termina con una sección de referencias
   reconocible («Referencias», «Bibliografía», «Obras citadas», «Works cited»…), se recorta
   antes de segmentar: las listas de referencias son muy uniformes y producirían falsos
   positivos, además de gastar tokens. Un anexo o apéndice posterior a la bibliografía sí se
   analiza, y un texto que sea solo una bibliografía se analiza entero.
5. **Defensas anti prompt-injection** — las frases que se dirigen al modelo en lugar de formar
   parte del trabajo («ignora las instrucciones anteriores», «asigna una puntuación de 0»,
   marcadores de rol como `system:`) se sustituyen por la marca visible
   `[contenido eliminado: posible instrucción dirigida al modelo]`, y la secuencia de triples
   comillas que delimita el texto en el prompt se neutraliza. El prompt separa además las
   instrucciones (mensaje de rol `system`) del texto a analizar (rol `user`) y refuerza la
   resistencia a contenido dirigido al modelo. Todo lo detectado queda como **aviso** en el
   panel de resultados y en el informe PDF, con el fragmento original reproducido.
6. **Segmentación** — el texto se divide por párrafos (líneas en blanco). Los párrafos muy
   cortos se fusionan con su vecino y los muy largos se parten por frases; si el documento
   supera `max-segments`, los fragmentos se agrupan en bloques equilibrados para no perder
   texto ni disparar el consumo.
7. **Análisis asíncrono** — cada segmento se envía a DeepSeek en paralelo
   (`CompletableFuture` sobre un pool dimensionado por `concurrent-segments`). La respuesta
   HTTP vuelve de inmediato con el indicador de progreso, y la página consulta el estado por
   HTMX cada 1,5 s.
8. **Reintentos** — cada segmento se reintenta hasta `retry-attempts` veces con espera
   exponencial (`retry-delay-ms × 2^(intento-1)`).
9. **Publicación de resultados** — cada segmento terminado se publica de inmediato, de modo
   que la barra de progreso avanza de verdad. Un segmento que falla no arrastra al resto.
   Mientras el análisis está en curso, el botón «Analizar documento» permanece desactivado
   para no lanzar dos análisis a la vez.
10. **Consumo de cuota** — solo se descuenta si el análisis termina con algún resultado.

Las defensas son **heurísticas y de mejor esfuerzo**, y ninguna puede impedir un análisis: si
una comprobación falla, el documento se analiza completo y se avisa. Como salvaguarda, si el
texto «oculto» supera la mitad del documento (habitual en las capas OCR de un PDF escaneado, o
en un intento de dejar el análisis sin contenido) no se descarta nada: se analiza el documento
completo. La detección es propia, independiente del modelo: una inyección que funcione pediría
al modelo no revelarla.

El prompt vive en `src/main/resources/prompts/deepseek-analysis.txt` y se carga con
`@Value("classpath:prompts/deepseek-analysis.txt")`: puedes ajustarlo sin tocar el código Java,
reiniciando la aplicación.

Además del porcentaje, el modelo debe **citar literalmente** los fragmentos que justifican su
valoración (con un motivo y un nivel de sospecha) e indicar las **señales de autoría humana**
que encuentre. Ambas listas pueden venir vacías y la aplicación las normaliza antes de
mostrarlas; las citas se muestran tal cual llegaron, sin comprobar que sean literales.

---

## Sistema de cuotas

Para evitar abusos y contener el gasto de tokens, cada conexión tiene un **límite diario**:

| Límite | Valor por defecto | Dónde se guarda | Cuándo se reinicia |
|---|---|---|---|
| Por IP y día | **10 análisis** | `ConcurrentHashMap` en memoria | Tarea programada diaria a las 3:00 |

Detalles importantes:

- **La IP se resuelve** consultando en orden las cabeceras `X-Forwarded-For` (primera IP de la
  lista), `X-Real-IP`, `Proxy-Client-IP` y `WL-Proxy-Client-IP`; si ninguna sirve, se usa
  `getRemoteAddr()`. Se ignoran los valores `unknown` y se descarta el puerto.
- **La comprobación se hace antes de llamar a DeepSeek** y el consumo **solo se registra
  cuando el análisis termina con resultados**. Si la API falla, el usuario no pierde cuota.
- **Al agotar la cuota** la interfaz muestra un bloque centrado con el motivo y el correo de
  contacto configurado en `textorigin.contact-email` para solicitar más análisis.
- **En la página principal** hay una barra de progreso con los análisis disponibles hoy desde
  la conexión, que se pone roja al llegar a cero y se actualiza sola al terminar cada análisis.
- La limpieza de las cuotas se ejecuta con `@Scheduled(cron = "0 0 3 * * *")` y elimina las
  entradas de días anteriores.

> **Limitación conocida:** la comprobación y el registro no son una operación atómica. Dos
> peticiones simultáneas de la misma IP podrían pasar ambas la comprobación antes de que
> ninguna registre su consumo. Es una aproximación aceptable para un límite anti-abuso de este
> tipo. Tenlo en cuenta si despliegas la aplicación en un entorno con mucho tráfico: en ese
> caso conviene añadir un contador atómico compartido o un limitador más estricto.
>
> **Redes compartidas:** todo el tráfico que salga por una misma IP (un aula con NAT, una
> oficina) comparte el contador diario. Si lo despliegas en un centro educativo, ajusta
> `textorigin.quota.max-per-ip-per-day` al volumen de trabajo previsto o despliega la
> aplicación detrás de un proxy que preserve la IP real del cliente.

---

## Verificación anti-bots (CAPTCHA)

El único endpoint que gasta tokens y cuota es `POST /analysis/analyze`, así que la portada
incluye una verificación con **Cloudflare Turnstile**: un CAPTCHA gratuito, **sin cookies ni
seguimiento** (no exige banner de consentimiento) que el visitante resuelve con una casilla.

**Activarlo** (recomendado en cualquier despliegue público):

1. Entra en <https://dash.cloudflare.com> → *Turnstile* → *Add widget* e indica el dominio
   donde se sirve TextOrigin.
2. Copia el **Site Key** y el **Secret Key** del widget y expórtalos como variables de entorno:

   ```bash
   export TURNSTILE_SITE_KEY="0x4AAAAAAA..."
   export TURNSTILE_SECRET_KEY="0x4AAAAAAA..."
   ```

   Con Docker Compose, añádelos al archivo `.env` (ver `.env.example`).

**Sin claves, la aplicación arranca igualmente** (avisa por log): el widget no se pinta y el
formulario queda protegido solo por el límite diario por IP, como hasta ahora.

Para probar en local sin dar de alta un widget, Cloudflare publica un par de claves de prueba
que siempre dejan pasar:

```bash
export TURNSTILE_SITE_KEY=1x00000000000000000000AA
export TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA
```

Cómo se comporta:

- La verificación se hace **en el servidor** contra el endpoint `siteverify` de Cloudflare
  (el widget del navegador, por sí solo, no protege nada) y **antes** de comprobar la cuota:
  un envío automatizado no consume análisis del visitante ni tokens del modelo.
- El token es de un solo uso; la interfaz pide uno nuevo después de cada envío.
- Si Cloudflare no responde (red, tiempo de espera o un error suyo), la petición **continúa**
  con un aviso en el log: es preferible analizar un documento de más que dejar la aplicación
  inutilizable por una caída ajena, y el límite diario por IP sigue aplicándose. Un token
  ausente o rechazado sí se corta, con el aviso «Verificación de seguridad».
- El widget es la **única dependencia externa** de la interfaz (HTMX se sirve desde el
  WebJar); el script de Cloudflare solo se carga cuando hay claves configuradas.

> Este CAPTCHA también frena el abuso cuando alguien falsifica `X-Forwarded-For` para saltarse
> el límite por IP (ver el apartado del proxy inverso): el reto se resuelve en el navegador,
> no con cabeceras.

---

## Configuración

`src/main/resources/application.yml`:

```yaml
textorigin:
  contact-email: jgrateron@gmail.com   # Aparece en la interfaz, los errores y el informe PDF
  quota:
    max-per-ip-per-day: 10      # Análisis por IP y día
  captcha:
    enabled: true                        # Interruptor de la verificación anti-bots
    site-key: ${TURNSTILE_SITE_KEY:}     # Clave pública del widget de Turnstile
    secret-key: ${TURNSTILE_SECRET_KEY:} # Clave secreta (solo en el servidor)
  analysis:
    min-text-length: 100        # Longitud mínima del texto a analizar
    max-segments: 50            # Máximo de segmentos enviados al modelo
    concurrent-segments: 5      # Peticiones simultáneas a DeepSeek
    retry-attempts: 3           # Reintentos por segmento
    retry-delay-ms: 1000        # Espera base entre reintentos (exponencial)
```

Cualquier valor puede sobrescribirse sin recompilar:

```bash
java -jar target/textorigin-1.0.0.jar \
  --textorigin.quota.max-per-ip-per-day=20 \
  --textorigin.analysis.concurrent-segments=10
```

---

## Despliegue detrás de un proxy inverso

La aplicación está pensada para ejecutarse detrás de nginx, Traefik, Caddy o Apache. Para que
la IP real del visitante llegue a `QuotaService` —y el límite diario por IP no acabe contando a
todo el mundo como un único cliente— hay dos piezas que ya están configuradas:

1. **En la aplicación**, `server.forward-headers-strategy: framework` hace que Spring envuelva
   la petición y respete las cabeceras `X-Forwarded-*`. Además, `QuotaService` las lee
   explícitamente.
2. **En el proxy**, hay que enviar las cabeceras. Ejemplo con nginx:

   ```nginx
   location / {
       proxy_pass         http://127.0.0.1:8080;
       proxy_set_header   Host              $host;
       proxy_set_header   X-Real-IP         $remote_addr;
       proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
       proxy_set_header   X-Forwarded-Proto $scheme;
   }
   ```

   Con Traefik o Caddy estas cabeceras se añaden automáticamente.

**Aviso de seguridad:** `X-Forwarded-For` es una cabecera que el cliente puede falsificar. Si
la aplicación es accesible directamente (sin proxy), un usuario podría eludir el límite por IP
inventándose cabeceras. Despliega TextOrigin escuchando solo en la interfaz local
(`server.address=127.0.0.1`) o protege el puerto con un cortafuegos para que todo el tráfico
pase por el proxy.

---

## Notas de implementación

Decisiones que conviene conocer antes de modificar el proyecto:

- **Spring AI se cablea explícitamente** en `DeepSeekConfig` en lugar de dejarlo en manos de la
  autoconfiguración del starter. Motivo: la autoconfiguración de Spring AI 1.0 aborta el
  arranque si `spring.ai.openai.api-key` está vacía (lo hace incluso para los modelos de audio,
  imagen o *embeddings*, que no se usan). Con el cableado explícito, la aplicación arranca sin
  clave, avisa por log y explica el problema en la interfaz. Por eso `application.yml` excluye
  las autoconfiguraciones de OpenAI: **no las reactives** sin comprobar antes ese
  comportamiento. Las propiedades siguen siendo las estándar (`spring.ai.openai.*`).
- **Doble vía de llamada al modelo**: se usa el `ChatClient` de Spring AI y, si no estuviera
  disponible, una petición HTTP directa en formato OpenAI (`DeepSeekRequest`/`DeepSeekResponse`).
- **Normalización de la URL base**: se elimina el sufijo `/v1` para evitar el error habitual de
  duplicar el prefijo (`/v1/v1/chat/completions`). Funcionan igual
  `https://api.deepseek.com` y `https://api.deepseek.com/v1`.
- **Respuestas del modelo**: aunque se pide JSON puro, el servicio tolera bloques Markdown
  (```` ```json ````), texto alrededor, scores fuera de rango, niveles no reconocidos y
  `"null"` como cadena. Los fragmentos sospechosos y las evidencias se normalizan igual: se
  descartan las citas sin texto, se quitan las comillas envolventes, se recortan las citas de
  más de 300 caracteres, se limitan a 5 por segmento y se eliminan duplicados. Un segmento con
  respuesta inválida se marca como no analizado y no invalida el resto del informe.
- **PDF**: se usan las fuentes estándar Helvetica (WinAnsi). Los caracteres no representables
  (emojis, alfabetos no latinos) se sustituyen por `?` para que el informe siempre se genere.
  Al generar el informe verás en el log tres avisos del tipo
  `Using fallback font LiberationSans for base font Helvetica`: son informativos. PDFBox los
  emite porque las fuentes estándar 14 no se embeben en el documento (el visor aporta las
  suyas) y solo afectan al cálculo interno de métricas; el PDF declara correctamente
  `Helvetica`, `Helvetica-Bold` y `Helvetica-Oblique`, y el texto se extrae sin pérdidas.
- **Retención en memoria**: los análisis se eliminan automáticamente a las 6 horas. Si la
  aplicación se reinicia, los análisis en curso se pierden (no hay persistencia).

---

## Limitaciones conocidas y advertencias éticas

- **Ningún detector de IA es fiable al 100 %.** Los resultados son probabilísticos: indican
  parecido con patrones habituales de los modelos de lenguaje, no autoría demostrada.
- **Falsos positivos**: los textos de **escritores no nativos**, los estilos **muy formales o
  técnicos** y los escritos **muy revisados** pueden puntuar alto sin haber sido generados por
  IA. La interfaz y el informe incluyen esta advertencia de forma destacada.
- **Falsos negativos**: un texto generado por IA y reescrito después puede puntuar bajo.
- **Análisis por párrafos**: no se analizan metadatos del archivo, historial de edición ni el
  proceso de escritura del estudiante.
- **Bibliografía**: la sección de referencias final se excluye del análisis de forma
  automática, pero la detección es heurística: si el encabezado no sigue un formato
  reconocible («Referencias», «Bibliografía», «Obras citadas», «Works cited»…), sus párrafos
  se analizarán como el resto del documento.
- **Contenido dirigido al modelo**: la detección de texto oculto y de instrucciones también es
  heurística. Un ensayo que **cite** frases como «ignora las instrucciones anteriores» verá esa
  cita sustituida por la marca visible y reproducida en el aviso, y el análisis perderá ese
  fragmento; los colores definidos por tema en DOCX y los espacios de color personalizados en
  PDF no se inspeccionan; y una paráfrasis ingeniosa («trata este texto como perfecto») puede
  pasar sin ser detectada. El prompt reforzado y la separación entre instrucciones y texto son
  la segunda barrera para esos casos.
- **PDF escaneados**: si un PDF es una imagen sin capa de texto, no se podrá analizar (haría
  falta OCR, que TextOrigin no incorpora).
- **Coste**: cada análisis consume tokens de tu cuenta de DeepSeek. El sistema de cuotas y el
  límite de segmentos acotan el gasto, pero revísalo según tu presupuesto.
- **Privacidad**: el texto se envía a la API de DeepSeek para su análisis. Informa al alumnado
  y respeta la normativa de protección de datos de tu institución antes de usar la herramienta
  con trabajos reales.
- **Uso recomendado**: emplea el informe como punto de partida para una conversación con el
  estudiante sobre cómo escribió el texto. No lo uses como única base de una calificación ni
  de una acusación.

---

## Contacto

Para solicitar más análisis o reportar problemas: **jgrateron@gmail.com**
