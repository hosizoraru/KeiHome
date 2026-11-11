package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.ReleaseNote
import os.kei.hyper.home.data.ReleaseNotesCatalog
import os.kei.hyper.home.data.ReleaseVersion
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.Os4DisclosureContent
import os.kei.hyper.home.ui.components.Os4DisclosureHeader
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ReleaseNotesScreen(
  onBack: () -> Unit,
  currentVersion: String = BuildConfig.VERSION_NAME.substringBefore('+'),
  highlightedVersions: Set<String> = emptySet(),
  onContinue: (() -> Unit)? = null,
) {
  val parsedCurrent = remember(currentVersion) { ReleaseVersion.parse(currentVersion) }
  val releases = remember(parsedCurrent) {
    ReleaseNotesCatalog.releases.filter { release ->
      val version = ReleaseVersion.parse(release.version)
      parsedCurrent != null && version != null && version <= parsedCurrent
    }
  }
  val currentRelease = releases.firstOrNull { ReleaseVersion.parse(it.version) == parsedCurrent }
  val defaultOpen = currentRelease ?: releases.firstOrNull()
  var openedVersions by rememberSaveable(
    currentVersion,
    stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
  ) { mutableStateOf(listOfNotNull(defaultOpen?.version)) }
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
          color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
          scrollBehavior = behavior,
          navigationIcon = {
            IconButton(onClick = onBack, minHeight = 48.dp, minWidth = 48.dp,
              modifier = Modifier.testTag("release-notes.back")) {
              Icon(MiuixIcons.Glass.Back, stringResource(R.string.pack_back))
            }
          },
        )
      }
    },
    floatingActionButton = {
      if (onContinue != null) {
        PageActionFab(
          label = stringResource(R.string.release_notes_ui_continue),
          icon = MiuixIcons.Glass.ChevronForward,
          onClick = onContinue,
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
      Column(
        Modifier.widthIn(max = 840.dp).fillMaxSize()
          .testTag("release-notes.content")
          .padding(
            top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding(),
          )
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
          .nestedScroll(behavior.nestedScrollConnection)
          .verticalScroll(scroll)
          .padding(
            start = 16.dp, end = 16.dp,
            top = 12.dp, bottom = actionClearance + 12.dp,
          ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetadataPill(
              stringResource(R.string.release_notes_ui_current_version, currentVersion.substringBefore('+')),
              MiuixTheme.colorScheme.primary,
            )
            if (currentRelease == null) {
              MetadataPill(stringResource(R.string.release_notes_ui_pending_badge),
                MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            ReleaseNotesCatalog.releases.lastOrNull()?.let { first ->
              MetadataPill(stringResource(R.string.release_notes_ui_first_public, first.version),
                MiuixTheme.colorScheme.onSurfaceSecondary)
            }
          }
          if (currentRelease == null) {
            Text(stringResource(R.string.release_notes_ui_pending),
              style = MiuixTheme.textStyles.body2,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
          }
        }
        releases.forEach { release ->
          key(release.version) {
            ReleaseNotesCard(
              release = release,
              expanded = release.version in openedVersions,
              highlighted = release.version in highlightedVersions,
              onToggle = {
                openedVersions = if (release.version in openedVersions) {
                  openedVersions - release.version
                } else openedVersions + release.version
              },
            )
          }
        }
        if (releases.isEmpty()) {
          Text(stringResource(R.string.release_notes_ui_empty),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("release-notes.end"))
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
) {
  val locale = LocalConfiguration.current.locales[0]
  val date = remember(release.committedAt, locale) {
    runCatching {
      // Keep the target commit's own calendar date; the tag creation date is unrelated.
      OffsetDateTime.parse(release.committedAt).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }.getOrNull()
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
      bottomAction = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)) {
          MetadataPill(date ?: stringResource(R.string.release_notes_ui_date_unavailable),
            MiuixTheme.colorScheme.onSurfaceSecondary,
            Modifier.testTag("release-notes.date.${release.version}"))
          if (highlighted) {
            MetadataPill(stringResource(R.string.release_notes_ui_unread),
              MiuixTheme.colorScheme.primary)
          }
        }
      },
    )
    Os4DisclosureContent(expanded) {
      Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
          .testTag("release-notes.body.${release.version}"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (release.introduction != 0) {
          Text(stringResource(release.introduction), style = MiuixTheme.textStyles.body2)
        }
        stringArrayResource(release.changes).forEachIndexed { index, change ->
          ReleaseNotesChange(index + 1, change)
        }
        if (release.footnote != 0) {
          Text(stringResource(release.footnote), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
      }
    }
  }
}

@Composable
private fun ReleaseNotesChange(number: Int, change: String) {
  val title = change.substringBefore('\n').trim()
  val body = change.substringAfter('\n', "").trim()
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(stringResource(R.string.release_notes_ui_item_number, number),
      style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(title, style = if (body.isEmpty()) MiuixTheme.textStyles.body2
        else MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold))
      if (body.isNotEmpty()) Text(body, style = MiuixTheme.textStyles.body2)
    }
  }
}
