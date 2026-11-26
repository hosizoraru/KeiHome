package os.kei.hyper.home.ui.apple

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.util.Locale
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.Os4AnimatedVisibility
import os.kei.hyper.home.ui.components.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppleStorefrontPreference(country: String, enabled: Boolean, search: Boolean = false,
  onCountryChange: (String) -> Unit) {
  val presets = listOf("us", "cn", "jp", "hk", "tw", "gb", "ca", "kr")
  val choices = if (country in presets) presets else presets + country
  val locale = LocalConfiguration.current.locales[0]
  var custom by rememberSaveable { mutableStateOf(false) }
  val codeState = rememberTextFieldState(country.uppercase(Locale.ROOT))
  LaunchedEffect(country) {
    if (!custom) codeState.setTextAndPlaceCursorAtEnd(country.uppercase(Locale.ROOT))
  }
  val code = codeState.text.toString().uppercase(Locale.ROOT)
  val focus = LocalFocusManager.current
  val valid = code in Locale.getISOCountries()
  fun applyCountry() {
    if (valid && enabled) {
      focus.clearFocus()
      onCountryChange(code.lowercase(Locale.ROOT))
      custom = false
    }
  }
  OverlaySpinnerPreference(
    title = stringResource(if (search) R.string.apple_search_region else R.string.apple_region),
    summary = if (search) stringResource(R.string.apple_search_region_note) else null,
    enabled = enabled,
    items = (choices.map { appleRegionName(it, locale) } + stringResource(R.string.apple_region_custom))
      .map { DropdownItem(text = it) },
    selectedIndex = if (custom) choices.size else choices.indexOf(country),
    onSelectedIndexChange = {
      custom = it == choices.size
      if (custom) codeState.setTextAndPlaceCursorAtEnd(country.uppercase(Locale.ROOT))
      else onCountryChange(choices[it])
    },
  )
  Os4AnimatedVisibility(custom) {
    Column(Modifier.fillMaxWidth()) {
      GlassTextField(
        state = codeState,
        label = stringResource(R.string.apple_region_code),
        enabled = enabled,
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = InputTransformation {
          val filtered = asCharSequence().filter { it in 'a'..'z' || it in 'A'..'Z' }.take(2).toString()
            .uppercase(Locale.ROOT)
          if (filtered != asCharSequence().toString()) replace(0, length, filtered)
        },
        keyboardOptions = KeyboardOptions(
          capitalization = KeyboardCapitalization.Characters,
          autoCorrectEnabled = false,
          imeAction = ImeAction.Done,
        ),
        onKeyboardAction = { applyCountry() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      )
      Os4AnimatedVisibility(code.length == 2 && !valid) {
        Text(stringResource(R.string.apple_region_invalid),
          Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote2,
          color = MiuixTheme.colorScheme.error)
      }
      BasicComponent(title = stringResource(R.string.apple_region_apply),
        summary = appleRegionName(if (valid) code.lowercase(Locale.ROOT) else country, locale), enabled = enabled && valid,
        onClick = { applyCountry() })
    }
  }
}
