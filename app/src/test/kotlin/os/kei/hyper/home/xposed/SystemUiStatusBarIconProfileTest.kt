package os.kei.hyper.home.xposed

import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SystemUiStatusBarIconProfileTest {
  private val contract = SystemUiStatusBarIconContract
  private val status = SystemUiStatusBarIconContract.STATUS
  private val pack = MaterialField("LE;->p:LP;", "E", "P", Modifier.PUBLIC)
  private val small = MaterialField("LP;->s:LStatus;", "P", status, Modifier.PUBLIC)
  private val people = small.copy(id = "LP;->a:LStatus;")
  private val icon = MaterialField("LStatus;->i:Landroid/graphics/drawable/Icon;",
    status, "android.graphics.drawable.Icon", Modifier.PUBLIC)
  private val current = MaterialField("LV;->d:LStatus;", "V", status, Modifier.PRIVATE)
  private fun graph(): SystemUiStatusBarIconGraph {
    val consumer = MaterialMethod("LC;->a(LE;Z)LStatus;", "C", "a", status,
      listOf("E", "boolean"), Modifier.PUBLIC,
      setOf(contract.BITMAP, contract.CREATE, contract.SMALL),
      setOf(pack.id, small.id, people.id), setOf(small.id, people.id))
    val drawable = MaterialMethod("LV;->b(LStatus;)Landroid/graphics/drawable/Drawable;",
      "V", "b", "android.graphics.drawable.Drawable", listOf(status), Modifier.PUBLIC,
      setOf(contract.LOAD), setOf(icon.id))
    val update = MaterialMethod("LV;->c(Z)Z", "V", "c", "boolean", listOf("boolean"),
      Modifier.PRIVATE, setOf(drawable.id,
        "Landroid/widget/ImageView;->setImageDrawable(Landroid/graphics/drawable/Drawable;)V"),
      setOf(current.id))
    return SystemUiStatusBarIconGraph(consumer, drawable, update, pack, listOf(small, people), icon, current)
  }

  private fun rejected(g: SystemUiStatusBarIconGraph) {
    assertThrows(IllegalStateException::class.java) { contract.prove(g) }
  }

  @Test fun provesLinkedRolesWithoutRequiringOemMemberNames() {
    contract.prove(graph())
    val g = graph()
    contract.prove(g.copy(consumer = g.consumer.copy(owner = "RenamedManager", name = "renamed")))
  }

  @Test fun requiresBitmapProducerAndBothNativeIconChoices() {
    val g = graph()
    for (call in listOf(contract.BITMAP, contract.CREATE, contract.SMALL)) {
      rejected(g.copy(consumer = g.consumer.copy(calls = g.consumer.calls - call)))
    }
    rejected(g.copy(consumer = g.consumer.copy(parameters = listOf("ForeignEntry", "boolean"))))
    rejected(g.copy(consumer = g.consumer.copy(modifiers = Modifier.STATIC)))
  }

  @Test fun requiresSamePackCacheReadWriteAndRejectsAmbiguousOrImmutableFields() {
    val g = graph()
    rejected(g.copy(cached = emptyList()))
    rejected(g.copy(cached = listOf(small, small)))
    rejected(g.copy(cached = listOf(small.copy(owner = "ForeignPack"))))
    rejected(g.copy(cached = listOf(small.copy(modifiers = Modifier.FINAL))))
    rejected(g.copy(consumer = g.consumer.copy(writes = emptySet())))
    rejected(g.copy(entryPack = pack.copy(type = "ForeignPack")))
  }

  @Test fun requiresNativeUserLoaderAndLinkedRefreshRatherThanAnArbitraryImageViewSetter() {
    val g = graph()
    rejected(g.copy(drawable = g.drawable.copy(calls = emptySet())))
    rejected(g.copy(drawable = g.drawable.copy(reads = emptySet())))
    rejected(g.copy(update = g.update.copy(owner = "AnotherView")))
    rejected(g.copy(update = g.update.copy(calls = g.update.calls - g.drawable.id)))
    rejected(g.copy(update = g.update.copy(reads = emptySet())))
    rejected(g.copy(viewDescriptor = current.copy(type = "android.graphics.drawable.Icon")))
  }

  @Test fun sourceFingerprintTracksDexAndManifestInsteadOfVersionOrTimestamp() {
    val file = File.createTempFile("statusbar-source", ".apk")
    fun write(dex: String, manifest: String = "manifest", resource: String = "resource") {
      ZipOutputStream(file.outputStream()).use { zip ->
        for ((name, value) in mapOf("classes.dex" to dex, "AndroidManifest.xml" to manifest,
            "res/raw/unrelated" to resource)) {
          zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        }
      }
    }
    try {
      write("dex-one")
      val first = SystemUiStatusBarIconProfile.sourceFingerprint(file.path)
      write("dex-one", resource = "changed")
      assertEquals(first, SystemUiStatusBarIconProfile.sourceFingerprint(file.path))
      write("dex-two")
      assertNotEquals(first, SystemUiStatusBarIconProfile.sourceFingerprint(file.path))
      val second = SystemUiStatusBarIconProfile.sourceFingerprint(file.path)
      write("dex-two", manifest = "new-manifest")
      assertNotEquals(second, SystemUiStatusBarIconProfile.sourceFingerprint(file.path))
    } finally { file.delete() }
  }

  @Test fun probeBudgetUsesIdentityAndNeverEvictsLiveOwnersToRetryThem() {
    class EqualOwner { override fun equals(other: Any?) = other is EqualOwner; override fun hashCode() = 0 }
    val budget = SystemUiStatusBarProbeBudget(2)
    val a = EqualOwner(); val b = EqualOwner(); val c = EqualOwner()
    assertTrue(budget.claim(a))
    assertFalse(budget.claim(a))
    assertTrue(budget.claim(b))
    assertFalse(budget.claim(c))
    assertFalse(budget.claim(a))
    budget.clear()
    assertTrue(budget.claim(a))
    assertThrows(IllegalArgumentException::class.java) { SystemUiStatusBarProbeBudget(65) }
  }

  @Test fun cacheOnlyReaderRejectsPackageLookupImageDecodeAndIndirectIoHelpers() {
    val calls = setOf("Landroid/util/SparseArray;->get(I)Ljava/lang/Object;",
      "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
      "Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;")
    val log = "LManager;->log(Ljava/lang/String;)V"
    val logging = setOf("Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I")
    contract.proveCacheReader(calls + log, log, logging)
    for (io in listOf(
      "Landroid/content/pm/PackageManager;->getApplicationIcon(Ljava/lang/String;)Landroid/graphics/drawable/Drawable;",
      "Landroid/graphics/BitmapFactory;->decodeFile(Ljava/lang/String;)Landroid/graphics/Bitmap;",
      "LManager;->loadAppIcon()V",
    )) {
      assertThrows(IllegalStateException::class.java) {
        contract.proveCacheReader(calls + io, log, logging)
      }
      assertThrows(IllegalStateException::class.java) {
        contract.proveCacheReader(calls + log, log, logging + io)
      }
    }
  }

  @Test fun bootstrapRequiresExactNativeBitmapIdentityAndOneUnambiguousValidPackage() {
    val original = Any()
    val samePixelsDifferentObject = Any()
    assertEquals("os.kei", SystemUiStatusBarBitmapIdentity.match(original,
      listOf("os.kei" to original, "os.other" to samePixelsDifferentObject)))
    assertEquals("os.kei", SystemUiStatusBarBitmapIdentity.match(original,
      listOf("os.kei" to original, "os.kei" to original)))
    assertNull(SystemUiStatusBarBitmapIdentity.match(samePixelsDifferentObject, listOf("os.kei" to original)))
    assertNull(SystemUiStatusBarBitmapIdentity.match(original,
      listOf("os.kei" to original, "os.other" to original)))
    assertNull(SystemUiStatusBarBitmapIdentity.match(original, listOf("../outside" to original)))
    assertNull(SystemUiStatusBarBitmapIdentity.match(original, List(1_025) { "os.kei" to original }))
  }
}
