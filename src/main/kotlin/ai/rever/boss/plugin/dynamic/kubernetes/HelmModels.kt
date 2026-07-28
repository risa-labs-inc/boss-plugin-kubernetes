package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A release row from `helm list -o json`.
 *
 * ⚠️ `revision` is a **String** here but an **Int** in `helm history -o json`, and
 * the two commands format `updated` differently (Go's default time layout vs
 * RFC3339). Both verified against helm 4.2.3 — a shared model across the two would
 * fail to decode, which is why they are separate types.
 */
@Serializable
internal data class RawRelease(
    val name: String = "",
    val namespace: String = "",
    val revision: String = "",
    val updated: String = "",
    val status: String = "",
    val chart: String = "",
    @SerialName("app_version") val appVersion: String = "",
) {
    fun toInfo() = ReleaseInfo(
        name = name,
        namespace = namespace,
        revision = revision.toIntOrNull() ?: 0,
        updated = updated,
        status = status,
        chart = chart,
        appVersion = appVersion,
    )
}

data class ReleaseInfo(
    val name: String,
    val namespace: String,
    val revision: Int,
    val updated: String,
    val status: String,
    val chart: String,
    val appVersion: String,
) {
    val isDeployed: Boolean get() = status.equals("deployed", ignoreCase = true)

    val isFailed: Boolean
        get() = status.equals("failed", ignoreCase = true) ||
            status.contains("error", ignoreCase = true) ||
            status.endsWith("_failed", ignoreCase = true)

    /** Pending install/upgrade/rollback — mid-flight, neither good nor bad yet. */
    val isPending: Boolean get() = status.startsWith("pending", ignoreCase = true)

    /** Chart name without its trailing `-<version>`. */
    val chartName: String get() = chart.substringBeforeLast('-', chart)

    val chartVersion: String get() = chart.substringAfterLast('-', "")
}

/** One revision from `helm history -o json` — note the Int revision. */
@Serializable
internal data class RawRevision(
    val revision: Int = 0,
    val updated: String = "",
    val status: String = "",
    val chart: String = "",
    @SerialName("app_version") val appVersion: String = "",
    val description: String = "",
) {
    fun toInfo() = RevisionInfo(revision, updated, status, chart, appVersion, description)
}

data class RevisionInfo(
    val revision: Int,
    val updated: String,
    val status: String,
    val chart: String,
    val appVersion: String,
    val description: String,
) {
    val isDeployed: Boolean get() = status.equals("deployed", ignoreCase = true)
}

/**
 * Parsed `helm status -o json`.
 *
 * The raw payload also carries a `manifest` field containing every rendered
 * object — **including Secrets with their values** (verified). It is deliberately
 * *not* modelled here: the Status view must never be able to render it. The
 * Manifest view fetches `helm get manifest` and puts it through [redactRenderedYaml].
 */
@Serializable
internal data class RawStatus(
    val name: String = "",
    val namespace: String = "",
    val version: Int = 0,
    val info: Info = Info(),
    @SerialName("apply_method") val applyMethod: String = "",
) {
    @Serializable
    data class Info(
        val status: String = "",
        val description: String = "",
        @SerialName("first_deployed") val firstDeployed: String = "",
        @SerialName("last_deployed") val lastDeployed: String = "",
        val notes: String = "",
    )

    fun toInfo() = ReleaseStatus(
        name = name,
        namespace = namespace,
        revision = version,
        status = info.status,
        description = info.description,
        firstDeployed = info.firstDeployed,
        lastDeployed = info.lastDeployed,
        applyMethod = applyMethod,
    )
}

data class ReleaseStatus(
    val name: String,
    val namespace: String,
    val revision: Int,
    val status: String,
    val description: String,
    val firstDeployed: String,
    val lastDeployed: String,
    val applyMethod: String,
)

/** A repo from `helm repo list -o json`. */
@Serializable
data class RepoInfo(
    val name: String = "",
    val url: String = "",
)

/** A hit from `helm search repo -o json`. */
@Serializable
internal data class RawSearchHit(
    val name: String = "",
    val version: String = "",
    @SerialName("app_version") val appVersion: String = "",
    val description: String = "",
) {
    fun toInfo() = SearchHit(name, version, appVersion, description)
}

data class SearchHit(
    val name: String,
    val version: String,
    val appVersion: String,
    val description: String,
)

/**
 * A Helm chart directory found in the open project, plus the `values*.yaml` files
 * sitting beside it.
 *
 * Three values files in one chart is the normal case here (the user's real chart
 * has `values.yaml`, `values-dev.yaml`, `values-prod.yaml`), so a picker is part
 * of the model rather than an afterthought.
 */
data class ChartArtifact(
    val chartFile: File,
    val relativePath: String,
    val name: String,
    val version: String,
    val appVersion: String,
    val valuesFiles: List<File>,
) {
    val directory: File get() = chartFile.parentFile ?: chartFile

    /** Default release name suggestion: the chart dir, sanitised for Kubernetes. */
    val suggestedRelease: String
        get() = directory.name.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { "release" }

    fun valuesFileNamed(name: String?): File? =
        name?.let { wanted -> valuesFiles.firstOrNull { it.name == wanted } }
}

/**
 * Redact Kubernetes Secret payloads out of rendered YAML.
 *
 * `helm get manifest` and `helm template` emit every object a chart produces, and
 * a chart that produces a Secret emits its values in the clear (`stringData`) or
 * as base64 (`data`) — verified against a fixture chart. The rest of this plugin
 * refuses to render secret values, so rendered output is filtered to match.
 *
 * This is a display-layer net over helm's own well-formed output, not a general
 * YAML parser, and it is written to **fail safe**: a document that mentions
 * `kind: Secret` anywhere has every `data:`/`stringData:` block redacted, and an
 * unparseable indentation shape keeps redacting rather than stopping.
 */
internal fun redactRenderedYaml(yaml: String): String {
    if (yaml.isBlank()) return yaml

    val out = StringBuilder()
    val current = mutableListOf<String>()

    fun flush() {
        if (current.isEmpty()) return
        val document = current.joinToString("\n")
        out.append(if (mentionsSecretKind(document)) redactSecretBlocks(document) else document)
        current.clear()
    }

    // Walk line by line so `---` separators (and any leading comments helm emits
    // above them) come out byte-identical; only document bodies are rewritten.
    for (line in yaml.lines()) {
        if (SEPARATOR_REGEX.matches(line)) {
            flush()
            out.append('\n').append(line).append('\n')
            continue
        }
        current.add(line)
    }
    flush()
    return out.toString().trim('\n').let { if (yaml.endsWith("\n")) "$it\n" else it }
}

private val SEPARATOR_REGEX = Regex("^---\\s*$")

private fun mentionsSecretKind(document: String): Boolean =
    Regex("(?m)^\\s*kind:\\s*[\"']?Secret[\"']?\\s*$").containsMatchIn(document)

/**
 * Replace scalar values inside `data:`/`stringData:` mappings with a marker that
 * still shows the key and the size, so the shape of the object stays reviewable.
 */
private fun redactSecretBlocks(document: String): String {
    val lines = document.lines().toMutableList()
    var insideBlock = false
    var blockIndent = -1

    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length

        if (!insideBlock) {
            if (Regex("^(data|stringData):\\s*$").containsMatchIn(trimmed)) {
                insideBlock = true
                blockIndent = indent
            }
            continue
        }

        // Blank lines inside a block are harmless; keep scanning.
        if (trimmed.isBlank()) continue

        // Dedent to or past the block key ends the block.
        if (indent <= blockIndent) {
            insideBlock = false
            blockIndent = -1
            if (Regex("^(data|stringData):\\s*$").containsMatchIn(trimmed)) {
                insideBlock = true
                blockIndent = indent
            }
            continue
        }

        val key = trimmed.substringBefore(':', "")
        if (key.isBlank() || !trimmed.contains(':')) continue
        val rawValue = trimmed.substringAfter(':').trim().trim('"', '\'')
        if (rawValue.isEmpty()) continue
        lines[i] = " ".repeat(indent) + "$key: <redacted: ${rawValue.length} chars>"
    }
    return lines.joinToString("\n")
}
