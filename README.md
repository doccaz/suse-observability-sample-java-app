# SUSE Observability – Sample Java App

Aplicação Spring Boot mínima usada para demonstrar como uma aplicação Java
"normal" (fora de um cluster Kubernetes) envia **logs** e **traces** via
OTLP para o SUSE Observability, usando o agente Java do OpenTelemetry
(zero-code / auto-instrumentação) e, opcionalmente, atributos de span
adicionados manualmente via API.

## Sumário

- [Visão geral](#visão-geral)
- [Endpoints da aplicação](#endpoints-da-aplicação)
- [Build](#build)
- [Executando localmente com Docker](#executando-localmente-com-docker)
- [Conectando a um SUSE Observability fora do cluster](#conectando-a-um-suse-observability-fora-do-cluster)
- [Autenticação (API key)](#autenticação-api-key)
- [Demo: auto-instrumentação vs. instrumentação manual](#demo-auto-instrumentação-vs-instrumentação-manual)
- [Deploy no Kubernetes](#deploy-no-kubernetes)
- [Segurança](#segurança)

## Visão geral

O agente Java do OpenTelemetry (`opentelemetry-javaagent.jar`) é baixado no
build da imagem Docker e injetado via `-javaagent`. Com ele, frameworks como
Spring MVC e `RestTemplate` são instrumentados automaticamente — spans são
criados para toda requisição HTTP recebida e para toda chamada HTTP feita
pela aplicação, sem alterar uma linha de código.

Para gerar traces de verdade (não apenas logs), a aplicação precisa ter
alguma atividade que o agente saiba instrumentar: HTTP, JDBC, mensageria
etc. Um loop que só chama `logger.info()` não produz nenhum span. Por isso
a app expõe endpoints REST que fazem uma chamada HTTP interna
(`/api/order` → `/api/inventory`), gerando uma trace com spans aninhados
(server → client → server).

## Endpoints da aplicação

| Endpoint | Instrumentação | Descrição |
|---|---|---|
| `GET /api/order/{itemId}` | Somente auto-instrumentação (zero código) | Chama `/api/inventory/{itemId}` via `RestTemplate` |
| `GET /api/inventory/{itemId}` | Somente auto-instrumentação (zero código) | Simula consulta de estoque (~150ms) |
| `GET /api/manual/order/{itemId}` | Auto-instrumentação + atributos manuais | Mesmo fluxo do `/api/order`, mas enriquece o span com atributos de negócio |
| `GET /api/manual/inventory/{itemId}` | Auto-instrumentação + atributos manuais | Mesmo fluxo do `/api/inventory`, idem |

Todo item cujo `hashCode() % 5 == 0` é tratado como "sem estoque" (gera
`logger.warn`/`logger.error` e, nos endpoints `/api/manual/*`, o atributo
`order.status=rejected`) — útil para forçar variação nos traces durante a
demo.

Além disso, a classe `ObservabilityTestApplication` mantém o loop de log
original (`Mensagem de rotina #N`, com `warn` a cada 5 e `error` a cada 10),
que continua rodando em paralelo ao servidor web — útil para testar o
exportador de **logs** independente dos traces.

## Build

Requer Java 17. Sem Maven local, use um container:

```bash
docker run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-17 \
  mvn -q clean package -DskipTests
```

Ou, se tiver Maven/Maven Wrapper instalado:

```bash
make build
```

## Executando localmente com Docker

```bash
make docker   # builda a imagem com o agente OTel embutido (SLE BCI + OpenJDK 17)
make run      # sobe o container com --network host, apontando pro OTLP endpoint
```

As variáveis de exportação OTel usadas pelo `make run` (ver `Makefile`):

```
OTEL_SERVICE_NAME=java-app-bci-local
OTEL_LOGS_EXPORTER=otlp
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_EXPORTER_OTLP_PROTOCOL=grpc
OTEL_EXPORTER_OTLP_HEADERS=Authorization=SUSEObservability%20<api-key>
```

Teste:

```bash
curl http://localhost:8080/api/order/42
curl http://localhost:8080/api/manual/order/42
```

Acompanhe os logs do agente (versão carregada, eventuais erros de export):

```bash
docker logs -f java-otel-bci
```

Para parar: `make stop`.

## Conectando a um SUSE Observability fora do cluster

Cenário testado: instância do SUSE Observability rodando dentro de um k3s,
dentro de uma VM KVM local, em uma rede NAT isolada (`virbr-suse`,
`192.168.110.0/24`) — não acessível diretamente da rede local (192.168.86.x)
nem, em muitos setups, nem sequer pelo host da VM, porque o
`otel-collector` do chart é exposto apenas como `ClusterIP` (não há
`hostPort`/`NodePort` habilitado por padrão).

Para testar a partir do host da VM (ou de onde quer que você rode a app),
faça um túnel SSH direto para o IP do pod do collector, ao invés de tentar
alcançar o IP do node:

```bash
# descubra o pod e seu IP dentro da VM
ssh root@<vm-ip> "kubectl -n suse-observability get pod -l app.kubernetes.io/name=suse-observability-otel-collector -o wide"

# abra o túnel local (mantenha rodando em background)
ssh -f -N -L 4317:<pod-ip>:4317 -L 4318:<pod-ip>:4318 root@<vm-ip>
```

Com o túnel de pé, `localhost:4317`/`localhost:4318` no seu host passam a
ser o OTLP gRPC/HTTP do SUSE Observability. É esse endpoint que
`OBSERVABILITY_ENDPOINT` no `Makefile` usa.

> Nota sobre redes bridged/macvtap: se a VM estiver anexada à LAN via
> macvtap (`type='direct'`, `mode='bridge'`) para obter IP via DHCP na rede
> 192.168.86.x, o **host** que criou a interface macvtap nunca consegue
> falar com a própria VM através da mesma NIC física — é uma limitação do
> kernel Linux (sem hairpin), não um erro de configuração. Outros
> dispositivos da LAN conseguem alcançar a VM normalmente. Nesse projeto
> optamos por manter a VM só na rede NAT interna e usar túnel SSH para
> testes a partir do host.

## Autenticação (API key)

O SUSE Observability usa um esquema de autorização próprio no
OTLP receiver (extensão `ingestion_api_key_auth`, `schema: SUSEObservability`
no `values.yaml` do chart). O header esperado é:

```
Authorization: SUSEObservability <api-key>
```

Na variável de ambiente `OTEL_EXPORTER_OTLP_HEADERS` (que segue o formato
`chave=valor` separado por vírgula, sem URL-decoding automático do valor),
o espaço entre o esquema e a chave precisa ser URL-encoded:

```
OTEL_EXPORTER_OTLP_HEADERS=Authorization=SUSEObservability%20<api-key>
```

Teste rápido com `curl` (via túnel, porta 4318/HTTP) para validar uma key
sem precisar subir a aplicação inteira:

```bash
curl -i -X POST http://localhost:4318/v1/traces \
  -H "Content-Type: application/json" \
  -H "Authorization: SUSEObservability <api-key>" \
  --data '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"api-key-test"}}]},"scopeSpans":[{"scope":{"name":"manual-test"},"spans":[{"traceId":"5B8EFFF798038103D269B633813FC60C","spanId":"EEE19B7EC3C1B174","name":"test-span","kind":1,"startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000000100000000"}]}]}]}'
```

- Sem header ou key inválida → `401 Unauthorized`
- Key válida → `200` com `{"partialSuccess":{}}`

## Demo: auto-instrumentação vs. instrumentação manual

A app tem dois pares de endpoints idênticos em comportamento, para
comparar lado a lado no SUSE Observability:

1. **Auto-instrumentação pura** — `OrderController` / `InventoryController`
   (`/api/order`, `/api/inventory`). Nenhuma linha de código relacionada a
   OpenTelemetry. Todos os spans (HTTP server + HTTP client) vêm de graça
   do javaagent.

2. **Instrumentação direcionada** — `ManualOrderController` /
   `ManualInventoryController` (`/api/manual/order`,
   `/api/manual/inventory`). Mesmo fluxo, mas usando
   `Span.current().setAttribute(...)` (dependência `io.opentelemetry:opentelemetry-api`,
   só compile-time — o javaagent injeta a implementação real em runtime)
   para adicionar atributos de negócio ao span já criado pela
   auto-instrumentação: `order.item_id`, `order.available`, `order.status`,
   `inventory.item_id`, `inventory.available`.

Para demonstrar:

```bash
curl http://localhost:8080/api/order/42          # trace só com atributos HTTP padrão
curl http://localhost:8080/api/order/5           # força "sem estoque" (5 % 5 == 0)

curl http://localhost:8080/api/manual/order/42   # mesma trace + atributos de negócio
curl http://localhost:8080/api/manual/order/5
```

No SUSE Observability, compare os spans de `/api/order/*` com os de
`/api/manual/order/*`: a estrutura (server → client → server) é idêntica,
mas só o segundo grupo carrega os atributos customizados.

## Deploy no Kubernetes

```bash
# edite deployment.yaml: registry da imagem e OTEL_EXPORTER_OTLP_ENDPOINT
make deploy
```

O manifesto já vem configurado com `OTEL_LOGS_EXPORTER=otlp`,
`OTEL_TRACES_EXPORTER=otlp` e `OTEL_METRICS_EXPORTER=none`. Adicione
`OTEL_EXPORTER_OTLP_HEADERS` com a API key real via `Secret` (não
hardcoded no manifesto) antes de aplicar em um ambiente real.

## Segurança

O `Makefile` deste repositório contém uma API key de teste em texto
plano (`OBSERVABILITY_API_KEY`), usada apenas para testes locais neste
laboratório. Antes de versionar/compartilhar este projeto para além do
seu uso local:

- Troque `OBSERVABILITY_API_KEY` por uma variável de ambiente ou
  `.env` fora do controle de versão.
- Nunca hardcode a key em `deployment.yaml` — use um `Secret` do
  Kubernetes.
