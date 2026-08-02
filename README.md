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
- **Helm releases** — revision, status and chart, with upgrade, rollback, test and uninstall.
- **Chart repos** — add, update, remove, and search across them.

Helm charts in your project appear in **Project** alongside plain manifests, with a picker for
whichever `values*.yaml` sit beside them — install, lint, render, update dependencies, package.

Sections only watch the cluster while expanded, so an open panel isn't ten idle subprocesses.

**Release tab** — one per Helm release: **Status**, **Values** (what you supplied, or the fully
merged set), **Manifest** (with Secret values redacted), **History** with one-click rollback to
any revision, and **Notes**.

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
  they had. (Chart *repos* are the deliberate exception — adding one writes the shared helm
  config, because a repo is a registration you want everywhere. Those actions say so.)
- **Secret values are never read.** The list query doesn't request them, the model has no field
  to hold them, YAML is refused for Secrets — including the comma-joined type list
  (`secrets,pods`) that kubectl accepts and an earlier version of this refusal missed — and key
  names/sizes come from `describe`
  (`password: 14 bytes`). There is no path through this plugin that renders a secret value —
  including Helm: rendered manifests, `helm template` and dry-run installs all come back with
  `data:`/`stringData:` masked as `<redacted: N chars>`, and dry runs are captured in-plugin
  rather than printed into a terminal where they'd land in scrollback.
  Chart *values* are shown as-is, since those are your own configuration rather than a
  Kubernetes Secret.
- **Commands reuse one terminal tab** instead of opening a new one each time, and never type
  into a tab the plugin doesn't own.
- **Every mutation confirms, naming the context and namespace** — the one thing you must not
  have to guess before deleting something.
- **No bulk or cascade operations.** No delete-all, no prune, no delete-namespace.

## MCP tools

Agents in BOSS terminals get `mcp__boss__k8s_*`, and every reply about cluster or release state
says which context and namespace it came from — YAML bodies carry it as a leading `#` comment so
they still parse. (The project/local tools — manifests, charts, `helm template`, repo lists —
have no cluster target to name.)

| Tool | Purpose |
|---|---|
| `k8s_contexts` / `k8s_use_context` | See and change the target (never touches kubeconfig) |
| `k8s_namespaces` / `k8s_api_resources` | Namespaces; resource types incl. CRDs |
| `k8s_pods` / `k8s_get` | List pods, or any kind |
| `k8s_logs` | Pod or workload logs, with container and `previous` |
| `k8s_describe` / `k8s_yaml` / `k8s_events` | Inspect one object (YAML refused for secrets) |
| `k8s_port_forward_stop` / `k8s_forwards` | Stop / list supervised forwards |
| `k8s_manifests` | Project manifests |
| `k8s_open_resource` | Open the resource tab |
| `k8s_apply` / `k8s_scale` / `k8s_rollout_restart` / `k8s_delete` | Require `kubernetes.manage` |
| `k8s_exec` | Interactive shell in a pod — requires `kubernetes.exec` |
| `k8s_port_forward` | Start a supervised forward — requires `kubernetes.portforward` |
| `helm_releases` / `helm_status` / `helm_values` / `helm_manifest` / `helm_history` / `helm_notes` | Inspect releases (manifest redacted) |
| `helm_charts` / `helm_lint` / `helm_template` | Project charts; render without a cluster |
| `helm_repos` / `helm_search` / `helm_repo_update` | Chart repositories (read + local cache refresh) |
| `helm_install` / `helm_upgrade` / `helm_rollback` / `helm_uninstall` / `helm_test` | Require `kubernetes.manage` |
| `helm_repo_add` / `helm_repo_remove` | Require `helm.repo` — they write the shared helm config |
| `helm_package` / `helm_dependency_update` | Local packaging |
| `helm_push` | Requires `helm.publish` — the only outward-facing action |

### What is gated, and what that does and doesn't buy you

Five permissions, granted per role by an admin:

- **`kubernetes.manage`** — changing cluster and release state: apply, delete, scale, restart,
  and the Helm release lifecycle.
- **`kubernetes.exec`** — its own permission, because `k8s_exec` is not an operation but a
  shell: arbitrary commands inside the cluster with the pod's service-account credentials,
  reading its mounted Secrets around this plugin's redaction.
- **`kubernetes.portforward`** — starting a forward is an unmediated socket to a
  cluster-internal service over whatever protocol it speaks. A read tool can list a Deployment;
  a forward to an internal database can write to it. *Stopping* one needs nothing.
- **`helm.repo`** — `helm_repo_add`/`helm_repo_remove` write the *shared*
  `~/.config/helm/repositories.yaml`, so the effect outlives BOSS and shows up in your own
  shells. Its own name rather than folded into `kubernetes.manage`, which is about the cluster.
- **`helm.publish`** — pushing a chart off the machine.

**What the gates are honestly worth.** They are a real gate against a caller holding nothing —
which, with nobody signed in, is every caller, since the host exposes an ungated tool
unconditionally. Between *permissions* they are accident-prevention and an audit signal rather
than containment: someone holding `kubernetes.manage` can apply a Job that prints a Secret into
its logs and read it back with `k8s_logs`, so `kubernetes.exec` is a separate decision an admin
makes, not a wall. And none of it is a sandbox — `mcp__boss__run_command` is ungated, so an
agent that cannot call `k8s_delete` can still run `kubectl delete` in a terminal. The sidebar
itself has no permission checks at all; this is about what agents can do through the tools.

A mutating tool that is *not* gated is a decision with a written reason: selecting a target
(`k8s_use_context` — session-local, never touches your kubeconfig), stopping a forward, opening
a tab, and the local chart build steps. `McpToolGatingTest` enumerates the real tool objects and
fails the build unless **every** tool is classified — gated, ungated-with-a-reason, or
read-only — so a new tool cannot arrive undecided. It pins the Kotlin tables, not this
markdown, so treat the test as the source of truth and this table as prose that a human keeps in
step. See [issue #3](https://github.com/risa-labs-inc/boss-plugin-kubernetes/issues/3).

### Upgrading from 1.0.4 or earlier

`k8s_exec`, `k8s_port_forward`, `helm_repo_add` and `helm_repo_remove` used to be callable by
anyone. After this version they are **admin-only until an admin grants** `kubernetes.exec`,
`kubernetes.portforward` and `helm.repo` to the relevant role — holding `kubernetes.manage` does
not confer them. New permissions are registered in the RBAC catalog when the plugin is published
to the store, and grants ride in your session token, so re-authenticate after being granted one.
A side-loaded jar never goes through publish, so those permissions never enter the catalog and
the four tools stay admin-only.

## Requirements

- `kubectl` on PATH (or in a usual install dir — `/opt/homebrew/bin`, `/usr/local/bin`,
  Docker Desktop's bundled copy) and a reachable cluster
- `helm` for the Helm features — optional; without it those sections say so and everything
  else works. Helm 3 and 4 are both supported (the flag dialect is picked from the version).

If `kubectl` or `helm` is missing, the panel offers to install it: it shows you the exact command
and, once you confirm, runs it in the terminal tab where you can watch or stop it. Nothing is
installed silently, and where Homebrew isn't available the button opens the tool's own
instructions instead of guessing a package manager. Once the binary appears, the panel picks it
up on its own — no reload.
- BOSS ≥ 9.2.35, boss-plugin-api ≥ 1.0.52 (the RBAC-gated tools need
  `McpToolDefinition.withRbac`, which lands in 1.0.52)

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
