# AGENTS.md

## Project Overview

**Kubernetes** (`ai.rever.boss.plugin.dynamic.kubernetes`) is a dynamic plugin for the BOSS
desktop application, and a sibling of the Docker plugin — same shape one layer up.

A sidebar scoped to a context + namespace lists the open project's manifests alongside
workloads, pods, services, ingresses, jobs, config maps, secrets (names only), volume claims
and pinned custom resources. Each object gets a main-panel tab with live logs, an inline
preview backed by a supervised port-forward, describe, YAML and events.

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.kubernetes`
- **Main Class**: `ai.rever.boss.plugin.dynamic.kubernetes.KubernetesDynamicPlugin`
- **API Version**: 1.0.48 (`minApiVersion` 1.0.48 — needs the MCP tool-provider API)
- **Type**: `mixed` (registers both a panel and a tab type)

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build             # Full build
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user runs it.
- After building, copy the JAR to `~/.boss/plugins/` (or `~/.boss_debug/plugins/` for a
  dev-mode host) and reload from Toolbox.
- While iterating locally, keep one version and overwrite the same JAR.

## Architecture

```
KubectlCli.kt               → only place that shells out to kubectl; resolution + exec/stream
KubeModels.kt               → models from `-o json` .items[]; SecretInfo has NO data field
KubeEngine.kt               → ClusterState, contexts/namespaces, per-section watches, project scan
KubeServices.kt             → shared brain: engines, forwards, actions, storage, verified tab open
PortForwardManager.kt       → supervised `kubectl port-forward` with restart + ceiling
KubeActions.kt              → terminal-tab routing; delete/scale/restart in-plugin
KubePanelInfo/ViewModel/Component.kt → sidebar (context+namespace header, 12 sections)
KubeResourceTab*.kt         → tab type, VM and UI: Logs | Preview | Describe | YAML | Events
KubeMcpTools.kt             → k8s_* MCP tools
KubeIcon.kt                 → SimpleIcons.Kubernetes alias
KubernetesDynamicPlugin.kt  → register/dispose

HelmCli.kt                  → only place that shells out to helm; version + flag dialect
HelmModels.kt               → release/revision/repo/chart models + redactRenderedYaml
HelmEngine.kt               → releases, repos, chart discovery; companion to KubeEngine
HelmActions.kt              → install/upgrade/rollback/uninstall/test/package/push/repo ops
HelmReleaseTab*.kt          → tab type, VM and UI: Status | Values | Manifest | History | Notes
HelmMcpTools.kt             → helm_* MCP tools (second provider, independent of helm's absence)
```

### Load-bearing decisions

**Never write to the kubeconfig.** `kubectl config use-context` and `set-context --namespace`
mutate global state shared with every other terminal on the machine; picking a context in a
sidebar must not silently retarget the user's shell. The selection lives in `KubeEngine` and is
passed as `--context X -n Y` on every invocation (`KubeEngine.args`).

**Secrets: the model has no `data` field, and the list query never asks for one.** `secretRows()`
uses `-o custom-columns` naming only metadata and type. Key names and sizes come from
`kubectl describe`, which prints `key: <n> bytes`. `KubeActions.yaml()` refuses Secrets
outright, and `k8s_get` ignores `output=yaml` for them. You cannot leak what you never
deserialize — keep it that way.

**`--request-timeout` on every server-touching call.** Without it an unreachable cluster hangs
each call for the better part of a minute. `KubectlExec.cleanError` also strips kubectl's
`memcache.go` retry-storm noise, which is otherwise five copies of the same sentence.

**Unreachable is spelled two different ways by kubectl** and they share no substring:
`get` says `dial tcp …: connect: connection refused`, `version` says `The connection to the
server … was refused`. `UNREACHABLE_MARKERS` lists both; matching only "connection refused"
silently mislabels a plainly-down cluster.

**Go templates render absent fields as the literal `<no value>`.** A context with no namespace
produced `-n '<no value>'`, which matched nothing and made the panel look empty for no visible
reason. Guarded in the template *and* by `cleanTemplateValue()`.

**Watch only expanded sections.** Ten permanent watch subprocesses per window is not
acceptable. `setSectionActive` starts/stops
`get <res> --watch --output-watch-events -o go-template=…`, one compact line per change, which
only *triggers* a debounced full `-o json` refresh. Same push-then-reconcile split as the
Docker plugin's `docker events`.

**Port-forwards are supervised and their process handles are held.** They drop constantly (pod
replaced, SPDY stream recycled, laptop slept) and kubectl just exits. The supervisor restarts
on the *same* local port so a preview URL stays valid, with a `MAX_RESTARTS` ceiling.
`PortForwardManager` keeps the `Process` per key and kills it directly on stop: relying on
coroutine cancellation alone was tested and a `kubectl port-forward` outlived its cancelled
job, still serving the port.

**Interactive to a terminal, fast in-plugin.** `apply`, `diff` and `exec -it` need long output
or a real TTY, so they open a BossTerm tab; `delete`, `scale` and `rollout restart` run
in-plugin and toast.

**Confirmations name the context and namespace.** That string (`KubeActions.describeTarget()`)
is the difference between a routine delete and an incident. There are deliberately **no bulk or
cascade helpers** — no delete-all, no prune, no delete-namespace.

### Helm specifics

**Every command reuses one plugin-owned terminal tab.** `KubeActions.openTerminal` first tries
`runInExistingTerminal`, which reuses the tab recorded in `KubeServices.commandTerminal`,
creating it only once. Two things it must not do: open a tab per command (the strip fills within
minutes), or send into whatever tab is focused — `sendCommand` writes to the terminal's *active*
tab, so without an owned tab a `helm upgrade` would be typed into the user's own shell session.
Opening a fresh BOSS tab is the last resort.

The sequence is `switchToTab` → **`sendInterrupt`** → 500 ms → `sendCommand`, matching what
terminal-tab does for a re-run. The interrupt is not cosmetic: `sendCommand` writes to the pty,
so with a foreground process still running (a `helm install --wait`, an `exec -it`) the text goes
to *that process's stdin* and never runs, while the caller still gets `true` and reports success
for a command that vanished. The API exposes no idle/running signal, so interrupting is the only
way to guarantee the shell is the reader. Safe here because the tab only ever holds this plugin's
own commands — and announced with a toast, since it does mean an unrelated command in flight gets
stopped.

**`kubectl exec -it` never shares the tab.** Ctrl-C is not a universal "stop that": under
`exec -it` kubectl holds the local terminal in raw mode and *forwards* 0x03 to the remote
process, so the container's shell takes the SIGINT and prints a prompt while kubectl keeps
owning the pty. The interrupt that makes reuse safe for everything else does nothing here, and
the next command would be typed **into the container's shell** — in a pod with kubectl and a
mounted service-account token that runs against the cluster with the *pod's* credentials, and
the sidebar would report success. Hence `TerminalCommandKind`: `Interactive` gets its own
sub-tab and is never recorded as owned; `Batch` shares. Classify a new terminal command by what
holds the pty, not by what looks tidiest — if Ctrl-C will not free it, it is `Interactive`.

**`openTerminal` returning true means "accepted for delivery", not "running".** The command is
queued; the consumer types it later, after the interrupt sequence and after anything ahead of it.
So anything timed from that return is wrong (see `onDelivered` below), and a command can still
fall back to a BOSS tab — or fail — after its caller was told it launched. The `helm_*` and `k8s_*`
tools hedge their wording for this and name what interrupting costs, which is where the warning
belongs: prospectively, while the caller can still act on it. There is deliberately **no** toast
after the fact — with no liveness signal there is nothing to gate one on, so it fired on every
command and became noise (boss-plugins#11).

**No API-version floor was raised for the terminal reuse.** Everything it uses predates the
declared `minApiVersion` 1.0.48: `getPluginAPI`, `PluginContext.windowId`, `ActiveTabData.windowId`,
`hasTerminalState` and `sendInterrupt` land in `boss-plugin-api` **1.0.16**, and
`TerminalTabPluginAPI` / `createTab` / `switchToTab` / `listTabs` in **1.0.23**. The
`runCatching { Throwable }` wrap is not the compatibility contract — it is there for the case that
terminal-tab simply is not loaded, which logs rather than degrading in silence.

**Commands are delivered through a single-consumer `Channel`, not a lock.** `runInExistingTerminal`
only decides *whether* a terminal exists and enqueues; one coroutine drains the queue and performs
every switch/interrupt/wait/send. This is load-bearing twice over. A `synchronized` block that
holds across the interrupt but `launch`es the send does not work — a second command's interrupt
then lands before the first has been typed, so the first runs and the second is swallowed, which
is the original bug one step later. And the sequence must not run on the UI thread: panel clicks
call `openTerminal` directly, and the body makes cross-plugin calls whose threading contract this
plugin does not control, so a blocking monitor there risks parking the UI thread. One consumer
also means `ownedTerminal` needs no `@Volatile` and lives privately in `KubeActions` rather than
on the shared services object, where any call site could retarget the tab without queueing.

The working directory is never implicit: it defaults to `projectPath`, because on reuse the tab
sits wherever the last command left it and a relative `-f ./manifest.yaml` would resolve against
the wrong project.

**Anything timed after a terminal command must hang off `onDelivered`, not `openTerminal`'s
return.** That return now means "queued", not "typed" — delivery costs the interrupt sequence
plus every command ahead of it in the queue. `HelmActions.runHelmTerminal` learned this the hard
way: `scheduleHelmRefresh()`'s 2.5 s settle was being counted from acceptance, so the release
list could refresh before helm had started and leave the sidebar on the pre-upgrade state.

**Helm 4 removed and renamed CLI surface**, all verified against 4.2.3 rather than assumed:

- `helm list --all` **no longer exists**. Use the per-state flags
  (`--deployed --failed --pending --uninstalling`), which exist in Helm 3 too, so no dialect
  switch is needed. `--superseded` is left out on purpose: it returns every historical revision.
- `--atomic` → `--rollback-on-failure`, `--force` → `--force-replace`. `HelmCli` picks by major
  version so both 3 and 4 work.
- `helm version --client` is removed; probe with `--template '{{.Version}}'`.
- `--wait` is now a WaitStrategy (`watcher|hookOnly|legacy`), not a boolean, and `watcher` needs
  the `watch` RBAC verb.
- `helm push` speaks HTTPS by default — a non-TLS registry needs `--plain-http`, or it fails
  with "server gave HTTP response to HTTPS client".
- `helm list -o json` returns a **bare array**, unlike kubectl's `{"items":[…]}`, and `revision`
  is a **String** there but an **Int** in `helm history -o json` (with different `updated`
  formats). Hence separate `RawRelease`/`RawRevision` types — one shared model fails to decode.

**Three paths render manifests, and all three are redacted or withheld:**

- `helm get manifest` → `redactRenderedYaml`
- `helm template` → same
- `helm install --dry-run` → runs **in-plugin**, not in a terminal, precisely because its output
  embeds the manifest; sending it to a terminal would put secret values in scrollback
- `helm status -o json` embeds the manifest too, so `RawStatus` deliberately has no `manifest`
  field and the Status view says where to find it instead

Chart *values* are **not** redacted — they are the user's own input, and hiding what you
configured would break the view. Only rendered `kind: Secret` objects are.

**Repo config is deliberately shared.** `helm repo add/update/remove` uses the default
`~/.config/helm/repositories.yaml`, unlike the kubeconfig which is never written: a context
selection is transient and must not retarget other shells, whereas a chart repo is a persistent
registration you want everywhere. Every repo mutation confirms and says it affects the machine.

**Release reads require helm *and* a reachable cluster.** `requireReleaseAccess()` checks both,
because helm-installed-but-cluster-down makes `helm list` return nothing, and an unguarded
handler then answers "No Helm releases" — which reads as "nothing installed" when the truth is
"I can't see". Local-only tools (charts, lint, template, repos, package) stay usable when the
cluster is down.

### Installing missing tools

`ToolInstaller` + `KubePanelViewModel.installTool` offer to install `kubectl`/`helm`, and the
rules matter more than the code:

- **Nothing installs itself.** The offer is a button, the confirmation names the exact command,
  and the command runs in the plugin's terminal tab where it is visible and killable. Installing
  developer tooling touches PATH and sometimes wants sudo; a sidebar must not do that quietly.
- **Only when Homebrew is actually present.** Elsewhere the button opens the tool's own docs
  rather than guessing a package manager, and the plugin will never suggest piping a remote
  script into a shell.
- `brew install kubectl` resolves to the `kubernetes-cli` formula; Docker Desktop's cask is
  `docker-desktop` (not `docker`).
- After launching an install, `watchForTool` polls for the binary (~2 min) and re-probes so the
  panel leaves its not-installed state on its own. This works because neither `KubectlCli` nor
  `HelmCli` keeps a *negative* resolution: a null result isn't sticky, so the next probe finds a
  freshly installed binary without a reload. Verified by moving `helm` aside and back.

### Known host gap (not fixable from here)

`DynamicPluginManager.disablePlugin` calls `trackingContext.unregisterAll()` but **never calls
the plugin's `dispose()`**, and `emitPluginLifecycle` is only ever invoked with `LOADED` /
`UNLOADED` — `PluginLifecycleState.DISABLED` exists but nothing publishes it. So a *disabled*
(as opposed to unloaded) plugin keeps its coroutine scope and its child processes, and the
abandoned `PortForwardManager` will even restart a forward you kill by hand. Unload/reload and
app exit are clean; only disable leaks. The `MAX_RESTARTS` ceiling bounds the damage. The Docker
plugin has the same exposure with its `docker logs -f` streams. Fixing it properly means a
one-line host change (call `dispose()` on disable, or emit `DISABLED`).

### Key Patterns
- Entry point: `DynamicPlugin` with `register(context)` / `dispose()`
- UI: `PanelComponentWithUI` / `TabComponentWithUI` with `@Composable Content()`
- State: ViewModel + `StateFlow`
- Every `PluginContext` provider may be null — degrade, never crash
- `openTab` is fire-and-forget and silently drops unknown tab types; `openResourceTabVerified`
  polls `activeTabs` and reports what actually happened

### Dependencies
- **boss-plugin-api**: compileOnly (provided by the host at runtime)
- **Compose Desktop**, **Decompose**, **Coroutines**, **kotlinx-serialization**
- **simple-icons** / **feather**: not bundled; the host's composeApp provides them

## MCP Tools

kubectl side: `k8s_contexts`, `k8s_use_context`, `k8s_namespaces`, `k8s_pods`, `k8s_get`,
`k8s_logs`, `k8s_describe`, `k8s_yaml`, `k8s_events`, `k8s_api_resources`, `k8s_port_forward`,
`k8s_port_forward_stop`, `k8s_forwards`, `k8s_manifests`, `k8s_apply`, `k8s_exec`,
`k8s_open_resource`, and the `kubernetes.manage`-gated `k8s_scale`, `k8s_rollout_restart`,
`k8s_delete`.

Helm side: `helm_releases`, `helm_status`, `helm_values`, `helm_manifest`, `helm_history`,
`helm_notes`, `helm_charts`, `helm_lint`, `helm_template`, `helm_repos`, `helm_search`,
`helm_open_release`, `helm_package`, `helm_dependency_update`, `helm_repo_add`,
`helm_repo_update`, `helm_repo_remove`, plus `kubernetes.manage`-gated `helm_install`,
`helm_upgrade`, `helm_rollback`, `helm_uninstall`, `helm_test` and `helm.publish`-gated
`helm_push`.

Every reply names the context and namespace it acted on. Gate mutating tools with
`McpToolDefinition.withRbac(...)` — never `.copy()`, which drops the gate.

## Version Management

**`build.gradle.kts` is the single source of truth.** `processResources` syncs it into
`plugin.json` with the `inputs.property("pluginVersion", version)` guard, without which a
version-only bump ships a stale manifest. The default `jar` task is **disabled** so a
`-thin.jar` can never be mistaken for the real plugin by the store's asset picker.

## Code Quality

- Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully

## CI/CD

Pushes to `main` build the JAR, create a GitHub release and publish to the BOSS Plugin Store
via `.github/workflows/build.yml`, delegating to the shared workflow in
`risa-labs-inc/BossConsole-Releases`. Requires the `BOSS_STORE_PLUGIN_PUBLISH_KEY` repo secret.
