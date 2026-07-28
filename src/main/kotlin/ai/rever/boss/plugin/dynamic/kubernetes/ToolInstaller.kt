package ai.rever.boss.plugin.dynamic.kubernetes

import java.io.File

/** A CLI this plugin needs and can help install. */
enum class InstallableTool(
    val binary: String,
    val label: String,
    /** Homebrew formula. `kubectl` is an alias for `kubernetes-cli`. */
    val brewFormula: String,
    val docsUrl: String,
) {
    KUBECTL("kubectl", "kubectl", "kubectl", "https://kubernetes.io/docs/tasks/tools/"),
    HELM("helm", "helm", "helm", "https://helm.sh/docs/intro/install/"),
    ;

    fun isPresent(): Boolean = when (this) {
        KUBECTL -> KubectlCli.isInstalled()
        HELM -> HelmCli.isInstalled()
    }
}

/**
 * Helps the user install a missing CLI, without ever doing it behind their back.
 *
 * The plugin deliberately does **not** install anything on its own: it offers a
 * command, the user confirms, and the command then runs in the plugin's terminal
 * tab where it is visible and interruptible. Installing developer tooling touches
 * PATH, sometimes wants sudo, and differs per platform — that is not something to
 * do silently from a sidebar.
 *
 * A terminal install is only offered when Homebrew is actually present. Anywhere
 * else the honest answer is a link to the tool's own instructions rather than a
 * guessed package manager, and piping a remote script into a shell is not
 * something this plugin will suggest.
 */
object ToolInstaller {

    private val brewCandidates: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        listOf(
            "/opt/homebrew/bin/brew", // Apple Silicon
            "/usr/local/bin/brew", // Intel macOS
            "/home/linuxbrew/.linuxbrew/bin/brew",
            "$home/.linuxbrew/bin/brew",
        )
    }

    /** The `brew` binary, or null when Homebrew isn't installed. */
    fun brew(): File? {
        val onPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, "brew") }
        return (onPath + brewCandidates.map(::File)).firstOrNull { it.isFile && it.canExecute() }
    }

    fun canInstallWithBrew(): Boolean = brew() != null

    /**
     * The shell command to install [tool], or null when we have no safe suggestion
     * and the caller should open [InstallableTool.docsUrl] instead.
     *
     * Uses a bare `brew` rather than the resolved absolute path: this string runs in
     * a login shell where brew is on PATH, and showing `brew install helm` is what
     * the user can recognise and repeat themselves.
     */
    fun installCommand(tool: InstallableTool): String? =
        if (canInstallWithBrew()) "brew install ${tool.brewFormula}" else null
}
