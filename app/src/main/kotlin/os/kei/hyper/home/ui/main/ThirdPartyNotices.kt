package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BundledLicense(val key: String, val title: String, val asset: String)

private val bundledLicenses = listOf(
  BundledLicense("dexkit-apache", "DexKit · Apache 2.0", "licenses/DexKit-Apache-2.0.txt"),
  BundledLicense("dexkit-lgpl", "DexKit · LGPL 3.0", "licenses/DexKit-LGPL-3.0.txt"),
  BundledLicense("dexkit-source", "DexKit · Source", "licenses/DexKit-SOURCE.txt"),
  BundledLicense("gpl", "GNU GPL 3.0", "licenses/GPL-3.0.txt"),
  BundledLicense("materialkolor", "MaterialKolor · MIT", "licenses/MaterialKolor-MIT.txt"),
  BundledLicense("mmkv", "MMKV · BSD 3-Clause", "licenses/MMKV-BSD-3-Clause.txt"),
  BundledLicense("lsplt", "LSPlt · LGPL 3.0", "licenses/LSPlt-LGPL-3.0.txt"),
)

internal fun bundledLicenseForRoute(route: String): BundledLicense? =
  bundledLicenses.firstOrNull { route == "license/${it.key}" }

@Composable
internal fun ThirdPartyNotices(onNavigate: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SmallTitle(stringResource(R.string.settings_license_files))
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      bundledLicenses.forEach { license ->
        BasicComponent(title = license.title, onClick = { onNavigate("license/${license.key}") },
          endActions = { Icon(MiuixIcons.Basic.ArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
      }
    }
    LicenseDocument("THIRD_PARTY_NOTICES.md")
  }
}

@Composable
internal fun LicenseDocument(asset: String) {
  val context = LocalContext.current
  val content by produceState<String?>(null, context, asset) {
    value = withContext(Dispatchers.IO) {
      runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
    }
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    SelectionContainer {
      Text(when (content) {
        null -> stringResource(R.string.settings_notice_loading)
        "" -> stringResource(R.string.settings_notice_unavailable)
        else -> content.orEmpty()
      }, style = MiuixTheme.textStyles.body2)
    }
  }
}
