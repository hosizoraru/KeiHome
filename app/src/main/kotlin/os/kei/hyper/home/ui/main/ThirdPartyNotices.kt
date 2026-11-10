package os.kei.hyper.home.ui.main

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BundledLicense(val key: String, val title: String, val asset: String)

internal val bundledLicenses = listOf(
  BundledLicense("apache", "Apache 2.0", "licenses/Apache-2.0.txt"),
  BundledLicense("android-tools-notice", "Android Tools · NOTICE", "licenses/AndroidTools-NOTICE.txt"),
  BundledLicense("compose-icons-mit", "Compose Icons · MIT", "licenses/ComposeIcons-MIT.txt"),
  BundledLicense("dexkit-apache", "DexKit · Apache 2.0", "licenses/DexKit-Apache-2.0.txt"),
  BundledLicense("dexkit-lgpl", "DexKit · LGPL 3.0", "licenses/DexKit-LGPL-3.0.txt"),
  BundledLicense("dexkit-source", "DexKit · Source", "licenses/DexKit-SOURCE.txt"),
  BundledLicense("gpl", "GNU GPL 3.0", "licenses/GPL-3.0.txt"),
  BundledLicense("materialkolor", "MaterialKolor · MIT", "licenses/MaterialKolor-MIT.txt"),
  BundledLicense("mmkv", "MMKV · BSD 3-Clause", "licenses/MMKV-BSD-3-Clause.txt"),
  BundledLicense("libcxx", "libc++ · Apache 2.0 / LLVM", "licenses/libcxx-LICENSE.txt"),
  BundledLicense("lsplt", "LSPlt · LGPL 3.0", "licenses/LSPlt-LGPL-3.0.txt"),
  BundledLicense("lucide-isc", "Lucide Icons · ISC / MIT", "licenses/Lucide-ISC.txt"),
  BundledLicense("notices", "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md"),
)

internal fun bundledLicenseForRoute(route: String): BundledLicense? =
  bundledLicenses.firstOrNull { route == "license/${it.key}" }

private data class NoticeText(val text: String? = null, val failed: Boolean = false)

@Composable
private fun noticeText(asset: String, retry: Int): State<NoticeText> {
  val context = LocalContext.current
  return produceState(NoticeText(), asset, retry, context) {
    value = NoticeText()
    value = try {
      NoticeText(withContext(Dispatchers.IO) {
        context.assets.open(asset).bufferedReader().use { it.readText() }
      })
    } catch (_: IOException) {
      NoticeText(failed = true)
    }
  }
}

@Composable
private fun NoticePlaceholder(failed: Boolean, onRetry: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    BasicComponent(
      title = stringResource(if (failed) R.string.settings_notice_unavailable else R.string.settings_notice_loading),
      summary = if (failed) stringResource(R.string.notices_retry) else null,
      onClick = if (failed) onRetry else null,
      endActions = { if (!failed) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp) },
    )
  }
}

@Composable
internal fun ThirdPartyNotices(onNavigate: (String) -> Unit) {
  var retry by remember { mutableIntStateOf(0) }
  val state by noticeText("third-party-dependencies.tsv", retry)
  val dependencies = remember(state.text) {
    state.text?.let { runCatching { parseBundledDependencies(it) }.getOrNull() }
  }
  if (dependencies == null) {
    NoticePlaceholder(state.failed || state.text != null) { retry++ }
    return
  }
  val included = remember(dependencies) {
    thirdPartyComponents.filter { it.kind != AttributionKind.DEPENDENCY || it.dependencies(dependencies).isNotEmpty() }
  }
  val componentCount = included.count { it.kind == AttributionKind.DEPENDENCY }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.notices_thanks), style = MiuixTheme.textStyles.title4)
        Text(stringResource(R.string.notices_intro), style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
        MetadataPill(pluralStringResource(R.plurals.notices_component_count, componentCount, componentCount),
          MiuixTheme.colorScheme.primary)
      }
    }
    AttributionKind.entries.forEach { kind ->
      val entries = included.filter { it.kind == kind }
      if (entries.isNotEmpty()) {
        SmallTitle(stringResource(when (kind) {
          AttributionKind.DEPENDENCY -> R.string.notices_dependencies
          AttributionKind.SOURCE -> R.string.notices_sources
          AttributionKind.HISTORY -> R.string.notices_history
        }))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          entries.forEach { component ->
            val versions = component.dependencies(dependencies).map { it.version }.distinct()
            BasicComponent(
              title = component.name,
              summary = listOfNotNull(stringResource(component.description), versions.singleOrNull()).joinToString("\n"),
              onClick = { onNavigate("component/${component.key}") },
              endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) },
            )
          }
        }
      }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      BasicComponent(title = stringResource(R.string.notices_full),
        summary = stringResource(R.string.notices_full_summary),
        onClick = { onNavigate("license/notices") },
        endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) })
    }
  }
}

@Composable
internal fun ThirdPartyComponentDetails(component: ThirdPartyComponent, onNavigate: (String) -> Unit) {
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  var retry by remember { mutableIntStateOf(0) }
  val state by noticeText("third-party-dependencies.tsv", retry)
  val versions = remember(state.text, component) {
    state.text?.let { runCatching { component.dependencies(parseBundledDependencies(it)) }.getOrNull() }
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
      Text(stringResource(component.description), style = MiuixTheme.textStyles.body2)
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      BasicComponent(title = stringResource(R.string.notices_source),
        summary = component.source.removePrefix("https://"),
        onClick = {
          try {
            uriHandler.openUri(component.source)
          } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.notices_source_unavailable, Toast.LENGTH_SHORT).show()
          }
        }, endActions = { Icon(actionIcon(PageActionIcon.OPEN_EXTERNAL), null) })
    }
    SmallTitle(stringResource(R.string.settings_license_files))
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      component.licenseKeys.forEach { key ->
        val license = bundledLicenses.first { it.key == key }
        BasicComponent(title = if (key == "dexkit-source") stringResource(R.string.notices_source_offer) else license.title,
          onClick = { onNavigate("license/$key") },
          endActions = { Icon(MiuixIcons.Basic.ArrowRight, null) })
      }
    }
    if (component.kind == AttributionKind.DEPENDENCY) {
      SmallTitle(stringResource(R.string.notices_versions))
      if (versions == null) NoticePlaceholder(state.failed || state.text != null) { retry++ }
      else Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        SelectionContainer {
          Text(versions.joinToString("\n\n") { "${it.group}:${it.module}\n${it.version}" }, style = MiuixTheme.textStyles.body2)
        }
      }
    }
  }
}

@Composable
internal fun LicenseDocument(asset: String) {
  var retry by remember { mutableIntStateOf(0) }
  val state by noticeText(asset, retry)
  val content = state.text
  if (content == null) NoticePlaceholder(state.failed) { retry++ }
  else Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    SelectionContainer { Text(content, style = MiuixTheme.textStyles.body2) }
  }
}
