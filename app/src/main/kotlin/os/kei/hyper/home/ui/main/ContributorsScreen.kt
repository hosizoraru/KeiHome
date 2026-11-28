package os.kei.hyper.home.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4IconBadge
import os.kei.hyper.home.ui.components.Os4InsetDivider
import os.kei.hyper.home.ui.components.keiHomeTopBarScroll
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Edit
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class ContributorHighlight(
  val version: String,
  val badgeRes: Int,
  val badgeTint: Color,
  val titleRes: Int,
  val detailRes: Int,
  val url: String,
  val tag: String,
)

private val JIZIZR_HIGHLIGHTS = listOf(
  ContributorHighlight(
    version = "v1.18.0",
    badgeRes = R.string.about_contributors_jizizr_pr3_badge,
    badgeTint = Os4BadgePalette.Green,
    titleRes = R.string.about_contributors_jizizr_pr3_title,
    detailRes = R.string.about_contributors_jizizr_pr3_detail,
    url = "${ProjectLinks.SOURCE_URL}/pull/3",
    tag = "contributors.jizizr.pr3",
  ),
  ContributorHighlight(
    version = "v1.17.0",
    badgeRes = R.string.about_contributors_jizizr_pr2_badge,
    badgeTint = Os4BadgePalette.Teal,
    titleRes = R.string.about_contributors_jizizr_pr2_title,
    detailRes = R.string.about_contributors_jizizr_pr2_detail,
    url = "${ProjectLinks.SOURCE_URL}/pull/2",
    tag = "contributors.jizizr.pr2",
  ),
  ContributorHighlight(
    version = "v1.16.0",
    badgeRes = R.string.about_contributors_jizizr_pr1_badge,
    badgeTint = Os4BadgePalette.Purple,
    titleRes = R.string.about_contributors_jizizr_pr1_title,
    detailRes = R.string.about_contributors_jizizr_pr1_detail,
    url = "${ProjectLinks.SOURCE_URL}/pull/1",
    tag = "contributors.jizizr.pr1",
  ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContributorsScreen(onBack: () -> Unit) {
  val uriHandler = LocalUriHandler.current
  val backdrop = rememberKeiHomeBlurBackdrop()
  val behavior = MiuixScrollBehavior()
  Scaffold(backdrop = backdrop, modifier = Modifier.testTag("contributors.page"), topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(title = stringResource(R.string.about_contributors_title), scrollBehavior = behavior,
        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("contributors.back")) {
            Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
          }
        })
    }
  }) { insets ->
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
      contentAlignment = Alignment.TopCenter) {
      Column(
        Modifier.widthIn(max = 840.dp).fillMaxSize().keiHomeTopBarScroll(behavior)
          .verticalScroll(rememberScrollState(), overscrollEffect = null)
          .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding() + 8.dp,
            bottom = insets.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Os4IconBadge(
              icon = MiuixIcons.Glass.Ok,
              tint = Os4BadgePalette.Pink,
              size = 40.dp,
              cornerRadius = 12.dp,
              iconSize = 20.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = stringResource(R.string.about_contributors_hero_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = stringResource(R.string.about_contributors_hero_summary),
                style = MiuixTheme.textStyles.body2.copy(lineHeight = 20.sp, lineBreak = LineBreak.Paragraph),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
              )
            }
          }
        }

        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          Column(Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("contributors.jizizr")
                .clickable(
                  role = Role.Button,
                  onClickLabel = stringResource(R.string.about_contributors_open_profile),
                ) { uriHandler.openUri("https://github.com/jizizr") }
                .padding(horizontal = 16.dp, vertical = 14.dp),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Image(
                painter = painterResource(R.drawable.contributor_jizizr),
                contentDescription = null,
                modifier = Modifier
                  .size(52.dp)
                  .clip(CircleShape)
                  .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape),
              )
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                  itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "@jizizr",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                  )
                  MetadataPill("Allen Joe", MiuixTheme.colorScheme.onSurfaceSecondary)
                  MetadataPill(
                    stringResource(R.string.about_contributors_count_badge, JIZIZR_HIGHLIGHTS.size),
                    Os4BadgePalette.Blue,
                  )
                }
                Text(
                  text = stringResource(R.string.about_contributors_jizizr_thanks),
                  style = MiuixTheme.textStyles.body2.copy(lineHeight = 19.sp, lineBreak = LineBreak.Paragraph),
                  color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
              }
              Icon(
                actionIcon(PageActionIcon.OPEN_EXTERNAL),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
              )
            }

            JIZIZR_HIGHLIGHTS.forEach { item ->
              Os4InsetDivider()
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag(item.tag)
                  .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.about_contributors_open_contribution),
                  ) { uriHandler.openUri(item.url) }
                  .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    MetadataPill(item.version, Os4BadgePalette.Blue)
                    MetadataPill(stringResource(item.badgeRes), item.badgeTint)
                  }
                  Icon(
                    actionIcon(PageActionIcon.OPEN_EXTERNAL),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                  )
                }
                Text(
                  text = stringResource(item.titleRes),
                  style = MiuixTheme.textStyles.body2,
                  fontWeight = FontWeight.SemiBold,
                  color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                  text = stringResource(item.detailRes),
                  style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 19.sp, lineBreak = LineBreak.Paragraph),
                  color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
              }
            }
          }
        }

        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("contributors.join")
              .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.about_contributors_join_action),
              ) { uriHandler.openUri("${ProjectLinks.SOURCE_URL}/pulls") }
              .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Os4IconBadge(
              icon = MiuixIcons.Glass.Edit,
              tint = Os4BadgePalette.Blue,
              size = 38.dp,
              cornerRadius = 11.dp,
              iconSize = 19.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(
                text = stringResource(R.string.about_contributors_join_title),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text = stringResource(R.string.about_contributors_join_summary),
                style = MiuixTheme.textStyles.footnote1.copy(lineHeight = 18.sp, lineBreak = LineBreak.Paragraph),
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
              )
            }
            Icon(
              actionIcon(PageActionIcon.OPEN_EXTERNAL),
              contentDescription = null,
              tint = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
          }
        }
      }
    }
  }
}
