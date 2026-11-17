package os.kei.hyper.home.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import os.kei.hyper.home.R
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun MonetArtworkPreferences(
  enabled: Boolean,
  padding: Boolean,
  editable: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  onPaddingChange: (Boolean) -> Unit,
) {
  SwitchPreference(
    title = stringResource(R.string.monet_unadapted_title),
    summary = stringResource(R.string.monet_unadapted_summary),
    modifier = Modifier.testTag("monet.unadapted"),
    checked = enabled,
    enabled = editable,
    onCheckedChange = onEnabledChange)
  SwitchPreference(
    title = stringResource(R.string.monet_full_bleed_padding_title),
    summary = stringResource(R.string.monet_full_bleed_padding_summary),
    modifier = Modifier.testTag("monet.fullbleed.padding"),
    checked = padding,
    enabled = editable,
    onCheckedChange = onPaddingChange)
}
