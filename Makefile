# =============================================================================
#  TextOrigin · Automatización de tareas
#
#  Ayuda:      make help
#  Arrancar:   make run        (local, con Maven)
#              make up         (Docker Compose)
#  Publicar:   make release    (construye y sube la imagen a Docker Hub)
#
#  Variables que puedes sobrescribir:
#     make docker-build VERSION=1.1.0 IMAGE=otra-cuenta/textorigin
# =============================================================================

SHELL    := /bin/bash
IMAGE    ?= jgrateron/textorigin
VERSION  ?= 1.0.0
PORT     ?= 8080
COMPOSE  ?= docker compose
MVN      ?= mvn

.DEFAULT_GOAL := help

# =============================================================================
#  Ayuda
# =============================================================================
.PHONY: help
help: ## Muestra esta ayuda
	@echo ""
	@echo "  TextOrigin · objetivos disponibles"
	@echo "  ──────────────────────────────────────────────────────────────────"
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  Configuración: IMAGE=$(IMAGE)  VERSION=$(VERSION)  PORT=$(PORT)"
	@echo ""

.PHONY: info
info: ## Muestra la configuración usada por el Makefile
	@printf "  IMAGE   = %s\n" "$(IMAGE)"
	@printf "  VERSION = %s\n" "$(VERSION)"
	@printf "  PORT    = %s\n" "$(PORT)"
	@printf "  COMPOSE = %s\n" "$(COMPOSE)"

# =============================================================================
#  Desarrollo local (requiere Java 21 y Maven)
# =============================================================================
.PHONY: build
build: ## Compila y empaqueta el jar (sin pruebas)
	$(MVN) -B clean package -DskipTests

.PHONY: test
test: ## Ejecuta la batería de pruebas
	$(MVN) -B test

.PHONY: run
run: ## Arranca en local con Maven en el puerto PORT
	@$(MAKE) --no-print-directory check-env
	$(MVN) -B spring-boot:run -Dspring-boot.run.arguments=--server.port=$(PORT)

.PHONY: clean
clean: ## Elimina los artefactos de compilación de Maven
	$(MVN) -B clean

.PHONY: clean-all
clean-all: clean ## Limpia artefactos, contenedores e imagen local
	-$(COMPOSE) down --remove-orphans
	-docker rmi $(IMAGE):$(VERSION) $(IMAGE):latest

# =============================================================================
#  Comprobaciones previas
# =============================================================================
.PHONY: check-env
check-env: ## Avisa si DEEPSEEK_API_KEY no está definida
	@if [ -z "$$DEEPSEEK_API_KEY" ]; then \
		printf "\033[33mAVISO\033[0m  DEEPSEEK_API_KEY no está definida.\n"; \
		printf "       La aplicación arrancará igualmente, pero el análisis fallará.\n"; \
		printf "       Defínela con:  export DEEPSEEK_API_KEY=\"sk-...\"\n"; \
	else \
		printf "\033[32mOK\033[0m     DEEPSEEK_API_KEY definida ($${#DEEPSEEK_API_KEY} caracteres).\n"; \
	fi

.PHONY: check-secrets
check-secrets: ## Verifica que no haya claves de API en el código
	@if grep -rInE "sk-[A-Za-z0-9]{20,}" \
			--exclude-dir=target --exclude-dir=.git --exclude-dir=.mvn . 2>/dev/null; then \
		printf "\033[31mERROR\033[0m  Posible clave de API en el repositorio (ver arriba).\n"; \
		exit 1; \
	else \
		printf "\033[32mOK\033[0m     No se han encontrado claves de API en el código.\n"; \
	fi

# =============================================================================
#  Docker
# =============================================================================
.PHONY: docker-build
docker-build: ## Construye la imagen Docker (etiquetas VERSION y latest)
	docker build -t $(IMAGE):$(VERSION) -t $(IMAGE):latest .

.PHONY: docker-run
docker-run: ## Ejecuta la imagen en primer plano (requiere DEEPSEEK_API_KEY)
	@docker run --rm --name textorigin -p $(PORT):8080 \
		-e DEEPSEEK_API_KEY="$${DEEPSEEK_API_KEY:?DEEPSEEK_API_KEY no definida - usa: export DEEPSEEK_API_KEY=sk-...}" \
		$(IMAGE):$(VERSION)

.PHONY: docker-shell
docker-shell: ## Abre una shell dentro de la imagen (para diagnóstico)
	docker run --rm -it --entrypoint sh $(IMAGE):$(VERSION)

.PHONY: login
login: ## Inicia sesión en Docker Hub
	docker login

.PHONY: docker-push
docker-push: ## Publica la imagen en Docker Hub (VERSION y latest)
	docker push $(IMAGE):$(VERSION)
	docker push $(IMAGE):latest

.PHONY: release
release: docker-build docker-push ## Construye y publica la imagen en Docker Hub
	@printf "\n\033[32mImagen publicada\033[0m  %s:%s  y  %s:latest\n\n" "$(IMAGE)" "$(VERSION)" "$(IMAGE)"

# =============================================================================
#  Docker Compose
# =============================================================================
.PHONY: up
up: ## Levanta el servicio construyendo la imagen (recomendado en local)
	$(COMPOSE) up -d --build

.PHONY: up-image
up-image: ## Levanta el servicio con la imagen de Docker Hub (sin construir)
	$(COMPOSE) up -d

.PHONY: down
down: ## Detiene y elimina el contenedor
	$(COMPOSE) down

.PHONY: restart
restart: down up ## Reinicia el servicio reconstruyendo la imagen

.PHONY: logs
logs: ## Muestra y sigue los registros del contenedor
	$(COMPOSE) logs -f --tail=100

.PHONY: ps
ps: ## Muestra el estado del contenedor
	$(COMPOSE) ps

.PHONY: config
config: ## Valida y muestra la configuración resuelta de Docker Compose
	$(COMPOSE) config
