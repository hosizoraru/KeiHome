package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import os.kei.hyper.home.R
import os.kei.hyper.home.data.DefaultDataRepository
import os.kei.hyper.home.ui.components.BlurredBar
import os.kei.hyper.home.ui.components.liquid.IosLiquidGlassNavigationBar
import os.kei.hyper.home.ui.components.rememberKeiHomeBlurBackdrop
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MainScreen(
  onItemClick: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  when (val state = uiState) {
    MainScreenUiState.Loading -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator()
      }
    }
    is MainScreenUiState.Success -> {
      MainScreen(data = state.data, modifier = modifier)
    }
    is MainScreenUiState.Error -> {
      Text(
        text = "Error loading data: ${state.throwable.message}",
        color = MiuixTheme.colorScheme.error,
      )
    }
  }
}

@Composable
internal fun MainScreen(data: List<String>, modifier: Modifier = Modifier) {
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }
  val homeLabel = stringResource(R.string.navigation_home)
  val settingsLabel = stringResource(R.string.navigation_settings)
  val navigationItems = remember(homeLabel, settingsLabel) {
    listOf(
      NavigationItem(homeLabel, MiuixIcons.Home),
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
  val topAppBarScrollBehavior = MiuixScrollBehavior()
  val listState = rememberLazyListState()

  Scaffold(
    modifier = modifier,
    topBar = {
      BlurredBar(backdrop, blurActive) {
        TopAppBar(
          title = title,
          color = barColor,
          scrollBehavior = topAppBarScrollBehavior,
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
        item {
          if (selectedTab == 0) {
            HomeContent(data)
          } else {
            SettingsPlaceholder()
          }
        }
      }
    }
  }
}

@Composable
private fun HomeContent(data: List<String>) {
  Card {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
      data.forEach { Greeting(it) }
    }
  }
}

@Composable
private fun SettingsPlaceholder() {
  Card {
    Text(
      text = "KeiHome settings will be added after the target behavior is defined.",
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      style = MiuixTheme.textStyles.body1,
    )
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(
    text = "Hello $name !",
    modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    style = MiuixTheme.textStyles.body1,
  )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
  KeiHomeTheme { MainScreen(listOf("Android")) }
}

