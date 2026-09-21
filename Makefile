# Variáveis do Projeto
IMAGE_NAME = java-app-bci-test:1.0
CONTAINER_NAME_ORDER = java-otel-order
CONTAINER_NAME_INVENTORY = java-otel-inventory
# Endpoint local via túnel SSH (ssh -L 4317:<pod-ip>:4317 -L 4318:<pod-ip>:4318 root@<vm-ip>)
OBSERVABILITY_ENDPOINT = http://localhost:4317
# Authorization: SUSEObservability <api-key> (URL-encoded space between scheme e chave)
OBSERVABILITY_API_KEY = <your-suse-observability-api-key>

.PHONY: all clean build docker run run-order run-inventory stop deploy

# Comando padrão
all: clean build docker

# 1. Limpa o build anterior
clean:
	@echo "--> Limpando o projeto..."
	./mvnw clean || mvn clean || echo "Maven não encontrado, tente rodar na sua IDE ou instalar o maven."

# 2. Compila a aplicação Java
build:
	@echo "--> Compilando a aplicação..."
	./mvnw package -DskipTests || mvn package -DskipTests

# 3. Constrói a imagem Docker baseada em SUSE BCI
docker:
	@echo "--> Construindo a imagem Docker com SLE BCI..."
	docker build -t $(IMAGE_NAME) .

# 4. Roda os dois serviços localmente no Docker para testar (dois processos
#    JVM separados, cada um com seu próprio OTEL_SERVICE_NAME, para que o
#    service graph do OTel registre uma relação real entre dois serviços
#    em vez de um self-loop no mesmo service.instance.id)
run: run-inventory run-order
	@echo "Ambos os serviços rodando. Teste com: curl http://localhost:8080/api/order/42"

run-inventory:
	@echo "--> Rodando o serviço inventory (porta 8081)..."
	docker run -d --name $(CONTAINER_NAME_INVENTORY) \
		--network host \
		-e SPRING_PROFILES_ACTIVE="inventory" \
		-e SERVER_PORT="8081" \
		-e OTEL_SERVICE_NAME="inventory-service" \
		-e OTEL_RESOURCE_ATTRIBUTES="deployment.environment=production,service.namespace=demo-lab" \
		-e OTEL_LOGS_EXPORTER="otlp" \
		-e OTEL_TRACES_EXPORTER="otlp" \
		-e OTEL_METRICS_EXPORTER="none" \
		-e OTEL_EXPORTER_OTLP_ENDPOINT="$(OBSERVABILITY_ENDPOINT)" \
		-e OTEL_EXPORTER_OTLP_PROTOCOL="grpc" \
		-e OTEL_EXPORTER_OTLP_HEADERS="Authorization=SUSEObservability%20$(OBSERVABILITY_API_KEY)" \
		$(IMAGE_NAME)

run-order:
	@echo "--> Rodando o serviço order (porta 8080)..."
	docker run -d --name $(CONTAINER_NAME_ORDER) \
		--network host \
		-e SPRING_PROFILES_ACTIVE="order" \
		-e SERVER_PORT="8080" \
		-e INVENTORY_SERVICE_URL="http://localhost:8081" \
		-e INVENTORY_SERVICE_NAME="inventory-service" \
		-e OTEL_SERVICE_NAME="order-service" \
		-e OTEL_RESOURCE_ATTRIBUTES="deployment.environment=production,service.namespace=demo-lab" \
		-e OTEL_LOGS_EXPORTER="otlp" \
		-e OTEL_TRACES_EXPORTER="otlp" \
		-e OTEL_METRICS_EXPORTER="none" \
		-e OTEL_EXPORTER_OTLP_ENDPOINT="$(OBSERVABILITY_ENDPOINT)" \
		-e OTEL_EXPORTER_OTLP_PROTOCOL="grpc" \
		-e OTEL_EXPORTER_OTLP_HEADERS="Authorization=SUSEObservability%20$(OBSERVABILITY_API_KEY)" \
		$(IMAGE_NAME)
	@echo "Use 'docker logs $(CONTAINER_NAME_ORDER)' / 'docker logs $(CONTAINER_NAME_INVENTORY)' para ver os logs locais."

# 5. Para os containers locais
stop:
	@echo "--> Parando e removendo os containers..."
	docker stop $(CONTAINER_NAME_ORDER) $(CONTAINER_NAME_INVENTORY) || true
	docker rm $(CONTAINER_NAME_ORDER) $(CONTAINER_NAME_INVENTORY) || true

# 6. Faz o deploy no Kubernetes (depois que a imagem estiver no registry)
deploy:
	@echo "--> Aplicando o manifesto no Kubernetes..."
	kubectl apply -f deployment.yaml
