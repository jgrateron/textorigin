# syntax=docker/dockerfile:1
# =============================================================================
#  TextOrigin · Imagen Docker
#
#  Compilación en dos etapas:
#    1. Maven + JDK 21 compilan el jar dentro de la imagen (no necesitas Maven
#       ni Java instalados en tu equipo para construirla).
#    2. La imagen final solo lleva un JRE 21 Alpine con el jar: es pequeña y no
#       contiene ni el código fuente ni las herramientas de compilación.
#
#  La clave de DeepSeek se inyecta en TIEMPO DE EJECUCIÓN mediante la variable
#  de entorno DEEPSEEK_API_KEY. Nunca se copia dentro de la imagen.
#
#  Construir:  docker build -t jgrateron/textorigin:1.0.0 -t jgrateron/textorigin:latest .
#  Ejecutar:   docker run --rm -p 8080:8080 -e DEEPSEEK_API_KEY="sk-..." jgrateron/textorigin
# =============================================================================

# ---------------------------- Etapa 1: compilación ----------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# El contenido del proyecto se filtra con .dockerignore (sin target/, .git/, .env...).
COPY pom.xml ./
COPY src ./src

# La caché de dependencias vive en un montaje de BuildKit, así que no engorda
# ninguna capa y los rebuilds reutilizan lo ya descargado.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests clean package

# ---------------------------- Etapa 2: ejecución ------------------------------
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="TextOrigin" \
      org.opencontainers.image.description="Analiza si un texto fue generado por IA. Herramienta orientativa para profesorado." \
      org.opencontainers.image.authors="jgrateron" \
      org.opencontainers.image.source="https://github.com/jgrateron/textorigin"

# Usuario sin privilegios: el contenedor nunca se ejecuta como root.
RUN addgroup -S textorigin && adduser -S -G textorigin textorigin

WORKDIR /app

# En la imagen se activa la caché de plantillas de Thymeleaf (en desarrollo local
# queda desactivada para que los cambios en las plantillas se vean al recargar).
ENV SPRING_THYMELEAF_CACHE=true \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

# El plugin de Spring Boot genera un único .jar ejecutable (el original queda como .jar.original).
COPY --from=build --chown=textorigin:textorigin /build/target/textorigin-*.jar /app/app.jar

USER textorigin

EXPOSE 8080

# Comprueba que el servicio responde. Endpoint ligero: no crea sesión ni consume cuota.
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/health > /dev/null 2>&1 || exit 1

# 'exec' hace que la JVM sea el proceso 1 y reciba SIGTERM, de modo que el
# apagado ordenado de Spring Boot (server.shutdown=graceful) funcione.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
