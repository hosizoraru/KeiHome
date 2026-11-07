package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppleStorefrontPreference(country: String, enabled: Boolean, search: Boolean = false,
  onCountryChange: (String) -> Unit) {
  val presets = listOf("us", "cn", "jp", "hk", "tw", "gb", "ca", "kr")
  val choices = if (country in presets) presets else presets + country
  val locale = LocalConfiguration.current.locales[0]
  var custom by rememberSaveable { mutableStateOf(false) }
  var code by rememberSaveable(country) { mutableStateOf(country.uppercase(Locale.ROOT)) }
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
      if (custom) code = country.uppercase(Locale.ROOT) else onCountryChange(choices[it])
    },
  )
  if (custom) {
    InputField(query = code, onQueryChange = { value ->
      code = value.filter { it in 'a'..'z' || it in 'A'..'Z' }.take(2).uppercase(Locale.ROOT)
    }, onSearch = { applyCountry() }, expanded = false, onExpandedChange = {},
      modifier = Modifier.padding(horizontal = 16.dp),
      label = stringResource(R.string.apple_region_code))
    if (code.length == 2 && !valid) Text(stringResource(R.string.apple_region_invalid),
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote2,
      color = MiuixTheme.colorScheme.onSurfaceSecondary)
    BasicComponent(title = stringResource(R.string.apple_region_apply),
      summary = appleRegionName(if (valid) code.lowercase(Locale.ROOT) else country, locale), enabled = enabled && valid,
      onClick = { applyCountry() })
  }
}
