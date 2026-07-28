# BOSS Kubernetes Plugin

Work a cluster without leaving BOSS. The sibling of the
[Docker plugin](https://github.com/risa-labs-inc/boss-plugin-docker), one layer up.

## What it does

**Sidebar** — scoped to a context and namespace, both permanently on screen because every
action is one click from there.

- **Project** — Kubernetes manifests and kustomizations found in the open project. Apply,
  dry-run apply, or diff.
- **Workloads** — Deployments, StatefulSets and DaemonSets with ready counts. Scale, restart
  the rollout, delete.
- **Pods** — phase, ready count, restarts, node. Open logs, shell in, delete.
- **Services** — type and ports, with one-click port-forward and a direct localhost link.
- **Ingresses · Jobs/CronJobs · ConfigMaps · Secrets · Volume claims** — listed and removable.
- **Custom resources** — pin the CRD types you care about; nothing is enumerated wholesale.

Sections only watch the cluster while expanded, so an open panel isn't ten idle subprocesses.

**Resource tab** — one per object:

- **Logs** — streamed `kubectl logs -f`, with a container picker for multi-container pods and
  a `--previous` toggle for reading why a crash-looping pod died.
- **Preview** — the page the service serves, rendered inline over a supervised port-forward.
- **Describe · YAML · Events** — including the namespace events for that object, which is
  usually where the real answer is.

**Port-forwards that don't quietly die.** Forwards drop constantly — pod replaced, stream
recycled, laptop slept — and `kubectl` just exits. Every forward here is supervised: it comes
back on the *same* local port, so a preview URL stays valid, and the UI says "reconnecting"
instead of serving nothing.

## Safety

- **Your kubeconfig is never modified.** Switching context or namespace in the panel is local
  to BOSS; `--context` and `-n` are passed per command, so your other shells keep whatever
  they had.
- **Secret values are never read.** The list query doesn't request them, the model has no field
  to hold them, YAML is refused for Secrets, and key names/sizes come from `describe`
  (`password: 14 bytes`). There is no path through this plugin that renders a secret value.
- **Every mutation confirms, naming the context and namespace** — the one thing you must not
  have to guess before deleting something.
- **No bulk or cascade operations.** No delete-all, no prune, no delete-namespace.

## MCP tools

Agents in BOSS terminals get `mcp__boss__k8s_*`, and every reply states which context and
namespace it came from:

| Tool | Purpose |
|---|---|
| `k8s_contexts` / `k8s_use_context` | See and change the target (never touches kubeconfig) |
| `k8s_namespaces` / `k8s_api_resources` | Namespaces; resource types incl. CRDs |
| `k8s_pods` / `k8s_get` | List pods, or any kind |
| `k8s_logs` | Pod or workload logs, with container and `previous` |
| `k8s_describe` / `k8s_yaml` / `k8s_events` | Inspect one object (YAML refused for secrets) |
| `k8s_port_forward` / `k8s_port_forward_stop` / `k8s_forwards` | Supervised forwards |
| `k8s_manifests` / `k8s_apply` | Project manifests; apply in a terminal tab |
| `k8s_exec` | Interactive shell in a pod, in a terminal tab |
| `k8s_open_resource` | Open the resource tab |
| `k8s_scale` / `k8s_rollout_restart` / `k8s_delete` | Require `kubernetes.manage` |

## Requirements

- `kubectl` on PATH (or in a usual install dir — `/opt/homebrew/bin`, `/usr/local/bin`,
  Docker Desktop's bundled copy) and a reachable cluster
- BOSS ≥ 9.2.35, boss-plugin-api ≥ 1.0.48

Cloud kubeconfigs that shell out to credential plugins (`gke-gcloud-auth-plugin`, `aws`) work
as long as those binaries are findable; the child PATH is widened to include the usual SDK
locations.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-kubernetes-*.jar ~/.boss/plugins/
```

Then reload from Toolbox. See [AGENTS.md](AGENTS.md) for architecture, the reasoning behind
each design decision, and one known host limitation around disabled plugins.
