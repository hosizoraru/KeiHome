package os.kei.hyper.home.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.ReleaseNote
import os.kei.hyper.home.data.ReleaseNotesCatalog
import os.kei.hyper.home.data.ReleaseVersion
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4DisclosureContent
import os.kei.hyper.home.ui.components.Os4DisclosureHeader
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.icon.glass.SelectAll
import top.yukonga.miuix.kmp.icon.glass.UsageHelp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ReleaseNotesScreen(
  onBack: () -> Unit,
  currentVersion: String = BuildConfig.VERSION_NAME.substringBefore('+'),
  highlightedVersions: Set<String> = emptySet(),
  onContinue: (() -> Unit)? = null,
  unreadVersions: Set<String> = highlightedVersions,
  onReadVersions: (Set<String>) -> Unit = {},
  onViewedVersionsChanged: (Set<String>) -> Unit = {},
  onOpenGuide: (() -> Unit)? = null,
) {
  val parsedCurrent = remember(currentVersion) { ReleaseVersion.parse(currentVersion) }
  val releases = remember(parsedCurrent) {
    ReleaseNotesCatalog.releases.filter { release ->
      val version = ReleaseVersion.parse(release.version)
      parsedCurrent != null && version != null && version <= parsedCurrent
    }
  }
  var openedVersions by rememberSaveable(
    currentVersion,
    onContinue != null,
    stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
  ) { mutableStateOf(ReleaseNotesCatalog.initiallyExpandedVersions(currentVersion, upgrade = onContinue != null)) }
  var viewedVersions by rememberSaveable { mutableStateOf(openedVersions) }
  var unreadOnly by rememberSaveable { mutableStateOf(false) }
  val leave = { onReadVersions(viewedVersions.toSet()); onBack() }
  LaunchedEffect(viewedVersions) { onViewedVersionsChanged(viewedVersions.toSet()) }
  val unreadCount = releases.count { it.version in unreadVersions }
  var actionHeight by remember { mutableIntStateOf(0) }
  val actionClearance = if (onContinue != null) {
    maxOf(100.dp, with(LocalDensity.current) { actionHeight.toDp() } + 24.dp)
  } else 24.dp
  val scroll = rememberScrollState()
  val behavior = MiuixScrollBehavior()
  val backdrop = rememberKeiHomeBlurBackdrop()
  val surface = MiuixTheme.colorScheme.surface
  Scaffold(
    backdrop = backdrop,
    modifier = Modifier.testTag("release-notes.page"),
    topBar = {
      BlurredBar(backdrop, backdrop != null) {
        TopAppBar(
          title = stringResource(R.string.release_notes_ui_title),
          modifier = Modifier.testTag("release-notes.topbar"),
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          scrollBehavior = behavior,
          navigationIcon = {
            IconButton(onClick = leave, minHeight = 48.dp, minWidth = 48.dp,
              modifier = Modifier.testTag("release-notes.back")) {
              Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
            }
          },
          actions = {
            if (onContinue == null && unreadCount > 0) {
              IconButton(onClick = { onReadVersions(releases.map { it.version }.toSet()) },
                modifier = Modifier.testTag("release-notes.read-all")) {
                Icon(MiuixIcons.Glass.SelectAll, stringResource(R.string.release_notes_ui_read_all))
              }
            }
          },
          bottomContent = {
            Os4ViewTabs(items = listOf(
              stringResource(R.string.browser_view_count, stringResource(R.string.release_notes_ui_all), releases.size),
              stringResource(R.string.browser_view_count, stringResource(R.string.release_notes_ui_unread), unreadCount)),
              selectedIndex = if (unreadOnly) 1 else 0, onSelect = { unreadOnly = it == 1 },
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("release-notes.views"))
          },
        )
      }
    },
    floatingActionButton = {
      if (onContinue != null) {
        PageActionFab(
          label = stringResource(R.string.release_notes_ui_continue),
          icon = MiuixIcons.Glass.ChevronForward,
          onClick = { onReadVersions(viewedVersions.toSet()); onContinue() },
          trailingIcon = true,
          onHeightChanged = { actionHeight = it },
          modifier = Modifier.widthIn(max = 320.dp).testTag("release-notes.continue"),
        )
      }
    },
  ) { insets ->
    Box(
      Modifier.fillMaxSize()
        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      contentAlignment = Alignment.TopCenter,
    ) {
      Crossfade(targetState = unreadOnly to if (unreadOnly) unreadVersions else emptySet(),
        animationSpec = tween(180), label = "release-notes.views") { (onlyUnread, viewUnread) ->
      val visible = releases.filter { !onlyUnread || it.version in viewUnread }
      val viewScroll = if (onlyUnread) rememberScrollState() else scroll
      Column(
        Modifier.widthIn(max = 840.dp).fillMaxSize()
          .testTag("release-notes.content")
          .drawWithCache {
            val fadeHeight = if (onContinue == null) 0f else (actionClearance + 24.dp).toPx().coerceAtMost(size.height)
            val start = size.height - fadeHeight
            val fade = Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface.copy(alpha = .6f), surface),
              startY = start, endY = size.height)
            onDrawWithContent {
              drawContent()
              if (fadeHeight > 0f) drawRect(fade, topLeft = Offset(0f, start), size = Size(size.width, fadeHeight))
            }
          }
          .keiHomeTopBarScroll(behavior)
          .verticalScroll(viewScroll, overscrollEffect = null)
          .padding(
            start = 16.dp, end = 16.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + actionClearance + 12.dp,
          ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (onOpenGuide != null) {
          Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
            BasicComponent(title = stringResource(R.string.setup_title),
              summary = stringResource(R.string.setup_replay_summary), onClick = onOpenGuide,
              startAction = { Icon(MiuixIcons.Glass.UsageHelp, null, tint = MiuixTheme.colorScheme.primary) },
              endActions = { Icon(MiuixIcons.Glass.ChevronForward, null) },
              modifier = Modifier.testTag("release-notes.guide"))
          }
        }
        visible.forEach { release ->
          key(release.version) {
            ReleaseNotesCard(
              release = release,
              expanded = release.version in openedVersions,
              highlighted = release.version in unreadVersions,
              onRead = { onReadVersions(setOf(release.version)) },
              onToggle = {
                openedVersions = if (release.version in openedVersions) {
                  openedVersions - release.version
                } else {
                  viewedVersions = (viewedVersions + release.version).distinct()
                  openedVersions + release.version
                }
              },
            )
          }
        }
        if (visible.isEmpty()) {
          Text(stringResource(if (onlyUnread) R.string.release_notes_ui_no_unread else R.string.release_notes_ui_empty),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("release-notes.end"))
      }
      }
    }
  }
}

@Composable
private fun ReleaseNotesCard(
  release: ReleaseNote,
  expanded: Boolean,
  highlighted: Boolean,
  onToggle: () -> Unit,
  onRead: () -> Unit,
) {
  val date = remember(release.committedAt) {
    release.committedAt?.let { timestamp ->
      runCatching {
        // Keep the target commit's own calendar date; the tag creation date is unrelated.
        OffsetDateTime.parse(timestamp).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
      }.getOrNull()
    }
  }
  Card(Modifier.fillMaxWidth().testTag("release-notes.release.${release.version}"),
    insideMargin = PaddingValues(0.dp)) {
    Os4DisclosureHeader(
      title = release.version,
      summary = null,
      expanded = expanded,
      actionLabel = stringResource(
        if (expanded) R.string.release_notes_ui_collapse else R.string.release_notes_ui_expand,
        release.version,
      ),
      stateLabel = stringResource(
        if (expanded) R.string.release_notes_ui_expanded else R.string.release_notes_ui_collapsed,
      ),
      onToggle = onToggle,
      modifier = Modifier.testTag("release-notes.toggle.${release.version}").semantics { heading() },
      titleContent = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
          Text(release.version, style = MiuixTheme.textStyles.headline1,
            modifier = Modifier.testTag("release-notes.version.${release.version}"))
          if (release.committedAt != null) {
            MetadataPill(date ?: stringResource(R.string.release_notes_ui_date_unavailable),
              MiuixTheme.colorScheme.onSurfaceSecondary,
              Modifier.testTag("release-notes.date.${release.version}"))
          }
          if (highlighted) {
            MetadataPill(stringResource(R.string.release_notes_ui_unread),
              MiuixTheme.colorScheme.primary)
          }
        }
      },
    )
    Os4DisclosureContent(expanded) {
      ReleaseNotesBody(release)
      if (highlighted) TextButton(text = stringResource(R.string.release_notes_ui_mark_read), onClick = onRead,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("release-notes.read.${release.version}"))
    }
  }
}

@Composable
private fun ReleaseNotesBody(release: ReleaseNote) {
  val intro = if (release.introduction == 0) "" else stringResource(release.introduction)
  val changes = if (release.changes == 0) emptyList() else stringArrayResource(release.changes).toList()
  val footnote = if (release.footnote == 0) "" else stringResource(release.footnote)
  val markdown = remember(intro, changes, footnote) {
    buildString {
      if (intro.isNotBlank()) append(intro).append("\n\n")
      changes.forEachIndexed { index, change ->
        val title = change.substringBefore('\n')
        val body = change.substringAfter('\n', "")
        val item = if (body.isBlank()) change else "**$title**\n\n$body"
        append("${index + 1}. ").append(item.replace("\n", "\n    ")).append("\n\n")
      }
      if (footnote.isNotBlank()) append("> ").append(footnote.replace("\n", "\n> "))
    }.trim()
  }
  Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
    .testTag("release-notes.body.${release.version}")) {
    ReleaseNotesMarkdown(markdown)
  }
}
