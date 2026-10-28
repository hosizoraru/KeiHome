package os.kei.hyper.home.ui.main

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconCapabilityStatus
import os.kei.hyper.home.data.InstalledIconCapability
import os.kei.hyper.home.data.InstalledIconScanner
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.liquid.IosLiquidGlassNavigationBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  var scanGeneration by rememberSaveable { mutableIntStateOf(0) }
  var statusGeneration by rememberSaveable { mutableIntStateOf(0) }
  var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var capabilityState by remember {
    mutableStateOf<IconCapabilityScanState>(IconCapabilityScanState.Loading)
  }
  val context = LocalContext.current
  LaunchedEffect(context, scanGeneration) {
    capabilityState = IconCapabilityScanState.Loading
    capabilityState = try {
      IconCapabilityScanState.Success(
        withContext(Dispatchers.Default) {
          InstalledIconScanner(context.applicationContext).scan()
        },
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (failure: Exception) {
      IconCapabilityScanState.Error(failure)
    }
  }
  val statusLabel = stringResource(R.string.navigation_status)
  val appsLabel = stringResource(R.string.navigation_apps)
  val settingsLabel = stringResource(R.string.navigation_settings)
  val navigationItems = remember(statusLabel, appsLabel, settingsLabel) {
    listOf(
      NavigationItem(statusLabel, MiuixIcons.Info),
      NavigationItem(appsLabel, MiuixIcons.GridView),
      NavigationItem(settingsLabel, MiuixIcons.Settings),
    )
  }
  val title = if (selectedTab == 0) {
    stringResource(R.string.app_name)
  } else {
    navigationItems[selectedTab].label
  }
  val backdrop = rememberKeiHomeBlurBackdrop()
  val blurActive = backdrop != null
  val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
  val scrollBehaviors = listOf(MiuixScrollBehavior(), MiuixScrollBehavior(), MiuixScrollBehavior())
  val topAppBarScrollBehavior = scrollBehaviors[selectedTab]
  val listStates = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
  val listState = listStates[selectedTab]
  val filterItems = listOf(
    stringResource(R.string.filter_supported),
    stringResource(R.string.filter_night),
    stringResource(R.string.filter_monochrome),
    stringResource(R.string.filter_unknown),
    stringResource(R.string.filter_all),
  )
  val filterEntry = DropdownEntry(
    items = filterItems.mapIndexed { index, label ->
      DropdownItem(
        text = label,
        selected = selectedFilter == index,
        onClick = { selectedFilter = index },
      )
    },
  )

  Scaffold(
    modifier = modifier,
    topBar = {
      BlurredBar(backdrop, blurActive) {
        TopAppBar(
          title = title,
          color = barColor,
          scrollBehavior = topAppBarScrollBehavior,
          actions = {
            if (selectedTab == 1) {
              OverlayIconDropdownMenu(entry = filterEntry) {
                Icon(
                  imageVector = MiuixIcons.Filter,
                  contentDescription = stringResource(R.string.filter_title),
                  tint = MiuixTheme.colorScheme.onBackground,
                )
              }
            }
            if (selectedTab != 2) {
              IconButton(
                onClick = {
                  if (selectedTab == 0) statusGeneration += 1 else scanGeneration += 1
                },
                enabled = selectedTab == 0 || capabilityState !is IconCapabilityScanState.Loading,
              ) {
                Icon(
                  imageVector = MiuixIcons.Refresh,
                  contentDescription = stringResource(R.string.refresh),
                  tint = MiuixTheme.colorScheme.onBackground,
                )
              }
            }
          },
        )
      }
    },
    bottomBar = {
      IosLiquidGlassNavigationBar(
        items = navigationItems,
        selectedIndex = selectedTab,
        onItemClick = { selectedTab = it },
        backdrop = backdrop,
        isBlurActive = blurActive,
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
    ) {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .fillMaxSize()
          .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
          start = 16.dp,
          top = innerPadding.calculateTopPadding() + 4.dp,
          end = 16.dp,
          bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (selectedTab == 0) {
          item(key = "status") {
            StatusContent(
              refreshGeneration = statusGeneration,
              onRefresh = { statusGeneration += 1 },
            )
          }
        } else if (selectedTab == 1) {
          item(key = "search") {
            InputField(
              query = searchQuery,
              onQueryChange = { searchQuery = it },
              onSearch = {},
              expanded = false,
              onExpandedChange = {},
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
              label = stringResource(R.string.search_apps),
            )
          }
          when (val state = capabilityState) {
            IconCapabilityScanState.Loading -> item(key = "loading") {
              ScanLoading()
            }
            is IconCapabilityScanState.Error -> item(key = "error") {
              ScanError(state.throwable)
            }
            is IconCapabilityScanState.Success -> {
              val filterMatches = state.capabilities.filter { it.matchesFilter(selectedFilter) }
              val capabilities = filterMatches.filter { it.matchesQuery(searchQuery) }
              item(key = "count") {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                  Text(
                    text = filterItems[selectedFilter],
                    style = MiuixTheme.textStyles.body1,
                  )
                  Text(
                    text = stringResource(
                      R.string.capability_count,
                      capabilities.size,
                      filterMatches.size,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                  )
                }
              }
              if (capabilities.isEmpty()) {
                item(key = "empty") {
                  Text(
                    text = stringResource(R.string.no_matching_apps),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    style = MiuixTheme.textStyles.body1,
                  )
                }
              }
              items(
                items = capabilities,
                key = { "${it.userId}:${it.packageName}" },
              ) { capability ->
                Card(modifier = Modifier.fillMaxWidth()) {
                  CapabilityRow(capability)
                }
              }
            }
          }
        } else {
          item(key = "settings") { SettingsContent() }
        }
      }
    }
  }
}

@Composable
private fun ScanLoading() {
  Row(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    CircularProgressIndicator()
    Text(stringResource(R.string.scanning_installed_apps), style = MiuixTheme.textStyles.body1)
  }
}
@Composable
private fun ScanError(throwable: Throwable) {
  Text(
    text = stringResource(
      R.string.scan_failed,
      throwable.message ?: stringResource(R.string.unknown_error),
    ),
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    color = MiuixTheme.colorScheme.error,
    style = MiuixTheme.textStyles.body1,
  )
}

internal fun InstalledIconCapability.matchesFilter(filter: Int): Boolean = when (filter) {
  0 -> nightMode == IconCapabilityStatus.DETECTED ||
    monochrome == IconCapabilityStatus.DETECTED
  1 -> nightMode == IconCapabilityStatus.DETECTED
  2 -> monochrome == IconCapabilityStatus.DETECTED
  3 -> nightMode == IconCapabilityStatus.UNKNOWN ||
    monochrome == IconCapabilityStatus.UNKNOWN
  else -> true
}

internal fun InstalledIconCapability.matchesQuery(query: String): Boolean {
  val normalized = query.trim()
  if (normalized.isEmpty()) return true
  return label.contains(normalized, ignoreCase = true) ||
    packageName.contains(normalized, ignoreCase = true)
}

@Composable
private fun CapabilityRow(capability: InstalledIconCapability) {
  Column(
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = capability.label,
        modifier = Modifier.weight(1f),
        style = MiuixTheme.textStyles.body1,
      )
      Text(
        text = if (capability.personalProfile) {
          stringResource(R.string.profile_personal)
        } else {
          stringResource(R.string.profile_user, capability.userId)
        },
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        style = MiuixTheme.textStyles.body2,
      )
    }
    Text(
      text = capability.packageName,
      color = MiuixTheme.colorScheme.onSurfaceSecondary,
      style = MiuixTheme.textStyles.body2,
    )
    Text(
      text = stringResource(
        R.string.capability_night_icon,
        capability.nightMode.displayText(),
      ),
      color = capability.nightMode.displayColor(),
      style = MiuixTheme.textStyles.body2,
    )
    Text(
      text = stringResource(
        R.string.capability_monochrome_icon,
        capability.monochrome.displayText(),
      ),
      color = capability.monochrome.displayColor(),
      style = MiuixTheme.textStyles.body2,
    )
  }
}

@Composable
private fun IconCapabilityStatus.displayText(): String = when (this) {
  IconCapabilityStatus.DETECTED -> stringResource(R.string.status_detected)
  IconCapabilityStatus.NOT_DETECTED -> stringResource(R.string.status_not_detected)
  IconCapabilityStatus.UNKNOWN -> stringResource(R.string.status_needs_verification)
}

@Composable
private fun IconCapabilityStatus.displayColor() = when (this) {
  IconCapabilityStatus.DETECTED -> MiuixTheme.colorScheme.primary
  IconCapabilityStatus.NOT_DETECTED -> MiuixTheme.colorScheme.onSurfaceSecondary
  IconCapabilityStatus.UNKNOWN -> MiuixTheme.colorScheme.error
}

private sealed interface IconCapabilityScanState {
  data object Loading : IconCapabilityScanState
  data class Success(val capabilities: List<InstalledIconCapability>) : IconCapabilityScanState
  data class Error(val throwable: Throwable) : IconCapabilityScanState
}

@Composable
private fun SettingsContent() {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(modifier = Modifier.fillMaxWidth()) {
      LanguagePreference()
    }
  }
}

@Composable
private fun LanguagePreference() {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
  var language by remember(localeManager, configuration) {
    mutableStateOf(AppLanguage.fromTag(localeManager.applicationLocales.toLanguageTags()))
  }
  val labels = listOf(
    stringResource(R.string.language_system),
    stringResource(R.string.language_chinese),
    stringResource(R.string.language_english),
    stringResource(R.string.language_japanese),
  )
  OverlaySpinnerPreference(
    title = stringResource(R.string.settings_language),
    items = labels.map { DropdownItem(text = it) },
    selectedIndex = AppLanguage.entries.indexOf(language),
    onSelectedIndexChange = { index ->
      val selected = AppLanguage.entries[index]
      language = selected
      localeManager.applicationLocales = LocaleList.forLanguageTags(selected.tag)
    },
  )
}
