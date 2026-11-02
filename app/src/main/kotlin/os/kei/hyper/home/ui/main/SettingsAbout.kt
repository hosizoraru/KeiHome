package os.kei.hyper.home.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Process
import android.os.Build
import android.os.UserManager
import java.io.File
import java.util.zip.ZipFile
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScreen(model: HomeViewModel, onBack: () -> Unit, onOpenNotices: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(model, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observeAboutIcon() }
  }
  AboutBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val heroHeight = (maxHeight * 0.47f).coerceAtLeast(260.dp)
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
        .navigationBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.fillMaxWidth().height(heroHeight), contentAlignment = Alignment.Center) { AboutHero(model) }
        AboutLinks(showSource = true, showNotices = true, onOpenNotices = onOpenNotices, onBackground = true)
        Spacer(Modifier.height(12.dp))
        AboutDetails()
        Spacer(Modifier.height(32.dp))
      }
      IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(start = 8.dp)) {
        Icon(MiuixIcons.Back, stringResource(R.string.pack_back))
      }
    }
  }
}

@Composable
internal fun AboutHero(model: HomeViewModel) {
  val context = LocalContext.current
  val appName = stringResource(R.string.app_name)
  val version = stringResource(R.string.polish_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
  val copy = stringResource(R.string.polish_copy_version)
  val dark = LocalKeiHomeDarkTheme.current
  val titleColors = if (dark) listOf(Color(0xff85dcea), Color(0xffa9a4ff))
    else listOf(Color(0xff155d7b), Color(0xff5044a6))
  Column(Modifier.fillMaxWidth().clickable(onClickLabel = copy) {
    context.getSystemService(ClipboardManager::class.java)
      .setPrimaryClip(ClipData.newPlainText(appName, "$appName $version\n${BuildConfig.APPLICATION_ID}"))
  }.padding(horizontal = 12.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    AboutAppIcon(model)
    Text(appName, style = MiuixTheme.textStyles.title1.copy(brush = Brush.linearGradient(titleColors),
      fontSize = 36.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
    Text(version, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary,
      textAlign = TextAlign.Center)
    Text(copy, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
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
  val night = isSystemInDarkTheme()
  val bitmap = previews?.let { if (night) it.dark else it.light }
  val mono = previews?.let { if (night) it.darkMonochrome else it.lightMonochrome }
  val colors = remember(monet.snapshot) { previewColors(monet.snapshot) }
  val useMonet = monet.snapshot?.monochrome?.enabled == true && mono != null && colors != null
  if (bitmap != null || useMonet) {
    PreviewIcon(if (useMonet) mono else bitmap,
      if (useMonet) (if (night) colors?.darkForeground else colors?.lightForeground) else null,
      if (useMonet) (if (night) colors?.darkBackground else colors?.lightBackground) else null,
      Modifier.size(96.dp), previews?.backgroundMask)
  } else Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(25.dp)).background(Color(0xfff3f5ef)))
}

@Composable
private fun aboutCardColors() = CardDefaults.defaultColors(
  color = if (LocalKeiHomeDarkTheme.current) Color(0xff283757).copy(alpha = 0.76f) else Color.White.copy(alpha = 0.72f),
  contentColor = MiuixTheme.colorScheme.onSurface,
)

@Composable
internal fun AboutLinks(showSource: Boolean, showNotices: Boolean, onOpenNotices: () -> Unit, onBackground: Boolean = false) {
  val colors = if (onBackground) aboutCardColors() else CardDefaults.defaultColors()
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), colors = colors) {
    if (showSource) BasicComponent(title = stringResource(R.string.settings_source),
      endActions = { AboutDetailValue(stringResource(R.string.settings_source_private)) })
    if (showNotices) BasicComponent(title = stringResource(R.string.settings_notices), onClick = onOpenNotices,
      endActions = { Icon(MiuixIcons.Basic.ArrowRight, null, tint = MiuixTheme.colorScheme.onSurfaceSecondary) })
  }
}

@Composable
private fun AboutDetails() {
  val context = LocalContext.current
  val details by produceState(AboutDeviceDetails(Build.MODEL, null, null, null), context) {
    value = withContext(Dispatchers.IO) {
      val launcher = runCatching { context.packageManager.getPackageInfo("com.miui.home", 0) }.getOrNull()
      AboutDeviceDetails(systemProperty("ro.product.marketname").ifBlank { Build.MODEL },
        launcher?.versionName, systemProperty("rust.runtime_version"),
        launcher?.applicationInfo?.sourceDir?.let(::launcherFlutterVersion))
    }
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp), colors = aboutCardColors()) {
    BasicComponent(title = stringResource(R.string.polish_about_device), endActions = {
      Column(horizontalAlignment = Alignment.End) {
        AboutDetailValue(details.marketName)
        Text(listOf(Build.DEVICE, Build.MODEL).distinct().joinToString(" · "),
          style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.onSurfaceSecondary,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    })
    BasicComponent(title = stringResource(R.string.polish_about_system),
      endActions = {
        Column(horizontalAlignment = Alignment.End) {
          AboutDetailValue(Build.VERSION.INCREMENTAL)
          Text("Android ${Build.VERSION.RELEASE}", style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      })
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
    details.rustVersion?.takeIf(String::isNotBlank)?.let { version -> BasicComponent(
      title = stringResource(R.string.polish_about_rust),
      endActions = { AboutDetailValue(version) }) }
    details.flutterVersion?.takeIf(String::isNotBlank)?.let { version -> BasicComponent(
      title = stringResource(R.string.polish_about_flutter),
      endActions = { AboutDetailValue(version) }) }
  }
}

private data class AboutDeviceDetails(val marketName: String, val launcherVersion: String?,
  val rustVersion: String?, val flutterVersion: String?)

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
