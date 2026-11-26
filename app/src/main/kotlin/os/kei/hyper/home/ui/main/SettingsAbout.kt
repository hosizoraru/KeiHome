package os.kei.hyper.home.ui.main

import os.kei.hyper.home.ui.components.Os4TrailingChevron

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Process
import android.os.Build
import android.os.UserManager
import java.io.File
import java.util.zip.ZipFile
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.MainActivity
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.ui.components.about.AboutBackground
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.KeiHomeScaffold as Scaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar as TopAppBar
import os.kei.hyper.home.ui.components.Os4BadgePalette
import os.kei.hyper.home.ui.components.Os4CopyIconButton
import os.kei.hyper.home.ui.components.Os4IconBadge
import os.kei.hyper.home.ui.components.Os4InsetDivider
import os.kei.hyper.home.ui.components.os4ContentSize
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.components.rememberOs4PressSource
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import os.kei.hyper.home.ui.theme.LocalKeiHomeScrollEndHaptic
import androidx.compose.foundation.layout.Row
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.glass.glassPress
import top.yukonga.miuix.kmp.basic.Icon
import os.kei.hyper.home.ui.components.KeiHomeIconButton as IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Log
import top.yukonga.miuix.kmp.icon.glass.Share
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun AboutScreen(model: HomeViewModel, onBack: () -> Unit, onOpenNotices: () -> Unit,
  onOpenContributors: () -> Unit,
  focusEnvironment: Boolean = false) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val aboutScroll = rememberScrollState()
  var heroHeightPx by remember { mutableIntStateOf(0) }
  val scrollProgress: () -> Float = {
    if (heroHeightPx > 0) (aboutScroll.value.toFloat() / heroHeightPx).coerceIn(0f, 1f) else 0f
  }
  val titleVisible by remember {
    derivedStateOf { heroHeightPx > 0 && aboutScroll.value >= heroHeightPx * 0.45f }
  }
  val heroCollapsed by remember {
    derivedStateOf { heroHeightPx > 0 && aboutScroll.value >= heroHeightPx }
  }
  val contentScrolled by remember {
    derivedStateOf { aboutScroll.canScrollBackward }
  }
  val titleAlpha = animateFloatAsState(if (titleVisible) 1f else 0f, tween(200), label = "About title")
  val barBgAlpha = animateFloatAsState(if (heroCollapsed) 1f else 0f, tween(200), label = "About bar bg")
  LaunchedEffect(focusEnvironment) {
    if (focusEnvironment) {
      snapshotFlow { aboutScroll.maxValue }.first { it != Int.MAX_VALUE }
      // The detail card is the final content; scrolling to the bottom keeps every row visible.
      delay(400)
      aboutScroll.animateScrollTo(aboutScroll.maxValue)
      delay(250)
      aboutScroll.animateScrollTo(aboutScroll.maxValue)
    }
  }
  LaunchedEffect(model, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeCurrentIconAppearance() }
  }
  val context = LocalContext.current
  val appName = stringResource(R.string.app_name)
  val version = stringResource(R.string.polish_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
  val copyVersion = {
    context.getSystemService(ClipboardManager::class.java)
      .setPrimaryClip(ClipData.newPlainText(appName, "$appName $version\n${BuildConfig.APPLICATION_ID}"))
  }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val hapticEnabled = LocalKeiHomeScrollEndHaptic.current
  Scaffold(containerColor = Color.Transparent, backdrop = backdrop, topBar = {
    BlurredBar(backdrop, backdrop != null) {
      TopAppBar(
        title = appName,
        isContentScrolled = contentScrolled,
        backgroundAlpha = { barBgAlpha.value },
        titleAlpha = { titleAlpha.value },
        expandable = false,
        color = MiuixTheme.colorScheme.surface,
        navigationIcon = { IconButton(onClick = onBack, minHeight = 48.dp, minWidth = 48.dp) {
          Icon(MiuixIcons.Glass.ChevronBackward, stringResource(R.string.pack_back))
        } },
        actions = {
          Os4CopyIconButton(
            onClick = copyVersion,
            contentDescription = stringResource(R.string.polish_copy_version),
            minHeight = 48.dp, minWidth = 48.dp,
            modifier = Modifier.testTag("aboutCopy"),
          )
        },
      )
    }
  }) { insets ->
    BoxWithConstraints(Modifier.fillMaxSize()
      .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
      val heroHeight = (maxHeight * 0.47f).coerceAtLeast(260.dp)
      AboutBackground(alpha = { 1f - scrollProgress() }) {
        Column(
          Modifier.fillMaxSize().testTag("about.scroll")
            .then(if (hapticEnabled) Modifier.scrollEndHaptic() else Modifier)
            .overScrollVertical()
            .verticalScroll(aboutScroll, overscrollEffect = null)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = insets.calculateTopPadding()),
        ) {
          Box(Modifier.fillMaxWidth().height(heroHeight).onSizeChanged { heroHeightPx = it.height },
            contentAlignment = Alignment.Center) { AboutHero(model, scrollProgress = scrollProgress) }
          AboutLinks(showSource = true, showNotices = true, onOpenNotices = onOpenNotices,
            onOpenContributors = onOpenContributors, onBackground = true)
          Spacer(Modifier.height(12.dp))
          AboutDetails()
          Spacer(Modifier.height(if (focusEnvironment) 96.dp else 32.dp))
        }
      }
    }
  }
}

@Composable
internal fun AboutHero(model: HomeViewModel, scrollProgress: () -> Float = { 0f }) {
  val appName = stringResource(R.string.app_name)
  val version = stringResource(R.string.polish_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
  val dark = LocalKeiHomeDarkTheme.current
  val titleColors = if (dark) listOf(Color(0xff85dcea), Color(0xffa9a4ff))
    else listOf(Color(0xff155d7b), Color(0xff5044a6))
  // Stagger hero fade-out in draw phase so scrolling never recomposes the hero subtree.
  fun Modifier.heroFade(start: Float, span: Float = 0.28f): Modifier = graphicsLayer {
    val p = ((scrollProgress() - start) / span).coerceIn(0f, 1f)
    alpha = 1f - p
    scaleX = 1f - p * 0.05f
    scaleY = 1f - p * 0.05f
  }
  Column(Modifier.fillMaxWidth().testTag("about.hero").padding(horizontal = 12.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.heroFade(0.32f)) { AboutAppIcon(model) }
    Text(appName, modifier = Modifier.heroFade(0.18f),
      style = MiuixTheme.textStyles.title1.copy(brush = Brush.linearGradient(titleColors),
        fontSize = 36.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
    Text(
      version,
      modifier = Modifier.heroFade(0.05f)
        .squircleBackground(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f), 12.dp)
        .padding(horizontal = 12.dp, vertical = 5.dp),
      style = MiuixTheme.textStyles.footnote1,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun AboutAppIcon(model: HomeViewModel) {
  val context = LocalContext.current
  val source by model.browserSource.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current.toString()
  val previews by produceState<AppIconPreviews?>(null, model, source?.revision, configuration) {
    val active = source
    val entry = active?.entries?.get(MainActivity::class.java.name) ?: active?.entries?.get(context.packageName)
    val themed = try {
      if (entry != null && active != null) model.currentPreviews.load(active, entry) else null
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { null }
    value = themed?.takeIf { it.light != null || it.dark != null || it.lightMonochrome != null || it.darkMonochrome != null } ?: run {
      val serial = context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(Process.myUserHandle()) ?: -1L
      model.previews.load(InstalledIconCapability(context.packageName, context.packageName, serial, true,
        IconCapabilityStatus.UNKNOWN, IconCapabilityStatus.UNKNOWN, versionCode = BuildConfig.VERSION_CODE.toLong()), 256)
    }
  }
  val colors = remember(monet.snapshot) { previewColors(monet.snapshot) }
  CurrentAppearanceIcon(previews, monet.snapshot?.monochrome?.enabled == true, colors, Modifier.size(96.dp)) {
    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
      modifier = Modifier.size(96.dp).squircleClip(25.dp)
        .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background)))
  }
}

@Composable
internal fun AboutLinks(showSource: Boolean, showNotices: Boolean, onOpenNotices: () -> Unit,
  onBackground: Boolean = false, showChannel: Boolean = true, onOpenContributors: (() -> Unit)? = null) {
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val links: @Composable ColumnScope.() -> Unit = {
    var hasPrevious = false
    if (showSource) {
      val sourcePress = rememberOs4PressSource()
      BasicComponent(
        title = stringResource(R.string.settings_source),
        onClick = { uriHandler.openUri(ProjectLinks.SOURCE_URL) },
        onClickLabel = stringResource(R.string.settings_source_open),
        interactionSource = sourcePress,
        modifier = Modifier.glassPress(sourcePress),
        startAction = { Os4IconBadge(MiuixIcons.Glass.Link, Os4BadgePalette.Indigo, size = 34.dp, cornerRadius = 10.dp, iconSize = 18.dp) },
        endActions = {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub", style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Icon(actionIcon(PageActionIcon.OPEN_EXTERNAL),
              stringResource(R.string.settings_source_open),
              modifier = Modifier.size(16.dp),
              tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
          }
        },
      )
      hasPrevious = true
    }
    if (showChannel) {
      if (hasPrevious) Os4InsetDivider(start = 64.dp)
      val channelPress = rememberOs4PressSource()
      BasicComponent(
        title = stringResource(R.string.update_channel),
        interactionSource = channelPress,
        modifier = Modifier.glassPress(channelPress),
        startAction = { Os4IconBadge(MiuixIcons.Glass.Share, Os4BadgePalette.Blue, size = 34.dp, cornerRadius = 10.dp, iconSize = 18.dp) },
        onClick = {
          if (!UpdateChannel.open(context))
            android.widget.Toast.makeText(context, R.string.update_channel_failed, android.widget.Toast.LENGTH_LONG).show()
        },
        endActions = {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
              stringResource(R.string.update_channel_summary),
              style = MiuixTheme.textStyles.footnote1,
              color = MiuixTheme.colorScheme.onSurfaceSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Icon(actionIcon(PageActionIcon.OPEN_EXTERNAL), null,
              modifier = Modifier.size(16.dp),
              tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
          }
        },
      )
      hasPrevious = true
    }
    if (showNotices) {
      if (hasPrevious) Os4InsetDivider(start = 64.dp)
      val noticesPress = rememberOs4PressSource()
      BasicComponent(
        title = stringResource(R.string.settings_notices),
        onClick = onOpenNotices,
        interactionSource = noticesPress,
        modifier = Modifier.glassPress(noticesPress).testTag("about.notices"),
        startAction = { Os4IconBadge(MiuixIcons.Glass.Log, Os4BadgePalette.Teal, size = 34.dp, cornerRadius = 10.dp, iconSize = 18.dp) },
        endActions = { Os4TrailingChevron() },
      )
      hasPrevious = true
    }
    if (onOpenContributors != null) {
      if (hasPrevious) Os4InsetDivider(start = 64.dp)
      val contribPress = rememberOs4PressSource()
      BasicComponent(
        title = stringResource(R.string.about_contributors_title),
        onClick = onOpenContributors,
        interactionSource = contribPress,
        modifier = Modifier.glassPress(contribPress).testTag("about.contributors"),
        startAction = { Os4IconBadge(MiuixIcons.Glass.Info, Os4BadgePalette.Pink, size = 34.dp, cornerRadius = 10.dp, iconSize = 18.dp) },
        endActions = { Os4TrailingChevron() },
      )
    }
  }
  if (onBackground) GlassCard(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = links)
  else Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), content = links)
}

@Composable
private fun AboutDetails() {
  val context = LocalContext.current
  val details by produceState(AboutDeviceDetails(Build.MODEL, null, null, null), context) {
    value = withContext(Dispatchers.IO) { loadAboutDeviceDetails(context) }
  }
  GlassCard(Modifier.fillMaxWidth().os4ContentSize(), insideMargin = PaddingValues(0.dp)) {
    BasicComponent(title = stringResource(R.string.polish_about_device), endActions = {
      Column(horizontalAlignment = Alignment.End) {
        AboutDetailValue(details.marketName)
        Text(listOf(Build.DEVICE, Build.MODEL).distinct().joinToString(" · "),
          style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    })
    Os4InsetDivider(start = 16.dp)
    BasicComponent(title = stringResource(R.string.polish_about_system),
      endActions = {
        Column(horizontalAlignment = Alignment.End) {
          AboutDetailValue(Build.VERSION.INCREMENTAL)
          Text("Android ${Build.VERSION.RELEASE}", style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      })
    Os4InsetDivider(start = 16.dp)
    BasicComponent(title = stringResource(R.string.polish_about_launcher),
      endActions = {
        val version = details.launcherVersion ?: stringResource(R.string.polish_about_unavailable)
        val split = version.indexOf('-', (version.length / 2).coerceAtLeast(0))
        Column(horizontalAlignment = Alignment.End) {
          AboutDetailValue(if (split > 0) version.substring(0, split) else version)
          if (split > 0) Text(version.substring(split + 1), style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      })
    details.rustVersion?.takeIf(String::isNotBlank)?.let { version ->
      Os4InsetDivider(start = 16.dp)
      BasicComponent(
        title = stringResource(R.string.polish_about_rust),
        endActions = { AboutDetailValue(version) },
      )
    }
    details.flutterVersion?.takeIf(String::isNotBlank)?.let { version ->
      Os4InsetDivider(start = 16.dp)
      BasicComponent(
        title = stringResource(R.string.polish_about_flutter),
        endActions = { AboutDetailValue(version) },
      )
    }
  }
}

internal data class AboutDeviceDetails(val marketName: String, val launcherVersion: String?,
  val rustVersion: String?, val flutterVersion: String?)

internal fun loadAboutDeviceDetails(context: android.content.Context): AboutDeviceDetails {
  val launcher = runCatching { context.packageManager.getPackageInfo("com.miui.home", 0) }.getOrNull()
  return AboutDeviceDetails(systemProperty("ro.product.marketname").ifBlank { Build.MODEL },
    launcher?.versionName, systemProperty("rust.runtime_version"),
    launcher?.applicationInfo?.sourceDir?.let(::launcherFlutterVersion))
}

private fun systemProperty(key: String): String = runCatching {
  val property = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
  (property.invoke(null, key) as? String)?.trim().orEmpty()
}.getOrDefault("")

/** The installed launcher's own build asset, read as data without loading its code. */
private fun launcherFlutterVersion(path: String): String? = runCatching {
  ZipFile(File(path)).use { zip ->
    val entry = zip.getEntry("assets/flutter_assets/assets/debug/flutter-version.txt")
      ?: return@use null
    if (entry.size !in 1..128) return@use null
    val value = zip.getInputStream(entry).use { it.readNBytes(129) }.decodeToString().trim()
    value.takeIf { it.length in 1..80 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]*")) }
  }
}.getOrNull()

@Composable
private fun AboutDetailValue(value: String) {
  Text(value, style = MiuixTheme.textStyles.body2,
    color = MiuixTheme.colorScheme.onSurfaceSecondary,
    maxLines = 1, overflow = TextOverflow.Ellipsis)
}
