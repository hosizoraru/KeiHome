package os.kei.hyper.home

import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlinx.serialization.Serializable

@Serializable sealed interface HomeRoute : NavKey
@Serializable data object Main : HomeRoute
@Serializable data class PackRoute(val section: String) : HomeRoute
@Serializable data class SettingsRoute(val section: String) : HomeRoute
@Serializable data object ReleaseNotesUpgradeRoute : HomeRoute
@Serializable data class DesktopRoute(val section: String) : HomeRoute
@Serializable data class OnboardingRoute(val page: Int, val replay: Boolean = false) : HomeRoute
@Serializable data object OnboardingDesktop : HomeRoute
