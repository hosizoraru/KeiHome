package os.kei.hyper.home.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDisplayStorageTest {
  @Test fun verifiesExistingUiValuesOnAnExplicitUpgradeCheck() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("mmkv.verifyExistingLegacy") == "true")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val original = context.getSharedPreferences("app_display", Context.MODE_PRIVATE).all
    assumeTrue(original.isNotEmpty())
    val store = AppDisplayStorage.open(context)
    for ((key, value) in original) {
      val migrated = when (value) {
        is Boolean -> store.getBoolean(key, !value)
        is Int -> store.getInt(key, value.inv())
        is Long -> store.getLong(key, value.inv())
        is Float -> store.getFloat(key, Float.NaN)
        is String -> store.getString(key, null)
        is Set<*> -> store.getStringSet(key, null)
        else -> error("Unsupported original value")
      }
      assertEquals(key, value, migrated)
    }
    instrumentation.sendStatus(0, android.os.Bundle().apply {
      putInt("mmkv.verifiedLegacyValues", original.size)
    })
  }

  @Test fun migratesTypedValuesOnceAndKeepsSubsequentChoicesAfterReopen() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val name = "mmkv_migration_probe_${UUID.randomUUID()}"
    val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val set = setOf("day", "night", "材料")
    assertTrue(legacy.edit().putBoolean("auto_apply_icons", false).putInt("browser_sort", 3)
      .putLong("large", Long.MAX_VALUE).putFloat("ratio", 1.25f).putString("color_os_style", "pebble")
      .putString("unicode", "灵感").putStringSet("set", set).commit())
    var store: MMKV? = null
    try {
      store = AppDisplayStorage.open(context, name, name)
      assertEquals(false, store.getBoolean("auto_apply_icons", true))
      assertEquals(3, store.getInt("browser_sort", -1))
      assertEquals(Long.MAX_VALUE, store.getLong("large", 0))
      assertEquals(1.25f, store.getFloat("ratio", 0f), 0f)
      assertEquals("pebble", store.getString("color_os_style", null))
      assertEquals("灵感", store.getString("unicode", null))
      assertEquals(set, store.getStringSet("set", null))
      store.encode("color_os_style", "classic")
      store.sync()
      store.close()
      store = null
      assertTrue(legacy.edit().putString("color_os_style", "outdated").commit())
      store = AppDisplayStorage.open(context, name, name)
      assertEquals("classic", store.getString("color_os_style", null))
      assertEquals("outdated", legacy.getString("color_os_style", null))
    } finally {
      store?.close()
      MMKV.removeStorage(name)
      context.deleteSharedPreferences(name)
    }
  }
}
