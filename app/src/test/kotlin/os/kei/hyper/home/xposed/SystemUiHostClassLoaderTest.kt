package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test

class SystemUiHostClassLoaderTest {
  private fun loader(factory: Class<*>? = null, application: Class<*>? = null) =
    object : ClassLoader(null) {
      override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
        "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory" ->
          factory ?: throw ClassNotFoundException(name)
        "com.android.systemui.SystemUIApplication" ->
          application ?: throw ClassNotFoundException(name)
        else -> throw ClassNotFoundException(name)
      }
    }

  @Test fun hostWithExistingPluginAnchorDoesNotRequireTheOptionalApplicationName() {
    assertTrue(SystemUiThemeCompatibilityRuntime.isHostClassLoader(
      loader(factory = com.android.systemui.shared.plugins.PluginInstance.PluginFactory::class.java),
    ))
  }

  @Test fun missingPluginFactoryCanUseAnActualHostApplication() {
    assertTrue(SystemUiThemeCompatibilityRuntime.isHostClassLoader(
      loader(application = com.android.systemui.SystemUIApplication::class.java),
    ))
  }

  @Test fun aMisnamedClassCannotBeUsedAsTheHostApplication() {
    assertFalse(SystemUiThemeCompatibilityRuntime.isHostClassLoader(loader(application = Any::class.java)))
  }

  @Test fun aLoaderWithoutEitherHostAnchorIsRejected() {
    assertFalse(SystemUiThemeCompatibilityRuntime.isHostClassLoader(loader()))
  }

  @Test fun fatalProbeFailuresAreNotSilentlyTurnedIntoMissingClasses() {
    val fatal = object : VirtualMachineError("test") {}
    val loader = object : ClassLoader(null) {
      override fun loadClass(name: String, resolve: Boolean): Class<*> = throw fatal
    }
    try {
      SystemUiThemeCompatibilityRuntime.isHostClassLoader(loader)
      fail("expected fatal")
    } catch (actual: VirtualMachineError) { assertSame(fatal, actual) }
  }
}
