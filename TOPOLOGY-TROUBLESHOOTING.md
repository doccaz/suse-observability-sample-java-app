# SUSE Observability: why Kubernetes/Service components don't materialize

Symptom this document explains: traces and logs ingest fine and are
searchable in the **Traces** view, but `Kubernetes > Pods` is empty, and
clicking a service name from a trace gives **"Component not found — The
component you are looking for did not exist at any moment in time."**

None of this is a SUSE Observability defect. It's four stacked environment
issues, found while rebuilding a lab instance from scratch (chart `2.1.0`
→ `2.11.1`) and deploying the sample app inside the k3s cluster instead of
externally.

1. **The Kubernetes cluster agent was never installed.** The
   `suse-observability` Helm release only deploys the backend platform
   (API, sync, receiver, OTel collector, storage, etc.). The actual
   Kubernetes API watcher that turns Pods/Deployments/Services into
   Components ships as a **separate chart**,
   `suse-observability/suse-observability-agent` (cluster-agent,
   node-agent, logs-agent, checks-agent, rbac-agent). Without it, traces
   ingest fine (that's the OTel collector's job, unaffected), but nothing
   is ever watching the Kubernetes API, so no Component can exist,
   `Kubernetes > Pods` stays empty, and every relation pointing at a
   service/pod component hits `ComponentForRelationMissing` in the `sync`
   logs — not because relation-processing is broken, but because the
   components it references genuinely don't exist.
2. **Routing:** the agent's `stackstate.url` must include the `/stsAgent`
   path suffix, e.g.
   `http://suse-observability-router.suse-observability.svc.cluster.local:8080/stsAgent`.
   A bare base URL causes the agent's `/intake/` and `/api/v1/series`
   posts to fall through the router's catch-all route to the UI's nginx,
   which 405s them.
3. **Cluster-name mismatch:** an instance can already have a Kubernetes
   StackPack installed (**Settings → StackPacks → Installed Instances**)
   configured with a specific Kubernetes Cluster Name, sitting there
   "Installed. Waiting for data..." — with no indication in the UI of
   *which* cluster name it's actually waiting for. Installing the agent
   with `stackstate.cluster.name` set to anything else (e.g. the VM's own
   hostname) means the agent's data is accepted and processed correctly,
   but never matches that StackPack instance, so it shows "waiting for
   data" forever. Fix:
   ```bash
   helm upgrade suse-observability-agent suse-observability/suse-observability-agent \
     --reuse-values --set-string stackstate.cluster.name=<name from the StackPack instance page>
   ```
   Confirmed via `sync` pod logs:
   `New data observed for "Kubernetes - downstream" after 58147 seconds`,
   followed by real component creation (`created 200`, `created 299`,
   `created 146, updated 49`, ~1225 elements total) — then `Kubernetes >
   Pods` populated immediately. This alone does **not** yet fix individual
   OTel service components — see point 4.
4. **OTel service components need explicit Kubernetes correlation.** Even
   with points 1–3 fixed and `Kubernetes > Pods` fully populated, clicking
   a service name from a trace still 404'd until the traced application's
   OTel resource attributes included `k8s.pod.name`, `k8s.pod.uid`,
   `k8s.namespace.name`, and `k8s.node.name` — sourced via the Kubernetes
   Downward API, since plain OTel Java agent auto-instrumentation does not
   set these on its own (see the `k8s/*.yaml` manifests and the
   "Correlação com a topologia do Kubernetes" section of `README.md` for
   the exact pattern used). Confirmed via otel-collector logs: the
   `otel-component-mapping:service` / `service-instance` / `pod`
   topology-connector streams (`Topology stream created` log line) only
   fired for the first time *after* adding these attributes and resending
   traffic — the relation-mapping streams (`provided-by`, `synchronous`)
   had been firing fine the whole time without them; only the *component*
   mappings needed the k8s correlation. In other words: OTLP-only sources
   with no Kubernetes Pod backing are **not** a fully supported path for
   Service/Service Instance components on this StackPack version — the
   correlation is required, not optional.

## Suggestion for the product

The "Installed. Waiting for data..." state on a StackPack instance page is
a plausible trap for exactly this investigation — a new agent install with
a self-chosen cluster name will silently never match, with nothing in the
UI pointing at the expected cluster name as the likely cause. A more
visible diagnostic here (e.g. "no data received tagged with cluster name
`<X>` in the last N minutes") would save a lot of debugging time.
