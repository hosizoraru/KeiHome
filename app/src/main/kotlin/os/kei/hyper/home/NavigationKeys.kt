package os.kei.hyper.home

import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlinx.serialization.Serializable

@Serializable sealed interface HomeRoute : NavKey
@Serializable data object Main : HomeRoute
@Serializable data class PackRoute(val section: String) : HomeRoute
