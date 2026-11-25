package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.Copy
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BundledLicense(val key: String, val title: String, val asset: String)

internal val bundledLicenses = listOf(
  BundledLicense("keihome", "KeiHome · Apache 2.0", "licenses/KeiHome-Apache-2.0.txt"),
  BundledLicense("apache", "Apache 2.0", "licenses/Apache-2.0.txt"),
  BundledLicense("android-tools-notice", "Android Tools · NOTICE", "licenses/AndroidTools-NOTICE.txt"),
  BundledLicense("compose-icons-mit", "Compose Icons · MIT", "licenses/ComposeIcons-MIT.txt"),
  BundledLicense("dexkit-apache", "DexKit · Apache 2.0", "licenses/DexKit-Apache-2.0.txt"),
  BundledLicense("dexkit-lgpl", "DexKit · LGPL 3.0", "licenses/DexKit-LGPL-3.0.txt"),
  BundledLicense("dexkit-source", "DexKit · Source", "licenses/DexKit-SOURCE.txt"),
  BundledLicense("gpl", "GNU GPL 3.0", "licenses/GPL-3.0.txt"),
  BundledLicense("materialkolor", "MaterialKolor · MIT", "licenses/MaterialKolor-MIT.txt"),
  BundledLicense("mmkv", "MMKV · BSD 3-Clause", "licenses/MMKV-BSD-3-Clause.txt"),
  BundledLicense("commonmark", "CommonMark · BSD 2-Clause", "licenses/CommonMark-BSD-2-Clause.txt"),
  BundledLicense("jgit", "Eclipse JGit · EDL 1.0", "licenses/JGit-EDL-1.0.txt"),
  BundledLicense("slf4j", "SLF4J · MIT", "licenses/SLF4J-MIT.txt"),
  BundledLicense("commons-codec-notice", "Apache Commons Codec · NOTICE", "licenses/Commons-Codec-NOTICE.txt"),
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
  return key(asset, retry, context) {
    produceState(NoticeText()) {
      value = try {
        NoticeText(withContext(Dispatchers.IO) {
          context.assets.open(asset).bufferedReader().use { it.readText() }
        })
      } catch (_: IOException) {
        NoticeText(failed = true)
      }
    }
  }
}

@Composable
internal fun NoticesDestinationScreen(route: String, onBack: () -> Unit, onNavigate: (String) -> Unit) {
  val license = bundledLicenseForRoute(route)
  val component = thirdPartyComponentForRoute(route)
  val category = noticeCategoryForRoute(route)
  val title = when (license?.key) {
    "keihome" -> stringResource(R.string.project_license_title)
    "notices" -> stringResource(R.string.notices_full)
    "dexkit-source" -> stringResource(R.string.notices_source_offer)
    else -> license?.title ?: component?.name ?: stringResource(category?.title ?: R.string.settings_notices)
  }
  var retry by remember(route) { mutableIntStateOf(0) }
  val document = if (license != null) noticeText(license.asset, retry).value else NoticeText()
  val context = LocalContext.current
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behavior = MiuixScrollBehavior()
  Scaffold(backdrop = backdrop, modifier = Modifier.testTag("notices.page"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = title, scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack, minHeight = 48.dp, minWidth = 48.dp,
            modifier = Modifier.testTag("notices.back")) {
            Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
          }
        }, actions = {
          if (route == "notices" || category != null) {
            OverlayIconCascadingDropdownMenu(
              entries = listOf(DropdownEntry(items = listOf(
                DropdownItem(text = stringResource(R.string.notices_full),
                  onClick = { onNavigate("license/notices") }),
              ))),
              modifier = Modifier.testTag("notices.documents"),
            ) {
              Icon(MiuixIcons.Glass.More, stringResource(R.string.project_license_documents_menu))
            }
          }
          if (license != null) IconButton(
            enabled = document.text != null,
            minHeight = 48.dp, minWidth = 48.dp,
            modifier = Modifier.testTag("notices.copy"),
            onClick = {
              document.text?.let { text ->
                context.getSystemService(ClipboardManager::class.java)
                  .setPrimaryClip(ClipData.newPlainText(title, text))
              }
            },
          ) { Icon(MiuixIcons.Glass.Copy, stringResource(R.string.notices_copy_document)) }
        })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().imePadding()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      contentAlignment = Alignment.TopCenter) {
      key(route) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize()
          .testTag("notices.scroll").keiHomeTopBarScroll(behavior)
          .verticalScroll(rememberScrollState(), overscrollEffect = null)
          .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 8.dp,
            bottom = insets.calculateBottomPadding() + 32.dp)) {
          when {
            license != null -> {
              if (license.key == "keihome") ProjectLicenseSummary()
              LicenseDocument(document) { retry++ }
            }
            component != null -> ThirdPartyComponentDetails(component, onNavigate)
            else -> ThirdPartyNotices(onNavigate, category)
          }
        }
      }
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
internal fun ThirdPartyNotices(onNavigate: (String) -> Unit, category: NoticeCategory? = null) {
  val resources = LocalResources.current
  var query by rememberSaveable(category?.key) { mutableStateOf("") }
  var retry by remember { mutableIntStateOf(0) }
  val state by noticeText("third-party-dependencies.tsv", retry)
  val dependencies = remember(state.text) {
    state.text?.let { runCatching { parseBundledDependencies(it) }.getOrNull() }
  }
  val included = remember(dependencies) {
    thirdPartyComponents.filter {
      it.kind != AttributionKind.DEPENDENCY || it.dependencies(dependencies.orEmpty()).isNotEmpty()
    }
  }
  val terms = remember(query) { query.trim().split(Regex("\\s+")).filter(String::isNotBlank) }
  val projectLicenseTitle = stringResource(R.string.project_license_title)
  val showProjectLicense = category == null &&
    terms.all { "$projectLicenseTitle Apache License 2.0".contains(it, ignoreCase = true) }
  val filtered = included.filter { component ->
    val searchable = listOf(component.name, resources.getString(component.description), component.source,
      component.licenseKeys.joinToString(" ") { key -> bundledLicenses.first { it.key == key }.title },
      component.dependencies(dependencies.orEmpty()).joinToString(" ") { it.coordinate }).joinToString(" ")
    (category == null || component.category == category) &&
      terms.all { searchable.contains(it, ignoreCase = true) }
  }
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Os4SearchField(query = query, onQueryChange = { query = it },
      label = stringResource(R.string.notices_search),
      inputModifier = Modifier.testTag("notices.search"))
    if (showProjectLicense) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        BasicComponent(title = projectLicenseTitle, summary = "Apache License 2.0",
          modifier = Modifier.testTag("notices.project-license"),
          onClick = { onNavigate("license/keihome") },
          endActions = { Icon(MiuixIcons.Glass.ChevronForward, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
      }
    }
    if (dependencies == null) NoticePlaceholder(state.failed || state.text != null) { retry++ }
    if (filtered.isEmpty() && dependencies != null && !showProjectLicense) {
      Text(stringResource(R.string.notices_search_empty), style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        modifier = Modifier.testTag("notices.empty").padding(16.dp))
    }
    if (category == null && terms.isEmpty()) {
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        NoticeCategory.entries.forEach { entry ->
          val count = filtered.count { it.category == entry }
          if (count > 0) BasicComponent(
            title = stringResource(R.string.project_license_group_count, stringResource(entry.title), count),
            modifier = Modifier.testTag("notices.category.${entry.key}"),
            onClick = { onNavigate("notices/category/${entry.key}") },
            endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
          )
        }
      }
      return@Column
    }
    NoticeCategory.entries.forEach { entry ->
      val entries = filtered.filter { it.category == entry }
      if (entries.isNotEmpty()) {
        val groupTitle = stringResource(entry.title)
        SmallTitle(stringResource(R.string.project_license_group_count, groupTitle, entries.size))
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          entries.forEach { component ->
            val versions = component.dependencies(dependencies.orEmpty()).map { it.version }.distinct()
            BasicComponent(
              title = component.name,
              summary = listOfNotNull(stringResource(component.description), versions.singleOrNull()).joinToString("\n"),
              onClick = { onNavigate("component/${component.key}") },
              modifier = Modifier.testTag("notices.component.${component.key}"),
              endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
            )
          }
        }
      }
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
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(component.name, style = MiuixTheme.textStyles.title2, modifier = Modifier.semantics { heading() })
      Text(stringResource(component.description), style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceSecondary)
      if (component.key == "back-gesture") {
        Text(stringResource(R.string.notices_back_gesture_thanks), style = MiuixTheme.textStyles.body2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary)
      }
    }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
      BasicComponent(title = stringResource(R.string.notices_source),
        summary = component.source.removePrefix("https://"),
        modifier = Modifier.testTag("notices.source"),
        onClick = {
          try {
            uriHandler.openUri(component.source)
          } catch (_: RuntimeException) {
            Toast.makeText(context, R.string.notices_source_unavailable, Toast.LENGTH_SHORT).show()
          }
        }, endActions = { Icon(actionIcon(PageActionIcon.OPEN_EXTERNAL), null) })
    }
    if (component.licenseKeys.isNotEmpty()) {
      SmallTitle(stringResource(R.string.settings_license_files))
      Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        component.licenseKeys.forEach { key ->
          val license = bundledLicenses.first { it.key == key }
          BasicComponent(title = if (key == "dexkit-source") stringResource(R.string.notices_source_offer) else license.title,
            modifier = Modifier.testTag("notices.license.$key"),
            onClick = { onNavigate("license/$key") },
            endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) })
        }
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
private fun ProjectLicenseSummary() {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.project_license_copyright), style = MiuixTheme.textStyles.title4,
      modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.project_license_scope), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
    Text(stringResource(R.string.project_license_source_status), style = MiuixTheme.textStyles.body2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
  }
}

@Composable
private fun LicenseDocument(state: NoticeText, onRetry: () -> Unit) {
  val content = state.text
  if (content == null) NoticePlaceholder(state.failed, onRetry)
  else Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
    SelectionContainer {
      Text(content, style = MiuixTheme.textStyles.body2, modifier = Modifier.testTag("notices.document"))
    }
  }
}
