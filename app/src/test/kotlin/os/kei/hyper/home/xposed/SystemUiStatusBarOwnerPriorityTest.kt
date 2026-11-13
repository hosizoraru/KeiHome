package os.kei.hyper.home.xposed

import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test

class SystemUiStatusBarOwnerPriorityTest {
  interface Provider<T> { fun get(): T }
  class Owner
  class Ready<T>(@Volatile @JvmField var instance: Any?) : Provider<T> {
    override fun get(): T = error("provider.get must never run")
  }
  class Component(
    @JvmField val first: Provider<Owner>,
    @JvmField val second: Provider<Owner>,
  )
  class Root(@JvmField val component: Component)

  @Test fun initializedTypedProvidersAgreeWithoutInvokingGet() {
    val owner = Owner()
    val result = SystemUiStatusBarOwnerPriority.inspect(Owner::class.java,
      Root(Component(Ready<Owner>(owner), Ready<Owner>(owner))), emptyList())
    assertTrue(result.matched)
    assertSame(owner, result.owner)
  }

  @Test fun distinctTypedOwnersRejectAndUninitializedProvidersStayUninitialized() {
    val first = Ready<Owner>(Owner())
    val second = Ready<Owner>(Owner())
    assertNull(SystemUiStatusBarOwnerPriority.inspect(Owner::class.java,
      Root(Component(first, second)), emptyList()).owner)
    first.instance = null
    second.instance = null
    val empty = SystemUiStatusBarOwnerPriority.inspect(Owner::class.java,
      Root(Component(first, second)), emptyList())
    assertTrue(empty.matched)
    assertNull(empty.owner)
    assertNull(first.instance)
    assertNull(second.instance)
  }

  @Test fun cancellationNeverAdoptsOwner() {
    assertNull(SystemUiStatusBarOwnerPriority.inspect(Owner::class.java,
      Root(Component(Ready<Owner>(Owner()), Ready<Owner>(Owner()))),
      emptyList(), cancelled = { true }).owner)
  }

  @Test fun lastGenericProviderBypassesWideUnrelatedDaggerChildren() {
    val directory = Files.createTempDirectory("statusbar-wide-provider")
    try {
      val primitiveFields = (0 until 3_000).joinToString("\n") { "public int f$it;" }
      val source = """
        public class Wide {
          public static class Owner {}
          public interface Provider<T> { T get(); }
          public static class Ready implements Provider<Owner> {
            public volatile Object instance;
            public Ready(Object value) { instance = value; }
            public Owner get() { throw new AssertionError("get must not run"); }
          }
          public static class Fanout { $primitiveFields }
          public static class Component {
            public final Fanout first = new Fanout();
            public final Fanout second = new Fanout();
            $primitiveFields
            public final Provider<Owner> zzTarget;
            public Component(Owner owner) { zzTarget = new Ready(owner); }
          }
          public final Component component;
          public Wide(Owner owner) { component = new Component(owner); }
        }
      """.trimIndent()
      val file = directory.resolve("Wide.java")
      Files.writeString(file, source)
      assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        "-d", directory.toString(), file.toString()))
      URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
        val ownerType = loader.loadClass("Wide\$Owner")
        val owner = ownerType.getConstructor().newInstance()
        val root = loader.loadClass("Wide").getConstructor(ownerType).newInstance(owner)
        val component = loader.loadClass("Wide\$Component")
        assertEquals("zzTarget", component.declaredFields.last().name)
        val result = SystemUiStatusBarOwnerPriority.inspect(ownerType, root, emptyList())
        assertTrue(result.matched)
        assertSame(owner, result.owner)
        assertTrue(result.fields < 8_192)
      }
    } finally {
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
      }
    }
  }
}
