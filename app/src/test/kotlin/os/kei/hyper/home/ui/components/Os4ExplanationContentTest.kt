package os.kei.hyper.home.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Os4ExplanationContentTest {

  @Test fun cjkVisualWeightMatchesLatinEquivalent() {
    val zh = "图标包未覆盖系统应用时，可按应用名称查找相应图标。调整后需更新并应用图标包。"
    val en = "For system apps not covered by the pack, try finding a matching icon by app name. Update and apply the pack after changing."
    val zhUnits = explanationVisualUnits(zh)
    val enUnits = explanationVisualUnits(en)
    assertTrue("Expected zh units ($zhUnits) > raw length (${zh.length})", zhUnits > zh.length)
    assertFalse(shouldUseSheetForExplanation(zh, Os4InfoPresentation.Auto))
    assertFalse(shouldUseSheetForExplanation(en, Os4InfoPresentation.Auto))
    assertTrue("Expected en units ($enUnits) <= 220", enUnits <= 220)
  }

  @Test fun autoPresentationRoutesShortAndMediumTipsToPopupAndLongGuidesToSheet() {
    val shortZh = "图标包没有适配微博轻享版时，使用微博的图标。已设为保留原始图标的应用仍使用原图标。"
    val mediumZh = "为可适配的普通图标生成一张深色图片，减轻桌面启动动画的负担。切到浅色模式后这些图标仍为深色，日历和时钟保留各自的外观切换与动画。更改后需重新生成并应用。"
    val longSingleZh = "系统切换深浅色时换用预先生成的浅色或深色图标，不再为每个图标加载动态配置。打开应用更流畅，切换时保持桌面当前页和已打开的应用。导入后桌面会在后台重新加载一次即生效；之后每次切换，抽屉图标在下次息屏时更新。"
    val multiParagraphZh = "使用已应用到系统的图标方案，遵循自定义图标与保留原图规则。\n\n仅补全未提供图标的纯色系统启动页，不替换应用自带图标。\n\n关闭后下次冷启动恢复系统行为。"
    val compactThreeLinePopup = "ColorOS 灵感\n\n全部应用\n\n预览仅展示已解析结果。"

    assertFalse(shouldUseSheetForExplanation(shortZh, Os4InfoPresentation.Auto))
    assertFalse(shouldUseSheetForExplanation(mediumZh, Os4InfoPresentation.Auto))
    assertFalse(shouldUseSheetForExplanation(compactThreeLinePopup, Os4InfoPresentation.Auto))
    assertTrue(shouldUseSheetForExplanation(longSingleZh, Os4InfoPresentation.Auto))
    assertTrue(shouldUseSheetForExplanation(multiParagraphZh, Os4InfoPresentation.Auto))

    // Explicit overrides always win.
    assertFalse(shouldUseSheetForExplanation(multiParagraphZh, Os4InfoPresentation.Popup))
    assertTrue(shouldUseSheetForExplanation(shortZh, Os4InfoPresentation.Sheet))
  }

  @Test fun shortTwoSentenceTipsStayCohesiveWithoutOverBulleting() {
    val shortZh = "图标包未覆盖系统应用时，可按应用名称查找相应图标。调整后需更新并应用图标包。"
    val items = parseOs4ExplanationItems(shortZh)
    assertEquals(listOf(shortZh), items)
  }

  @Test fun multiSentenceDenseCopySplitsIntoScannablePointsAndProtectsPeriods() {
    val denseEn = "Uses version 18.01.07 rules (e.g. com.miui.home layers). Preserves dynamic clocks and calendars. Reapply the icon pack after changing."
    val items = parseOs4ExplanationItems(denseEn)
    assertEquals(
      listOf(
        "Uses version 18.01.07 rules (e.g. com.miui.home layers).",
        "Preserves dynamic clocks and calendars.",
        "Reapply the icon pack after changing.",
      ),
      items,
    )
  }

  @Test fun ultraShortStubsMergeIntoNeighboringBullet() {
    val withStub = "减小生成包体积，新装应用后可能需更新。默认关闭。为保持离线覆盖完整，原生导入资源不会裁剪，重新生成后即生效。"
    val items = parseOs4ExplanationItems(withStub)
    assertEquals(2, items.size)
    assertTrue(items.first().contains("默认关闭。"))
  }

  @Test fun explicitParagraphsArePreserved() {
    val text = "第一段规则说明。\n\n第二段注意事项。"
    assertEquals(listOf("第一段规则说明。", "第二段注意事项。"), parseOs4ExplanationItems(text))
  }
}
