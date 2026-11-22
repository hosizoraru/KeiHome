package os.kei.hyper.home.ui.main

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test

class MainTabNavigationDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private lateinit var pager: PagerState
  private lateinit var scope: CoroutineScope
  private lateinit var navigation: TabPagerNavigation

  private fun start() {
    rule.mainClock.autoAdvance = true
    rule.setContent {
      pager = rememberPagerState { 5 }
      scope = rememberCoroutineScope()
      navigation = remember { TabPagerNavigation(pager, scope) }
      HorizontalPager(pager, Modifier.fillMaxSize()) { BasicText("Page $it") }
    }
    rule.waitForIdle()
    rule.mainClock.autoAdvance = false
  }

  @After fun restoreClock() { rule.mainClock.autoAdvance = true }

  private fun reset() {
    rule.runOnIdle { navigation.selectPage(0, animate = false) }
    finishAt(0)
  }

  @Test fun selectionStaysStableAcrossClicksGesturesAndDirectRoutes() {
    start()
    distantClickKeepsDestinationSelectedDuringEveryAnimationFrame()
    reset()
    rapidReverseClicksCannotLetCancelledRequestsResetSelection()
    reset()
    repeatedTapOnDestinationDoesNotRestartNavigation()
    reset()
    userGestureInterruptsClickAndSelectionFollowsActualPage()
    reset()
    directSectionRouteCancelsOlderAnimationBeforeOpeningDetail()
  }

  private fun finishAt(index: Int) {
    rule.mainClock.advanceTimeBy(2000)
    rule.runOnIdle {
      assertEquals(index, navigation.selectedPage)
      assertEquals(index, pager.currentPage)
      assertEquals(0f, pager.currentPageOffsetFraction, 0.0001f)
    }
  }

  private fun distantClickKeepsDestinationSelectedDuringEveryAnimationFrame() {
    rule.runOnIdle { navigation.selectPage(4) }
    repeat(60) {
      rule.mainClock.advanceTimeByFrame()
      rule.runOnIdle { assertEquals(4, navigation.selectedPage) }
    }
    finishAt(4)
  }

  private fun rapidReverseClicksCannotLetCancelledRequestsResetSelection() {
    rule.runOnIdle { navigation.selectPage(4) }
    rule.mainClock.advanceTimeBy(48)
    rule.runOnIdle { navigation.selectPage(0) }
    rule.mainClock.advanceTimeBy(32)
    rule.runOnIdle { navigation.selectPage(3) }
    repeat(60) {
      rule.mainClock.advanceTimeByFrame()
      rule.runOnIdle { assertEquals(3, navigation.selectedPage) }
    }
    finishAt(3)
  }

  private fun repeatedTapOnDestinationDoesNotRestartNavigation() {
    var arrivals = 0
    rule.runOnIdle { navigation.selectPage(4) { arrivals++ } }
    repeat(5) {
      rule.mainClock.advanceTimeBy(32)
      rule.runOnIdle { navigation.selectPage(4) { arrivals += 100 } }
    }
    finishAt(4)
    rule.runOnIdle { assertEquals(1, arrivals) }
  }

  private fun userGestureInterruptsClickAndSelectionFollowsActualPage() {
    rule.runOnIdle { navigation.selectPage(4) }
    rule.mainClock.advanceTimeBy(32)
    rule.runOnIdle {
      scope.launch {
        pager.scroll(MutatePriority.UserInput) { scrollBy(-pager.layoutInfo.pageSize.toFloat()) }
      }
    }
    rule.mainClock.advanceTimeBy(2000)
    rule.runOnIdle {
      assertTrue(pager.currentPage < 4)
      assertEquals(pager.currentPage, navigation.selectedPage)
    }
    rule.runOnIdle { navigation.selectPage(2) }
    finishAt(2)
  }

  private fun directSectionRouteCancelsOlderAnimationBeforeOpeningDetail() {
    var pageAtArrival = -1
    rule.runOnIdle { navigation.selectPage(4) }
    rule.mainClock.advanceTimeBy(32)
    rule.runOnIdle { navigation.selectPage(2, animate = false) { pageAtArrival = pager.currentPage } }
    finishAt(2)
    rule.runOnIdle { assertEquals(2, pageAtArrival) }
  }
}
