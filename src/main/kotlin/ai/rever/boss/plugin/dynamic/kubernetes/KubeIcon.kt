package ai.rever.boss.plugin.dynamic.kubernetes

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.SimpleIcons
import compose.icons.simpleicons.Kubernetes

/**
 * The Kubernetes helm, used for the sidebar item, resource tabs and empty states —
 * one alias so they can never drift apart.
 *
 * Comes from `simple-icons`, which `buildPluginJar` does NOT bundle; the host's
 * composeApp depends on the same artifact, so it resolves through the classloader
 * fallback at runtime.
 */
val KubeIcon: ImageVector get() = SimpleIcons.Kubernetes
