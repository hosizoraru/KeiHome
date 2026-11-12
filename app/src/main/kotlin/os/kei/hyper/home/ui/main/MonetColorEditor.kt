package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonetCustomization
import os.kei.hyper.home.data.MonetIconRole
import os.kei.hyper.home.data.MonetPaletteFamily
import os.kei.hyper.home.data.MonetSchemes
import os.kei.hyper.home.data.MonetTonalSpec
import os.kei.hyper.home.ui.components.Os4Dialog
import os.kei.hyper.home.ui.components.Os4DialogAction
import os.kei.hyper.home.ui.components.Os4DialogActions
import os.kei.hyper.home.ui.components.Os4DialogButton
import os.kei.hyper.home.ui.components.Os4DisclosureContent
import os.kei.hyper.home.ui.components.Os4DisclosureHeader
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MonetSeedColorField(color: Int, enabled: Boolean, onColorChange: (Int) -> Unit) {
  var show by rememberSaveable { mutableStateOf(false) }
  ColorEditRow(stringResource(R.string.monet_custom_seed), color, enabled = enabled,
    tag = "monet.custom.seed", onClick = { show = true })
  if (show) MonetColorEditor(title = stringResource(R.string.monet_custom_seed), initial = color,
    onDismiss = { show = false }, onConfirm = { value, _ -> onColorChange(value); show = false })
}

@Composable
internal fun MonetAdvancedColors(
  choice: MonetCustomization, seed: Int, palette: Map<String, Int>, enabled: Boolean,
  onChange: (MonetCustomization) -> Unit,
) {
  var familiesExpanded by rememberSaveable { mutableStateOf(false) }
  var iconsExpanded by rememberSaveable { mutableStateOf(false) }
  var editingFamily by rememberSaveable { mutableStateOf<String?>(null) }
  var editingRole by rememberSaveable { mutableStateOf<String?>(null) }
  val defaults = remember(seed, choice.style) { MonetSchemes.familySpecs(seed, choice.style) }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    ColorSectionHeader(stringResource(R.string.monet_custom_palettes), familiesExpanded,
      stringResource(R.string.monet_custom_palette_count, choice.paletteOverrides.size),
      "monet.custom.palettes") { familiesExpanded = !familiesExpanded }
    Os4DisclosureContent(familiesExpanded) {
      Text(stringResource(R.string.monet_custom_palette_hint), Modifier.padding(horizontal = 16.dp),
        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
      MonetPaletteFamily.entries.forEach { family ->
        val spec = choice.paletteOverrides[family] ?: defaults.getValue(family)
        ColorEditRow(familyName(family), spec.sample(), enabled,
          summary = stringResource(if (family in choice.paletteOverrides) R.string.monet_custom_override
            else R.string.monet_custom_inherited),
          tag = "monet.custom.family.${family.name}", onClick = { editingFamily = family.name })
      }
    }
  }
  Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
    ColorSectionHeader(stringResource(R.string.monet_custom_icon_colors), iconsExpanded,
      if (choice.iconOverrides.isNotEmpty()) stringResource(R.string.monet_custom_override)
        else choice.backgroundFamily?.let { familyName(it) } ?: stringResource(R.string.monet_custom_aosp_roles),
      "monet.custom.icons") { iconsExpanded = !iconsExpanded }
    Os4DisclosureContent(iconsExpanded) {
      OverlaySpinnerPreference(
        title = stringResource(R.string.monet_custom_background_family),
        summary = stringResource(R.string.monet_custom_background_hint),
        items = (listOf(stringResource(R.string.monet_custom_aosp_roles)) +
          MonetPaletteFamily.entries.map { familyName(it) }).map { DropdownItem(it) },
        selectedIndex = choice.backgroundFamily?.let { it.ordinal + 1 } ?: 0,
        onSelectedIndexChange = { index ->
          onChange(choice.copy(backgroundFamily = MonetPaletteFamily.entries.getOrNull(index - 1)))
        }, enabled = enabled, modifier = Modifier.testTag("monet.custom.backgroundFamily"))
      MonetIconRole.entries.forEach { role ->
        val color = role.color(choice) { family, tone -> palette["$family$tone"] } ?: return@forEach
        ColorEditRow(roleName(role), color, enabled,
          summary = stringResource(if (role in choice.iconOverrides) R.string.monet_custom_override
            else R.string.monet_custom_inherited),
          tag = "monet.custom.role.${role.name}", onClick = { editingRole = role.name })
      }
    }
  }
  editingFamily?.let(MonetPaletteFamily::valueOf)?.let { family ->
    val spec = choice.paletteOverrides[family] ?: defaults.getValue(family)
    MonetColorEditor(title = familyName(family), initial = spec.sample(), initialSpec = spec,
      onDismiss = { editingFamily = null },
      onReset = {
        onChange(choice.copy(paletteOverrides = choice.paletteOverrides - family))
        editingFamily = null
      },
      onConfirm = { _, selected ->
        onChange(choice.copy(paletteOverrides = choice.paletteOverrides + (family to requireNotNull(selected))))
        editingFamily = null
      })
  }
  editingRole?.let(MonetIconRole::valueOf)?.let { role ->
    val color = role.color(choice) { family, tone -> palette["$family$tone"] } ?: return@let
    MonetColorEditor(title = roleName(role), initial = color, onDismiss = { editingRole = null },
      onReset = {
        onChange(choice.copy(iconOverrides = choice.iconOverrides - role))
        editingRole = null
      },
      onConfirm = { selected, _ ->
        onChange(choice.copy(iconOverrides = choice.iconOverrides + (role to selected)))
        editingRole = null
      })
  }
}

@Composable
private fun ColorSectionHeader(
  title: String, expanded: Boolean, summary: String, tag: String, onClick: () -> Unit,
) {
  val label = stringResource(if (expanded) R.string.polish_details_collapse else R.string.polish_details_expand)
  Os4DisclosureHeader(
    title = title, summary = summary, expanded = expanded,
    actionLabel = label, stateLabel = stringResource(
      if (expanded) R.string.dashboard_expanded else R.string.dashboard_collapsed),
    onToggle = onClick, modifier = Modifier.testTag(tag),
  )
}

@Composable
private fun ColorEditRow(
  title: String, color: Int, enabled: Boolean, tag: String, summary: String? = null, onClick: () -> Unit,
) {
  val hex = colorHex(color)
  BasicComponent(title = title, summary = hex,
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag), endActions = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (summary != null) MetadataPill(summary, MiuixTheme.colorScheme.primary)
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(color))
          .semantics { contentDescription = hex })
      }
    })
}

/** Picker movement stays local; generating 505 tones and IPC happen only after explicit confirmation. */
@Composable
internal fun MonetColorEditor(
  title: String, initial: Int, initialSpec: MonetTonalSpec? = null, onDismiss: () -> Unit,
  onReset: (() -> Unit)? = null, onConfirm: (Int, MonetTonalSpec?) -> Unit,
) {
  var color by rememberSaveable { mutableIntStateOf(initial) }
  val hexField = rememberTextFieldState(colorHex(initial))
  var synchronizedHex by rememberSaveable { mutableStateOf(colorHex(initial)) }
  var hue by rememberSaveable { mutableStateOf(initialSpec?.hue ?: 0.0) }
  var chroma by rememberSaveable { mutableStateOf(initialSpec?.chroma ?: 0.0) }
  var mode by rememberSaveable { mutableIntStateOf(0) }
  val keyboard = LocalSoftwareKeyboardController.current
  var visible by remember { mutableStateOf(true) }
  var afterDismiss by remember { mutableStateOf(onDismiss) }
  val dismiss: (() -> Unit) -> Unit = { action ->
    afterDismiss = action
    keyboard?.hide()
    visible = false
  }
  fun writeHex(selected: Int) {
    synchronizedHex = colorHex(selected)
    hexField.edit { replace(0, length, synchronizedHex); selection = TextRange(length) }
  }
  val updateColor: (Int) -> Unit = { selected ->
    color = selected
    writeHex(selected)
    if (initialSpec != null) MonetTonalSpec.fromColor(selected).let { hue = it.hue; chroma = it.chroma }
  }
  LaunchedEffect(hexField) {
    snapshotFlow { hexField.text.toString() }.collect { input ->
      // Programmatic swatches must not round-trip requested H/C through gamut-mapped RGB.
      if (input != synchronizedHex) {
        synchronizedHex = input
        val digits = input.removePrefix("#")
        if (digits.length == 6) {
          color = digits.toInt(16) or (0xff shl 24)
          if (initialSpec != null) MonetTonalSpec.fromColor(color).let { hue = it.hue; chroma = it.chroma }
        }
      }
    }
  }
  val hex = hexField.text.toString()
  val valid = hex.removePrefix("#").let { it.length == 6 && it.toIntOrNull(16) != null } && color ushr 24 == 255
  Os4Dialog(show = visible, title = title, onDismissRequest = { dismiss(onDismiss) },
    onDismissFinished = { afterDismiss() }) {
    Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.65f).dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverlaySpinnerPreference(title = stringResource(R.string.monet_custom_picker),
          items = listOf(stringResource(R.string.monet_custom_grid), stringResource(R.string.monet_custom_sliders))
            .map { DropdownItem(it) }, selectedIndex = mode, onSelectedIndexChange = { mode = it },
          insideMargin = PaddingValues(0.dp))
        if (mode == 0) ColorPalette(Color(color), { updateColor(it.toArgb()) },
          modifier = Modifier.testTag("monet.editor.palette"))
        else ColorPicker(Color(color), { updateColor(it.toArgb()) },
          modifier = Modifier.testTag("monet.editor.picker"))
        TextField(state = hexField, inputTransformation = InputTransformation {
          val digits = asCharSequence().toString().removePrefix("#")
          if (digits.length > 6 || digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            revertAllChanges()
          }
        }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
          autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
          label = "#RRGGBB", lineLimits = TextFieldLineLimits.SingleLine,
          modifier = Modifier.testTag("monet.editor.hex"))
        if (color ushr 24 != 255) Text(stringResource(R.string.monet_custom_opaque),
          color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
        if (initialSpec != null) {
          Text(stringResource(R.string.monet_custom_hct_hint), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceSecondary)
          SliderPreference(value = hue.toFloat(), onValueChange = {
            hue = it.toDouble(); color = MonetTonalSpec(hue, chroma).sample(); writeHex(color)
          }, title = stringResource(R.string.monet_custom_hue), valueRange = 0f..360f,
            valueText = String.format(Locale.ROOT, "%.0f°", hue), insideMargin = PaddingValues(0.dp),
            modifier = Modifier.testTag("monet.editor.hue"))
          SliderPreference(value = chroma.toFloat(), onValueChange = {
            chroma = it.toDouble(); color = MonetTonalSpec(hue, chroma).sample(); writeHex(color)
          }, title = stringResource(R.string.monet_custom_chroma), valueRange = 0f..200f,
            valueText = String.format(Locale.ROOT, "%.1f", chroma), insideMargin = PaddingValues(0.dp),
            modifier = Modifier.testTag("monet.editor.chroma"))
        }
      }
      onReset?.let { reset -> Os4DialogButton(stringResource(R.string.monet_custom_reset),
        onClick = { dismiss(reset) }, enabled = visible,
        modifier = Modifier.fillMaxWidth().testTag("monet.editor.reset")) }
      Os4DialogActions(listOf(
        Os4DialogAction(stringResource(R.string.monet_custom_cancel), onClick = { dismiss(onDismiss) },
          enabled = visible),
        Os4DialogAction(stringResource(R.string.monet_custom_use), enabled = valid && visible,
          role = GlassDialogButtonRole.Primary,
          onClick = {
            val selected = hexField.text.toString().removePrefix("#").toInt(16) or (0xff shl 24)
            val spec = if (initialSpec == null) null
              else if (hexField.text.toString() != synchronizedHex) MonetTonalSpec.fromColor(selected)
              else MonetTonalSpec(hue, chroma)
            dismiss { onConfirm(selected, spec) }
          },
          tag = "monet.editor.confirm")))
    }
  }
}

@Composable
internal fun familyName(family: MonetPaletteFamily): String = stringResource(when (family) {
  MonetPaletteFamily.PRIMARY -> R.string.monet_family_primary
  MonetPaletteFamily.SECONDARY -> R.string.monet_family_secondary
  MonetPaletteFamily.TERTIARY -> R.string.monet_family_tertiary
  MonetPaletteFamily.NEUTRAL -> R.string.monet_family_neutral
  MonetPaletteFamily.NEUTRAL_VARIANT -> R.string.monet_family_neutral_variant
})

@Composable
internal fun roleName(role: MonetIconRole): String = stringResource(when (role) {
  MonetIconRole.LIGHT_FOREGROUND -> R.string.monet_custom_light_foreground
  MonetIconRole.LIGHT_BACKGROUND -> R.string.monet_custom_light_background
  MonetIconRole.DARK_FOREGROUND -> R.string.monet_custom_dark_foreground
  MonetIconRole.DARK_BACKGROUND -> R.string.monet_custom_dark_background
})

private fun colorHex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xffffff)
