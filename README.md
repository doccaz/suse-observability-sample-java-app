# SUSE Observability – Sample Java App

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

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
- [Verificando as traces na UI](#verificando-as-traces-na-ui)
- [Limitações conhecidas](#limitações-conhecidas)
- [Deploy no Kubernetes](#deploy-no-kubernetes)
- [Correlação com a topologia do Kubernetes](#correlação-com-a-topologia-do-kubernetes)
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

## Verificando as traces na UI

Depois de rodar os endpoints (`curl http://localhost:8080/api/order/42` etc.),
as traces aparecem na UI do SUSE Observability em poucos segundos:

1. Abra a UI (o mesmo host configurado em `OTEL_EXPORTER_OTLP_ENDPOINT`,
   geralmente via ingress/porta 443, não a porta OTLP 4317/4318).
2. No menu lateral (ícone de hambúrguer no canto superior esquerdo), role
   até encontrar **Traces** — é um item de nível superior, fora da seção
   "Open Telemetry".
3. A lista mostra todas as traces recentes, com **Status**, **OTEL
   service**, **Name** (rota HTTP), **Duration** e **Start time**. Use os
   filtros (`Status`, `Attributes`, `Duration`, intervalo de tempo) para
   restringir a busca — por exemplo, filtre por `order-service` ou
   `inventory-service` para achar as traces geradas pelos testes acima.
4. Clique em uma linha da tabela para expandir o waterfall da trace
   inline: você verá o span `GET /api/order/{itemId}` (server, no
   `order-service`) contendo um span filho `GET` (client) que por sua vez
   contém `GET /api/inventory/{itemId}` (server, no `inventory-service`) —
   exatamente a cadeia server → client → server descrita na seção anterior.
5. Clique em um span específico do waterfall para abrir o painel **Span
   details** à direita, com `Status`, `Span kind`, `Scope name/version`,
   `Start/End time` etc.
6. Expanda **Span attributes** nesse painel para ver os atributos do span.
   Nos endpoints `/api/manual/*`, é aqui que aparecem os atributos
   customizados adicionados via `Span.current().setAttribute(...)`:
   `order.item_id`, `order.available`, `order.status` (e os equivalentes
   `inventory.*` no lado do inventory-service) — ao lado dos atributos
   HTTP/rede padrão (`http.route`, `http.response.status_code`,
   `network.peer.address` etc.) que vêm de graça da auto-instrumentação.

## Limitações conhecidas

- **"Component not found" ao clicar num serviço a partir de uma trace,
  ou `Kubernetes > Pods`/`Open Telemetry > Services` vazios, quase nunca é
  um problema de instrumentação da aplicação.** Investigamos esse sintoma
  a fundo (ver `TOPOLOGY-TROUBLESHOOTING.md` neste repo para o
  histórico completo) e a causa raiz **não** estava na app nem no OTel
  collector — ingestão de traces/logs sempre funcionou. Os três
  requisitos que faltavam, do lado da plataforma:
  1. O chart `suse-observability` sozinho só sobe o backend. É preciso
     instalar também o chart **`suse-observability-agent`** (cluster-agent
     etc.) — é ele que observa a API do Kubernetes e materializa
     Pods/Deployments como Components.
  2. O `stackstate.cluster.name` desse agente precisa bater exatamente
     com o nome configurado na instância do StackPack **Kubernetes**
     (**Settings → StackPacks → Installed Instances** na UI) — nomes
     diferentes = a instância fica "Installed. Waiting for data..."
     para sempre, sem nenhum erro visível.
  3. A aplicação instrumentada precisa emitir atributos `k8s.*` no
     resource (pod name, namespace, node) para que o StackPack
     OpenTelemetry consiga correlacionar o `service`/`service-instance`
     com o Pod real — sem isso, as traces continuam aparecendo
     normalmente na view de Traces, mas o Component do serviço nunca é
     criado. Veja [Correlação com a topologia do
     Kubernetes](#correlação-com-a-topologia-do-kubernetes) para como
     este projeto resolve isso.

  Se você só precisa validar que a instrumentação está funcionando, a
  view de **Traces** já é suficiente independentemente desses três
  pontos — ingestão OTLP nunca dependeu deles.
- **API REST/GraphQL da plataforma não testada com a API key de
  ingestão.** Este projeto só valida o caminho de ingestão OTLP
  (`OTEL_EXPORTER_OTLP_HEADERS`); consultas programáticas via API do
  SUSE Observability (fora da UI) não fazem parte do escopo aqui.
- **Sem métricas.** O projeto propositalmente desabilita o exportador de
  métricas (`OTEL_METRICS_EXPORTER=none`) para manter o foco em traces e
  logs — não há dashboards de métricas para validar neste demo.

## Deploy no Kubernetes

Além do `deployment.yaml` na raiz (exemplo genérico de manifesto para um
único serviço fora deste cluster, documentado por si só no arquivo), o
diretório `k8s/` contém um deploy **real, dentro do mesmo cluster** onde o
SUSE Observability roda — `order-service` e `inventory-service` como dois
Deployments/Services de verdade no k3s, se chamando via DNS interno do
cluster. Essa é a forma recomendada de testar a topologia completa (não só
traces), porque:

- Não precisa de túnel SSH nem de `ClusterIP` exposta externamente — a app
  fala com `suse-observability-otel-collector.suse-observability.svc.cluster.local:4317`
  diretamente.
- Gera uma relação `order-service → inventory-service` real entre dois
  Pods de verdade, correlacionável com a topologia do Kubernetes (ver
  próxima seção).

Passo a passo (testado num k3s single-node):

```bash
# 1. build da imagem localmente (mesmo Dockerfile do restante do projeto)
docker build -t sample-java-app:k8s-demo .

# 2. importar a imagem no containerd do cluster (sem precisar de registry —
#    útil em labs sem registry próprio; ajuste para `docker push` num
#    cenário real com registry configurado)
docker save sample-java-app:k8s-demo -o /tmp/sample-java-app.tar
scp /tmp/sample-java-app.tar root@<vm-ip>:/root/
ssh root@<vm-ip> "k3s ctr images import /root/sample-java-app.tar"

# 3. criar o namespace e o Secret com o header OTLP (nunca commitar a key/token)
kubectl create namespace sample-app-demo
kubectl create secret generic sample-app-observability -n sample-app-demo \
  --from-literal=otlp-headers='Authorization=SUSEObservability%20<api-key-ou-service-token>'

# 4. aplicar os manifestos
kubectl apply -f k8s/inventory-deployment.yaml -f k8s/order-deployment.yaml

# 5. gerar tráfego
kubectl exec -n sample-app-demo deploy/order-service -- curl -s http://localhost:8080/api/order/42
kubectl exec -n sample-app-demo deploy/order-service -- curl -s http://localhost:8080/api/manual/order/42
```

Os manifestos usam `imagePullPolicy: Never` (porque a imagem só existe
localmente no containerd do node, importada no passo 2) e
`runAsNonRoot: true` + `runAsUser: 1000` — o `appuser` da imagem (ver
`Dockerfile`) é UID 1000 mas não-numérico em `USER`, e o Kubernetes não
consegue validar `runAsNonRoot` sem o UID explícito (dá
`CreateContainerConfigError` sem isso).

## Correlação com a topologia do Kubernetes

Rodar dentro do cluster (seção anterior) resolve a parte de rede, mas
**não é suficiente sozinho** para o serviço aparecer como Component
correlacionado a um Pod real — é preciso que a aplicação emita atributos
`k8s.*` no resource OTel, via Kubernetes Downward API. Os manifestos em
`k8s/` já fazem isso:

```yaml
env:
  - name: K8S_POD_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.name } }
  - name: K8S_POD_UID
    valueFrom: { fieldRef: { fieldPath: metadata.uid } }
  - name: K8S_NAMESPACE_NAME
    valueFrom: { fieldRef: { fieldPath: metadata.namespace } }
  - name: K8S_NODE_NAME
    valueFrom: { fieldRef: { fieldPath: spec.nodeName } }
  - name: OTEL_RESOURCE_ATTRIBUTES
    value: "deployment.environment=lab,service.namespace=sample-app-demo,\
k8s.pod.name=$(K8S_POD_NAME),k8s.pod.uid=$(K8S_POD_UID),\
k8s.namespace.name=$(K8S_NAMESPACE_NAME),k8s.node.name=$(K8S_NODE_NAME),\
k8s.deployment.name=order-service,k8s.cluster.name=<nome-da-instância-kubernetes-stackpack>"
```

`$(VAR)` dentro de um valor de `env` referencia outra variável declarada
no mesmo container (interpolação nativa do Kubernetes) — não precisa de
init container nem de script wrapper.

`k8s.cluster.name` precisa bater **exatamente** com o nome configurado na
instância do StackPack Kubernetes instalada na plataforma (**Settings →
StackPacks → Kubernetes → Installed Instances**), não com o nome real do
seu cluster/VM. Sem essa correspondência, os componentes do StackPack
Kubernetes (Pods/Deployments) nunca aparecem — a instância fica
"Installed. Waiting for data..." indefinidamente, sem erro visível — e,
por consequência, o StackPack OpenTelemetry também não consegue
correlacionar o `service`/`service-instance` do seu app com um Pod real.

Com os atributos corretos, depois de gerar tráfego:

1. **Kubernetes > Pods** (filtros Clusters: All / Namespaces: All) passa a
   listar os Pods reais do cluster, incluindo `order-service-*` e
   `inventory-service-*`.
2. Na lista de **Traces**, clicar no nome de um serviço (`order-service`)
   não dá mais "Component not found" — abre o Component de verdade, com
   Topology/Events/Metrics.

Sinal no lado da plataforma de que a correlação está funcionando: os logs
do pod `suse-observability-otel-collector-0` (namespace `suse-observability`)
mostram, pouco depois do primeiro tráfego com os atributos `k8s.*`
presentes, uma linha `Topology stream created` para
`dataSource: "urn:stackpack:open-telemetry:otel-component-mapping:service"`
(e `...:service-instance`, `...:pod`) — sem os atributos `k8s.*`, esses
streams específicos nunca chegam a ser criados, mesmo com as traces
ingerindo normalmente.

## Segurança

O `Makefile` deste repositório usa um placeholder (`OBSERVABILITY_API_KEY
= <your-suse-observability-api-key>`) — substitua pela sua própria key
antes de rodar `make run`. Antes de versionar/compartilhar este projeto
para além do seu uso local:

- Troque `OBSERVABILITY_API_KEY` por uma variável de ambiente ou
  `.env` fora do controle de versão.
- Nunca hardcode a key em `deployment.yaml` — use um `Secret` do
  Kubernetes.
