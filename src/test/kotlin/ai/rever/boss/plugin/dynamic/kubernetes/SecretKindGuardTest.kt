package ai.rever.boss.plugin.dynamic.kubernetes

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Secret refusal, held to the spellings that used to walk around it.
 *
 * `KubeActions.isSecretKind` was `kind.lowercase().trimEnd('s') == "secret"`, which asks
 * whether the *whole argument* is the word "secret". kubectl's own type syntax is
 * `TYPE[.VERSION][.GROUP]` and it accepts a comma-joined list (`kubectl get rc,services`),
 * so `secrets,pods` is a request that includes Secrets and that check answered "not a
 * Secret" about it. `k8s_get kind="secrets,pods" output=yaml` therefore reached
 * `kubectl get secrets,pods -o yaml` — from a tool that is read-only, ungated, and
 * callable with nobody signed in — while the code comment above it claimed a structural
 * refusal.
 *
 * Every case below is one of those spellings. They are asserted at the guard, which is
 * where the fix lives: an argument that is not a single resource type is refused rather
 * than interpreted, so nothing has to predict what kubectl would have done with it.
 *
 * Scope note: that `kubectl get secrets,pods -o yaml` really does emit Secret payloads is
 * taken from kubectl's documented list syntax. No live API server was available, so that
 * half is documented-but-unexecuted; these tests cover this plugin's decision, not
 * kubectl's behaviour.
 */
class SecretKindGuardTest {

    private val services = KubeServices(HeadlessPluginContext)

    @AfterEach
    fun tearDown() = services.dispose()

    // ------------------------------------------------- the spellings that leaked

    @Test
    fun `list and whitespace spellings are treated as Secret requests`() {
        // Each of these returned false before, so no refusal fired.
        val leaked = listOf("secrets,pods", "secret,pod", "pods,secrets", "secrets ", " secrets", "secrets\t")
        leaked.forEach {
            assertTrue(
                KubeActions.isSecretKind(it),
                "isSecretKind(${it.quoted()}) must not answer 'no' — this is the bypass class: a " +
                    "comma-joined kubectl type list, or the same word with whitespace on it.",
            )
        }
    }

    @Test
    fun `a type list is refused outright rather than interpreted`() {
        val refused = listOf(
            "secrets,pods", // the leak
            "secret,pod",
            "pods,deployments", // refused too: one type per call, whatever the types are
            "secrets/my-secret", // TYPE/NAME is kubectl syntax this plugin does not take
            "pods pods", // interior whitespace: two types, same problem as a comma
            "",
            "  ",
            "-pods", // must start with a letter
        )
        refused.forEach {
            assertNull(
                KubeActions.normalizeKind(it),
                "normalizeKind(${it.quoted()}) must reject: only a single TYPE[.VERSION][.GROUP] is " +
                    "accepted, because that is the unit isSecretKind can actually decide about.",
            )
        }
    }

    @Test
    fun `surrounding whitespace is normalized away, not accepted as a different type`() {
        // The other half of the trailing-space case: `secrets ` is a typo rather than an
        // attack, so it is trimmed to a type that the Secret check then catches. What must
        // never happen is it being *passed through* as some type that isn't Secrets — the
        // old check let exactly that slip because trimEnd('s') is a no-op on a trailing
        // space.
        assertEquals("secrets", KubeActions.normalizeKind("secrets "))
        assertEquals("secrets", KubeActions.normalizeKind("  secrets  "))
        assertTrue(KubeActions.isSecretKind("secrets "))
    }

    @Test
    fun `the versioned and cased spellings of Secrets are still Secrets`() {
        listOf("secret", "secrets", "Secrets", "SECRET", "secrets.v1.", "secrets.v1.core", " secrets ")
            .forEach {
                assertTrue(
                    KubeActions.isSecretKind(it),
                    "isSecretKind(${it.quoted()}) must be true — only the TYPE segment decides, and " +
                        "these all name core Secrets.",
                )
            }
    }

    @Test
    fun `ordinary kinds and secret-ish CRDs are unaffected`() {
        listOf("pods", "pod", "deployments", "svc", "configmaps", "mycrds.example.com")
            .forEach {
                assertEquals(it.trim(), KubeActions.normalizeKind(it), "normalizeKind(${it.quoted()})")
                assertFalse(KubeActions.isSecretKind(it), "isSecretKind(${it.quoted()}) must be false")
            }

        // The TYPE segment is what decides, so a CRD whose name merely ends in "secrets"
        // is not the core Secret type and stays readable. Over-refusing these would break
        // sealed-secrets and external-secrets workflows for no gain.
        listOf("sealedsecrets.bitnami.com", "externalsecrets.external-secrets.io")
            .forEach {
                assertFalse(
                    KubeActions.isSecretKind(it),
                    "isSecretKind(${it.quoted()}) must be false — a CRD, not core v1 Secret",
                )
            }
    }

    // ------------------------------------------------------ the refusal in place

    @Test
    fun `KubeActions yaml refuses a type list before it reaches kubectl`() = runBlocking {
        val result = services.actions.yaml("secret,pod", "my-secret")

        assertFalse(result.ok, "A type list must not be forwarded to `kubectl get ... -o yaml`")
        assertEquals(KubeActions.KIND_REFUSAL, result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `KubeActions yaml still refuses plain Secrets`() = runBlocking {
        val result = services.actions.yaml("secrets", "my-secret")

        assertFalse(result.ok)
        assertTrue(
            result.stderr.contains("disabled for Secrets"),
            "Expected the Secret-specific refusal, got: ${result.stderr}",
        )
    }

    private fun String.quoted() = "\"$this\""
}
