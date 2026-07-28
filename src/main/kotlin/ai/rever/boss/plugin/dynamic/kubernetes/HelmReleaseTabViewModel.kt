package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State for one Helm release tab.
 *
 * Each body is fetched on first view and cached until refreshed, because
 * `helm get manifest` on a large chart is not cheap and nothing pushes updates.
 */
class HelmReleaseTabViewModel(
    private val services: KubeServices,
    val tabInfo: HelmReleaseTabInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val name: String get() = tabInfo.releaseName

    private val _section = MutableStateFlow(ReleaseSection.STATUS)
    val section: StateFlow<ReleaseSection> = _section.asStateFlow()

    private val _release = MutableStateFlow<ReleaseInfo?>(null)
    val release: StateFlow<ReleaseInfo?> = _release.asStateFlow()

    private val _status = MutableStateFlow<ReleaseStatus?>(null)
    val status: StateFlow<ReleaseStatus?> = _status.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _values = MutableStateFlow<String?>(null)
    val values: StateFlow<String?> = _values.asStateFlow()

    /** false = user-supplied only, true = fully merged (`--all`). */
    private val _allValues = MutableStateFlow(false)
    val allValues: StateFlow<Boolean> = _allValues.asStateFlow()

    private val _manifest = MutableStateFlow<String?>(null)
    val manifest: StateFlow<String?> = _manifest.asStateFlow()

    private val _history = MutableStateFlow<List<RevisionInfo>?>(null)
    val history: StateFlow<List<RevisionInfo>?> = _history.asStateFlow()

    private val _notes = MutableStateFlow<String?>(null)
    val notes: StateFlow<String?> = _notes.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        scope.launch {
            trackRelease()
            refreshStatus()
        }
        // Keep the header's revision and status honest without a watch API.
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                trackRelease()
            }
        }
    }

    fun dispose() = scope.cancel()

    private suspend fun trackRelease() {
        services.helm.refreshReleases()
        _release.value = services.helm.findRelease(name)
    }

    fun selectSection(section: ReleaseSection) {
        _section.value = section
        when (section) {
            ReleaseSection.STATUS -> if (_status.value == null) refreshStatus()
            ReleaseSection.VALUES -> if (_values.value == null) refreshValues()
            ReleaseSection.MANIFEST -> if (_manifest.value == null) refreshManifest()
            ReleaseSection.HISTORY -> if (_history.value == null) refreshHistory()
            ReleaseSection.NOTES -> if (_notes.value == null) refreshNotes()
        }
    }

    fun refreshStatus() = load {
        val (status, error) = services.helm.status(name)
        _status.value = status
        _statusError.value = error.ifBlank { null }
    }

    fun refreshValues() = load { _values.value = services.helm.values(name, _allValues.value) }

    fun setAllValues(all: Boolean) {
        if (_allValues.value == all) return
        _allValues.value = all
        _values.value = null
        refreshValues()
    }

    fun refreshManifest() = load { _manifest.value = services.helm.manifest(name) }

    fun refreshHistory() = load { _history.value = services.helm.history(name) }

    fun refreshNotes() = load { _notes.value = services.helm.notes(name) }

    /** Roll back to [revision]; the sidebar and this tab both pick it up after. */
    fun rollbackTo(revision: Int) {
        val current = _release.value ?: return
        services.helmActions.rollback(current, revision)
        scope.launch {
            delay(SETTLE_MS)
            _history.value = null
            _status.value = null
            trackRelease()
            refreshHistory()
            refreshStatus()
        }
    }

    fun test() {
        _release.value?.let { services.helmActions.test(it) }
    }

    fun valuesText(): String = _values.value.orEmpty()

    fun manifestText(): String = _manifest.value.orEmpty()

    private fun load(block: suspend () -> Unit) {
        scope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val POLL_MS = 8_000L
        const val SETTLE_MS = 3_000L
    }
}
