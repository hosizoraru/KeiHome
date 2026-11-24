package os.kei.hyper.home.xposed

import android.app.Notification
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.os.Process
import android.service.notification.StatusBarNotification
import android.util.SparseArray
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SystemUiStatusBarIconRuntimeDeviceTest {
  class Descriptor(@JvmField val icon: Icon) {
    @JvmField val pkg = "os.kei"
    @JvmField val user = Process.myUserHandle()
  }
  class NativeCache {
    val entries = HashMap<String, Bitmap>()
    @JvmField val originals = SparseArray<Map<String, WeakReference<Bitmap>>>()
    var reads = 0
    fun cached(name: String, user: Int, quiet: Boolean): Bitmap? {
      reads++
      return entries[name].takeIf { user == 0 && quiet }
    }
  }
  class Registry {
    companion object {
      @JvmField val dependencies = HashMap<String, Any>()
      @JvmStatic fun lookup(type: Class<*>): Any? = type.takeIf { false }
    }
  }
  class Pack {
    @JvmField var small: Descriptor? = null
    @JvmField var people: Descriptor? = null
    @JvmField var statusBar: View? = null
  }
  class Entry(@JvmField val pack: Pack = Pack()) {
    @JvmField var notification: StatusBarNotification? = null
    var sensitive = false
    @JvmField var importantConversation = false
  }
  class Collection { @JvmField val entries = HashMap<String, Entry>() }
  interface PipelineRole
  class Pipeline : PipelineRole { @JvmField val collection = Collection() }
  class Row(context: Context, @JvmField var entry: Entry?) : FrameLayout(context) {
    fun getEntry(): Entry? = error("bootstrap must only read the existing backing field")
  }
  class ManagerHolder(@JvmField val manager: Manager)
  class ReadyProvider(@Volatile @JvmField var instance: Any?) {
    var gets = 0
    fun get(): Any { gets++; error("bootstrap must not initialize a provider") }
  }
  class Component(@JvmField val provider: ReadyProvider)
  class ApplicationHolder(@JvmField val component: Component)
  class Manager {
    fun important(entry: Entry): Boolean = entry.importantConversation
    @JvmField val pipeline: PipelineRole = Pipeline()
    @JvmField val listeners = LinkedHashSet<StoreListener>()
    var computes = 0
    var updates = 0
    var nativeConsume: ((Entry, Boolean) -> Descriptor?)? = null
    var makePeople: ((Entry) -> Descriptor?)? = null
    var make: (Entry) -> Descriptor = { error("missing native fixture") }
    fun descriptor(entry: Entry, people: Boolean): Descriptor? {
      if (people) return entry.pack.people ?: makePeople?.invoke(entry)?.also { entry.pack.people = it }
      entry.pack.small?.let { return it }
      computes++
      return make(entry).also { entry.pack.small = it }
    }
    fun updateSafe(entry: Entry) {
      updates++
      entry.pack.small = null
      entry.pack.people = null
      val small = nativeConsume?.invoke(entry, false) ?: descriptor(entry, false)
      val selected = if (entry.sensitive) nativeConsume?.invoke(entry, true) else small
      entry.pack.statusBar?.let {
        it.notification = entry.notification; it.selected = selected; it.refresh(false)
      }
      listeners.forEach { it.update(entry, selected) }
    }
  }
  class Store(@JvmField val manager: Manager, @JvmField val pipeline: PipelineRole) {
    @JvmField var collection = (pipeline as Pipeline).collection
    @JvmField val views = HashMap<String, View>()
  }
  class StoreListener(@JvmField val store: Store) {
    fun update(entry: Entry, selected: Descriptor?) {
      store.views[entry.notification?.key]?.let {
        it.notification = entry.notification; it.selected = selected; it.refresh(false)
      }
    }
  }
  class View(context: Context) : ImageView(context) {
    @JvmField var notification: StatusBarNotification? = null
    @JvmField var selected: Descriptor? = null
    var refreshes = 0
    var bridge: ((View, Descriptor, () -> Any?) -> Any?)? = null
    var nativeLoaded: ((Icon, Any?) -> Any?)? = null
    fun icon(value: Descriptor): Drawable? {
      val native = value.icon.loadDrawable(context)
      return (nativeLoaded?.invoke(value.icon, native) ?: native) as? Drawable
    }
    fun refresh(force: Boolean): Boolean {
      val value = selected ?: return false
      refreshes++
      val load = { icon(value) as Any? }
      setImageDrawable((bridge?.invoke(this, value, load) ?: load()) as? Drawable)
      return !force
    }
  }
  private class Fixture(entryBootstrap: Boolean = false) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = Manager()
    val lifecycle = ThemeCompatibilityLifecycle()
    val bitmaps = ArrayList<Bitmap>()
    val failures = ArrayList<Throwable>()
    val cache = HashMap<String, Bitmap>()
    val missingSnapshots = HashSet<String>()
    val nativeCache = NativeCache()
    var nativeProducerCalls = 0
    var onReady: () -> Unit = {}
    var lookups = 0
    var onBootstrap: (String) -> Unit = {}
    val bootstrapLogs = ArrayList<String>()
    var runtime: SystemUiStatusBarIconRuntime
    val profile: SystemUiStatusBarIconProfile
    val entryProfile: SystemUiStatusBarEntryProfile
    init {
      fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
      Registry.dependencies.clear()
      entryProfile = SystemUiStatusBarEntryProfile(
        listOf(field(Registry::class.java, "dependencies")), emptyList(),
        listOf(listOf(field(Row::class.java, "entry"))),
        listOf(field(Pack::class.java, "statusBar")),
        Manager::class.java.getDeclaredMethod("updateSafe", Entry::class.java),
        field(View::class.java, "notification"), field(Entry::class.java, "notification"),
        Manager::class.java.getDeclaredMethod("important", Entry::class.java),
        listOf(listOf(field(Manager::class.java, "pipeline"), field(Pipeline::class.java, "collection"),
          field(Collection::class.java, "entries"))),
        listOf(SystemUiStatusBarStoreProfile(field(Manager::class.java, "listeners"),
          field(StoreListener::class.java, "store"), field(Store::class.java, "manager"),
          field(Store::class.java, "collection"), field(Store::class.java, "views"))),
      )
      profile = SystemUiStatusBarIconProfile(
        Manager::class.java.getDeclaredMethod("descriptor", Entry::class.java, Boolean::class.javaPrimitiveType),
        View::class.java.getDeclaredMethod("icon", Descriptor::class.java),
        View::class.java.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType),
        field(Entry::class.java, "pack"),
        listOf(field(Pack::class.java, "small"), field(Pack::class.java, "people")),
        field(Descriptor::class.java, "icon"), field(View::class.java, "selected"),
        Icon::class.java.getDeclaredMethod("getBitmap").apply { isAccessible = true },
        nativeManager = Registry::class.java.getDeclaredMethod("lookup", Class::class.java),
        nativeCaches = listOf(field(NativeCache::class.java, "originals")),
        cacheReader = NativeCache::class.java.getDeclaredMethod("cached", String::class.java,
          Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
        descriptorPackage = field(Descriptor::class.java, "pkg"),
        descriptorUser = field(Descriptor::class.java, "user"),
        bootstrap = entryProfile.takeIf { entryBootstrap },
      )
      if (entryBootstrap) entryProfile.validate(profile)
      runtime = SystemUiStatusBarIconRuntime(context, profile, lifecycle, 0,
        { lookups++; cache[it] }, { failures += it }, nativeManagerInstance = nativeCache,
        ready = { onReady() }, onBootstrap = {
          synchronized(bootstrapLogs) { bootstrapLogs += it }
          onBootstrap(it)
        }, snapshotMissing = { it in missingSnapshots })
      manager.nativeConsume = { entry, people -> descriptor(entry, people) }
    }
    fun bitmap(color: Int = Color.BLUE, clipped: Boolean = false, mutable: Boolean = false): Bitmap {
      val raw = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
      raw.eraseColor(color)
      if (clipped) raw.setPixel(0, 0, Color.TRANSPARENT)
      val result = if (mutable) raw else raw.copy(raw.config!!, false).also { raw.recycle() }
      bitmaps += result
      return result
    }
    fun icon(bitmap: Bitmap): Icon =
      Icon.createWithBitmap(bitmap).also { runtime.created(bitmap, it) }
    fun app(bitmap: Bitmap, name: String = "os.kei", user: Int = 0): Descriptor {
      runtime.probeProducer(user, name)?.let { held ->
        return held.bitmap?.let { Descriptor(icon(it)) }
          ?: Descriptor(Icon.createWithResource("android", android.R.drawable.sym_def_app_icon))
      }
      nativeProducerCalls++
      runtime.produced(user, name, bitmap)
      return Descriptor(icon(bitmap))
    }
    fun descriptor(entry: Entry, people: Boolean = false): Descriptor? =
      runtime.descriptor(manager, entry, people) { manager.descriptor(entry, people) } as? Descriptor
    fun view(value: Descriptor): View = View(context).also { view ->
      view.selected = value
      view.nativeLoaded = runtime::loaded
      view.bridge = runtime::drawable
      view.refresh(false)
    }
    fun nextGeneration(view: View) {
      runtime.requestStop()
      runtime = SystemUiStatusBarIconRuntime(context, profile, ThemeCompatibilityLifecycle(), 0,
        { lookups++; cache[it] }, { failures += it }, ready = { onReady() },
        onBootstrap = {
          synchronized(bootstrapLogs) { bootstrapLogs += it }
          onBootstrap(it)
        })
      view.nativeLoaded = runtime::loaded
      view.bridge = runtime::drawable
    }
    fun close() {
      runtime.requestStop()
      Registry.dependencies.clear()
      bitmaps.forEach { it.recycle() }
    }
  }
  private fun fixture(entryBootstrap: Boolean = false, block: Fixture.() -> Unit) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val fixture = Fixture(entryBootstrap)
      try { fixture.block(); assertTrue(fixture.failures.toString(), fixture.failures.isEmpty()) }
      finally { fixture.close() }
    }
  }

  @Test fun proofTagContainsOnlyLoaderNeutralDataAndSurvivesRetirement() = fixture {
    val original = bitmap()
    manager.make = { app(original) }
    val selected = requireNotNull(descriptor(Entry()))
    val view = view(selected)
    val tag = view.getTag(SystemUiStatusBarIconProof.KEY) as Array<*>
    assertSame(arrayOfNulls<Any>(0).javaClass, tag.javaClass)
    assertTrue(tag[0] is String)
    assertEquals("os.kei", tag[1])
    assertEquals(0, tag[2])
    assertSame(WeakReference::class.java, tag[3]!!.javaClass)
    assertSame(selected.icon, (tag[3] as WeakReference<*>).get())
    runtime.requestStop()
    assertSame(tag, view.getTag(SystemUiStatusBarIconProof.KEY))
    assertEquals("os.kei", SystemUiStatusBarIconProof.read(view, selected.icon, 0))
    assertNull(SystemUiStatusBarIconProof.read(view, selected.icon, 10))
    assertNull(SystemUiStatusBarIconProof.read(view, Icon.createWithBitmap(original), 0))
    view.selected = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    runtime.replay()
    assertEquals(1, view.refreshes)
  }

  @Test fun proofTagCollisionAndUnauthenticatedAvatarAreNeverOverwritten() = fixture {
    val avatar = view(Descriptor(Icon.createWithBitmap(bitmap(Color.RED))))
    assertNull(avatar.getTag(SystemUiStatusBarIconProof.KEY))
    manager.make = { app(bitmap()) }
    val selected = requireNotNull(descriptor(Entry()))
    val view = View(context).apply { this.selected = selected; bridge = runtime::drawable }
    val foreign = Any()
    view.setTag(SystemUiStatusBarIconProof.KEY, foreign)
    view.refresh(false)
    assertSame(foreign, view.getTag(SystemUiStatusBarIconProof.KEY))
    assertNull(SystemUiStatusBarIconProof.read(view, selected.icon, 0))
    val owned = this.view(selected)
    manager.make = { app(bitmap(Color.BLUE)) }
    val replacement = requireNotNull(descriptor(Entry()))
    owned.selected = replacement
    owned.refresh(false)
    assertEquals("os.kei", SystemUiStatusBarIconProof.read(owned, replacement.icon, 0))
    assertNull(SystemUiStatusBarIconProof.read(owned, selected.icon, 0))
  }

  @Test fun secondGenerationRestoresExistingIconWithoutManagerCacheOrNotificationAndReplaysLaterReady() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val restored = CountDownLatch(1)
    lateinit var f: Fixture
    lateinit var view: View
    var descriptorCalls = 0
    instrumentation.runOnMainSync {
      f = Fixture()
      f.manager.make = { f.app(f.bitmap()) }
      view = f.view(requireNotNull(f.descriptor(Entry())))
      descriptorCalls = f.manager.computes
      f.nextGeneration(view)
      f.onBootstrap = { if (it == "Status-bar bootstrap proof-tags restored=1") restored.countDown() }
      f.runtime.bootstrap { listOf(view) }
    }
    try {
      assertTrue(restored.await(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync {
        assertEquals(descriptorCalls, f.manager.computes)
        assertEquals(0, f.nativeCache.reads)
        assertEquals(1, view.refreshes)
        f.cache["os.kei"] = f.bitmap(Color.BLACK, clipped = true)
        f.runtime.replay()
        assertSame(f.cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
        assertEquals(2, view.refreshes)
        assertEquals(descriptorCalls, f.manager.computes)
        assertTrue(f.bootstrapLogs.none { it.contains("native-owner=") })
        assertTrue(f.failures.toString(), f.failures.isEmpty())
      }
    } finally { instrumentation.runOnMainSync { f.close() } }
  }

  @Test fun coldNativeBitmapReplaysFinalizedSnapshotThroughNativeRefreshAndPreservesTint() = fixture {
    val raw = bitmap()
    manager.make = { app(raw) }
    val view = view(requireNotNull(descriptor(Entry())))
    val tint = ColorStateList.valueOf(Color.MAGENTA)
    view.imageTintList = tint
    assertEquals(1, view.refreshes)
    val finalized = bitmap(clipped = true)
    cache["os.kei"] = finalized
    runtime.replay()
    assertSame(finalized, (view.drawable as BitmapDrawable).bitmap)
    assertEquals(0, Color.alpha((view.drawable as BitmapDrawable).bitmap.getPixel(0, 0)))
    assertEquals(2, view.refreshes)
    assertSame(tint, view.imageTintList)
    runtime.replay()
    assertEquals(2, view.refreshes)
    cache["os.kei"] = bitmap(Color.BLACK, clipped = true)
    runtime.replay()
    assertEquals(3, view.refreshes)
    assertSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
  }

  @Test fun missingArtworkRestoresNativeDrawableOnlyAfterTheReplacementHasBeenResolved() = fixture {
    val native = bitmap(Color.BLUE)
    manager.make = { app(native) }
    val view = view(requireNotNull(descriptor(Entry())))
    val themed = bitmap(Color.RED)
    cache["os.kei"] = themed
    runtime.replay()
    assertSame(themed, (view.drawable as BitmapDrawable).bitmap)
    cache.clear()
    runtime.replay()
    assertSame(themed, (view.drawable as BitmapDrawable).bitmap)
    assertEquals(2, view.refreshes)

    missingSnapshots += "os.kei"
    runtime.replay()
    assertEquals(Color.BLUE, (view.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    assertEquals(3, view.refreshes)
    runtime.replay()
    assertEquals(3, view.refreshes)
    cache["os.kei"] = bitmap(Color.GREEN)
    runtime.replay()
    assertEquals(Color.GREEN, (view.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    assertEquals(4, view.refreshes)
  }

  @Test fun mutableProducerIconCopyIsAuthenticatedByExactFactoryResultAndReplaysWithoutAnotherCopy() = fixture {
    val raw = bitmap(mutable = true)
    manager.make = { app(raw) }
    val selected = requireNotNull(descriptor(Entry()))
    val payload = Icon::class.java.getDeclaredMethod("getBitmap").apply { isAccessible = true }
      .invoke(selected.icon) as Bitmap
    assertNotSame(raw, payload)
    val view = view(selected)
    val finalized = bitmap(clipped = true, mutable = true)
    cache["os.kei"] = finalized
    runtime.replay()
    assertEquals(2, view.refreshes)
    assertSame(finalized, (view.drawable as BitmapDrawable).bitmap)
    assertEquals(0, Color.alpha(finalized.getPixel(0, 0)))
  }

  @Test fun aDifferentReturnedIconCannotBorrowTheMutableProducerFactoryReceipt() = fixture {
    val raw = bitmap(mutable = true)
    manager.make = {
      runtime.produced(0, "os.kei", raw)
      val intermediate = icon(raw)
      val copy = Icon::class.java.getDeclaredMethod("getBitmap").apply { isAccessible = true }
        .invoke(intermediate) as Bitmap
      Descriptor(icon(copy))
    }
    val view = view(requireNotNull(descriptor(Entry())))
    cache["os.kei"] = bitmap(clipped = true)
    val before = lookups
    runtime.replay()
    assertEquals(before, lookups)
    assertEquals(1, view.refreshes)
  }

  @Test fun maskedLeafIsLoadedBeforeNativeGetIconWrapsAndScalesItsResult() = fixture {
    manager.make = { app(bitmap()) }
    val selected = requireNotNull(descriptor(Entry()))
    val view = view(selected)
    val finalized = bitmap(clipped = true, mutable = true)
    cache["os.kei"] = finalized
    lateinit var wrapper: InsetDrawable
    val result = runtime.drawable(view, selected) {
      wrapper = InsetDrawable(requireNotNull(view.icon(selected)), 2)
      wrapper
    }
    assertSame(wrapper, result)
    assertSame(finalized, (wrapper.drawable as BitmapDrawable).bitmap)
    runtime.replay()
    assertEquals(1, view.refreshes)
  }

  @Test fun userDrawableLeafHookDoesNotClaimAnUnrelatedIconOutsideItsViewScope() = fixture {
    manager.make = { app(bitmap()) }
    val selected = requireNotNull(descriptor(Entry()))
    val view = view(selected)
    cache["os.kei"] = bitmap(clipped = true)
    val unrelated = Icon.createWithBitmap(bitmap(Color.RED))
    val native = unrelated.loadDrawable(context)
    assertSame(native, runtime.loaded(unrelated, native))
    val result = runtime.drawable(view, selected) { runtime.loaded(unrelated, native) }
    assertSame(native, result)
  }

  @Test fun hotReloadProbesAlreadyCachedDescriptorOnceAndAdoptsOnlyExactAppBitmap() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.small = old
    nativeCache.entries["os.kei"] = bitmap(Color.CYAN)
    manager.make = { app(bitmap(Color.CYAN)) }
    val replacement = requireNotNull(descriptor(entry))
    assertNotSame(old, replacement)
    assertSame(replacement, entry.pack.small)
    assertEquals(1, manager.computes)
    assertEquals(0, nativeProducerCalls)
    assertSame(replacement, descriptor(entry))
    assertEquals(1, manager.computes)
    cache["os.kei"] = bitmap(clipped = true)
    val view = view(replacement)
    assertSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
  }

  @Test fun cachedSmallHybridOrAvatarChoiceIsRestoredWithoutRetryOrCacheCorruption() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    val avatar = Descriptor(Icon.createWithBitmap(bitmap(Color.GREEN)))
    entry.pack.small = old
    entry.pack.people = avatar
    nativeCache.entries["os.kei"] = bitmap()
    manager.make = {
      runtime.produced(0, "os.kei", bitmap())
      Descriptor(Icon.createWithBitmap(bitmap(Color.YELLOW)))
    }
    assertSame(old, descriptor(entry))
    assertSame(old, entry.pack.small)
    assertSame(avatar, entry.pack.people)
    assertEquals(1, manager.computes)
    assertSame(old, descriptor(entry))
    assertEquals(1, manager.computes)
    val view = view(old)
    cache["os.kei"] = bitmap(clipped = true)
    val before = lookups
    runtime.replay()
    assertEquals(before, lookups)
    assertEquals(1, view.refreshes)
  }

  @Test fun emptyNativeCacheRecomputesCachedDescriptorFromReadySharedSnapshotWithFactoryWitness() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap(mutable = true)))
    val avatar = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    entry.pack.small = old
    entry.pack.people = avatar
    val finalized = bitmap(clipped = true, mutable = true)
    cache["os.kei"] = finalized
    manager.make = { app(bitmap(Color.CYAN)) }
    val replacement = requireNotNull(descriptor(entry))
    assertNotSame(old, replacement)
    assertSame(replacement, entry.pack.small)
    assertSame(avatar, entry.pack.people)
    assertTrue(nativeCache.entries.isEmpty())
    assertEquals(0, nativeProducerCalls)
    assertEquals(1, manager.computes)
    assertSame(finalized, (view(replacement).drawable as BitmapDrawable).bitmap)
    assertSame(replacement, descriptor(entry))
    assertEquals(1, manager.computes)
  }

  @Test fun coldSharedSnapshotDoesNotSpendProbeAndLaterNativeCallCanAdoptReadySnapshot() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.small = old
    manager.make = { error("both caches cold must not recompute") }
    assertSame(old, descriptor(entry))
    assertEquals(0, manager.computes)
    assertEquals(0, nativeProducerCalls)
    cache["os.kei"] = bitmap(clipped = true)
    manager.make = { app(bitmap()) }
    assertNotSame(old, descriptor(entry))
    assertEquals(1, manager.computes)
    assertEquals(0, nativeProducerCalls)
  }

  @Test fun sharedSnapshotProbeRequiresFactoryWitnessEvenWhenPayloadIdentityMatches() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.small = old
    cache["os.kei"] = bitmap(clipped = true)
    manager.make = {
      val held = requireNotNull(runtime.probeProducer(0, "os.kei")?.bitmap)
      // Deliberately omit this generation's factory receipt.
      Descriptor(Icon.createWithBitmap(held))
    }
    assertSame(old, descriptor(entry))
    assertSame(old, entry.pack.small)
    assertEquals(0, nativeProducerCalls)
    assertSame(old, descriptor(entry))
    assertEquals(1, manager.computes)
  }

  @Test fun sharedSnapshotProbePreservesPeopleHybridAndResourceChoicesWithoutNativeIo() = fixture {
    cache["os.kei"] = bitmap(clipped = true)
    for (mode in 0..3) {
      val entry = Entry()
      val old = Descriptor(Icon.createWithBitmap(bitmap()))
      val avatar = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
      entry.pack.small = old
      entry.pack.people = avatar
      val before = manager.computes
      assertSame(avatar, descriptor(entry, people = true))
      assertEquals(before, manager.computes)
      manager.make = {
        when (mode) {
          0 -> avatar // Native non-app branch: no nested producer.
          1 -> { app(bitmap()); Descriptor(Icon.createWithResource("android", android.R.drawable.sym_def_app_icon)) }
          2 -> app(bitmap(), name = "os.other")
          else -> app(bitmap(), user = 10)
        }
      }
      assertSame(old, descriptor(entry))
      assertSame(old, entry.pack.small)
      assertSame(avatar, entry.pack.people)
      assertEquals(0, nativeProducerCalls)
      assertSame(old, descriptor(entry))
      assertEquals(before + 1, manager.computes)
    }
  }

  @Test fun sharedSnapshotProbeRejectsDifferentFactoryIconAndChangesToOtherNativeCache() = fixture {
    cache["os.kei"] = bitmap(clipped = true)
    for (mutatePeople in listOf(false, true)) {
      val entry = Entry()
      val old = Descriptor(Icon.createWithBitmap(bitmap()))
      val avatar = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
      entry.pack.small = old
      entry.pack.people = avatar
      manager.make = {
        val witnessed = app(bitmap())
        if (mutatePeople) {
          it.pack.people = witnessed
          witnessed
        } else Descriptor(Icon.createWithBitmap(cache.getValue("os.kei")))
      }
      assertSame(old, descriptor(entry))
      assertSame(old, entry.pack.small)
      assertSame(avatar, entry.pack.people)
      assertEquals(0, nativeProducerCalls)
    }
  }

  @Test fun peopleBranchAndResourceSmallIconNeverBecomeAppBindings() = fixture {
    val entry = Entry()
    val avatar = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.people = avatar
    nativeCache.entries["os.kei"] = bitmap()
    assertSame(avatar, descriptor(entry, people = true))
    manager.make = {
      runtime.produced(0, "os.kei", bitmap())
      Descriptor(Icon.createWithResource("android", android.R.drawable.sym_def_app_icon))
    }
    val native = requireNotNull(descriptor(entry))
    val view = view(native)
    val before = lookups
    cache["os.kei"] = bitmap(clipped = true)
    runtime.replay()
    assertEquals(before, lookups)
    assertEquals(1, view.refreshes)
  }

  @Test fun clonedBitmapDifferentUserAndAmbiguousProducersDoNotAuthenticate() = fixture {
    for (mode in 0..2) {
      val raw = bitmap()
      manager.make = {
        when (mode) {
          0 -> { runtime.produced(0, "os.kei", raw); Descriptor(Icon.createWithBitmap(bitmap())) }
          1 -> app(raw, user = 10)
          else -> { runtime.produced(0, "os.kei", raw); app(raw) }
        }
      }
      val view = view(requireNotNull(descriptor(Entry())))
      val before = lookups
      cache["os.kei"] = bitmap(clipped = true)
      runtime.replay()
      assertEquals(before, lookups)
      assertEquals(1, view.refreshes)
    }
  }

  @Test fun nativeRebindAndDescriptorCloneUseIconIdentityWithoutClaimingAnUnrelatedView() = fixture {
    manager.make = { app(bitmap()) }
    val selected = requireNotNull(descriptor(Entry()))
    val view = view(Descriptor(selected.icon))
    cache["os.kei"] = bitmap(clipped = true)
    runtime.replay()
    assertEquals(2, view.refreshes)
    view.selected = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    runtime.replay()
    assertEquals(2, view.refreshes)
    val before = lookups
    runtime.replay()
    assertEquals(before, lookups)
  }

  @Test fun rebindInsideNativeLoadCannotPublishOldSnapshot() = fixture {
    manager.make = { app(bitmap()) }
    val old = requireNotNull(descriptor(Entry()))
    val view = view(old)
    val other = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    cache["os.kei"] = bitmap(clipped = true)
    val sentinel = other.icon.loadDrawable(context)
    val result = runtime.drawable(view, old) { view.selected = other; sentinel }
    assertSame(sentinel, result)
    runtime.replay()
    assertEquals(1, view.refreshes)
  }

  @Test fun stoppedOrRetiringRuntimePreservesNativeAndDoesNotReplay() = fixture {
    manager.make = { app(bitmap()) }
    val value = requireNotNull(descriptor(Entry()))
    val view = view(value)
    cache["os.kei"] = bitmap(clipped = true)
    runtime.requestStop()
    runtime.replay()
    assertEquals(1, view.refreshes)
    val marker = Any()
    assertSame(marker, runtime.descriptor(manager, Entry(), false) { marker })
    assertSame(marker, runtime.drawable(view, value) { marker })
    assertTrue(lifecycle.retire {})
  }

  @Test fun retiredLifecycleRejectsOptionalWorkWithoutChangingNativeResults() = fixture {
    assertTrue(lifecycle.retire {})
    val marker = Any()
    assertSame(marker, runtime.descriptor(manager, Entry(), false) { marker })
    runtime.produced(0, "os.kei", bitmap())
    runtime.replay()
    assertEquals(0, lookups)
  }

  @Test fun unsuccessfulProbeRestoresBothNativeCachesEvenWhenNativeRecomputeThrows() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    val avatar = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    entry.pack.small = old
    entry.pack.people = avatar
    nativeCache.entries["os.kei"] = bitmap()
    manager.make = {
      it.pack.people = Descriptor(Icon.createWithBitmap(bitmap(Color.YELLOW)))
      error("native probe failure")
    }
    assertSame(old, descriptor(entry))
    assertSame(old, entry.pack.small)
    assertSame(avatar, entry.pack.people)
    assertEquals(1, manager.computes)
    assertEquals(1, failures.size)
    failures.clear()
    assertSame(old, descriptor(entry))
    assertEquals(1, manager.computes)
  }

  @Test fun nativeBitmapCacheMissNeverForcesDescriptorOrNativeIoProducer() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.small = old
    manager.make = { error("cache miss must not execute the native producer") }
    assertSame(old, descriptor(entry))
    assertEquals(0, manager.computes)
    assertEquals(0, nativeProducerCalls)
    nativeCache.entries["os.kei"] = bitmap()
    manager.make = { app(bitmap()) }
    assertNotSame(old, descriptor(entry))
    assertEquals(1, manager.computes)
    assertEquals(0, nativeProducerCalls)
  }

  @Test fun mismatchingNestedProducerCannotFallThroughToNativeIoOrClaimCachedSmallChoice() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.small = old
    nativeCache.entries["os.kei"] = bitmap()
    manager.make = { app(bitmap(), name = "os.other") }
    assertSame(old, descriptor(entry))
    assertSame(old, entry.pack.small)
    assertEquals(0, nativeProducerCalls)
  }

  @Test fun stoppingInsideAdmittedProbeKeepsNativeIoGateClosedUntilItsFinally() = fixture {
    val entry = Entry()
    val old = Descriptor(Icon.createWithBitmap(bitmap()))
    entry.pack.small = old
    nativeCache.entries["os.kei"] = bitmap()
    manager.make = {
      runtime.requestStop()
      val gate = requireNotNull(runtime.probeProducer(0, "os.kei"))
      assertNull(gate.bitmap)
      old
    }
    assertSame(old, descriptor(entry))
    assertSame(old, entry.pack.small)
    assertNull(runtime.probeProducer(0, "os.kei"))
    assertEquals(0, nativeProducerCalls)
    assertTrue(lifecycle.retire {})
  }

  private fun notification() = StatusBarNotification("os.kei", "os.kei", 1, null, Process.myUid(),
    Process.myPid(), 0, Notification(), Process.myUserHandle(), 1L)

  private fun coldExisting(
    readyInitially: Boolean = true, connected: Boolean = false,
    witnessAfterOwnerMiss: Boolean = false,
    setup: Fixture.(Entry, View) -> Unit = { _, _ -> },
    check: Fixture.(Entry, View) -> Unit,
  ) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val audited = CountDownLatch(1)
    lateinit var f: Fixture
    lateinit var entry: Entry
    lateinit var view: View
    var acquisitionCacheReads = 0
    instrumentation.runOnMainSync {
      f = Fixture(entryBootstrap = true)
      entry = Entry().also { it.notification = notification() }
      // Framework copies this historical mutable Bitmap before our factory receipt exists.
      view = f.view(Descriptor(Icon.createWithBitmap(f.bitmap(mutable = true))))
      view.notification = entry.notification
      entry.pack.small = view.selected
      if (connected) {
        val store = Store(f.manager, f.manager.pipeline)
        store.views[entry.notification!!.key] = view
        f.manager.listeners += StoreListener(store)
      } else entry.pack.statusBar = view
      (f.manager.pipeline as Pipeline).collection.entries[entry.notification!!.key] = entry
      Registry.dependencies["native-holder"] = ManagerHolder(f.manager)
      f.manager.make = { f.app(f.bitmap()) }
      if (readyInitially) f.cache["os.kei"] = f.bitmap(clipped = true)
      f.onBootstrap = {
        if (witnessAfterOwnerMiss && it == "Status-bar bootstrap native-owner=false") {
          instrumentation.runOnMainSync {
            val event = Entry().apply {
              pack.small = Descriptor(Icon.createWithResource("android", android.R.drawable.sym_def_app_icon))
            }
            val beforeReads = f.nativeCache.reads
            val beforeProducer = f.nativeProducerCalls
            assertEquals("Cold discovery must not invoke the cache reader", 0, beforeReads)
            f.runtime.descriptor(f.manager, event, false) { f.manager.descriptor(event, false) }
            acquisitionCacheReads = f.nativeCache.reads - beforeReads
            assertEquals("The natural cached descriptor probes the verified cache once", 1, acquisitionCacheReads)
            assertEquals("The natural probe must not execute a native bitmap cache miss",
              beforeProducer, f.nativeProducerCalls)
          }
        }
        if (it.endsWith("entry-audit completed") || it.contains("candidates=0 ")) audited.countDown()
      }
      f.setup(entry, view)
      // No row is present: native collection/SBN/view ownership must suffice.
      f.runtime.bootstrap { listOf(view) }
    }
    try {
      assertTrue("cold entry audit did not finish: ${f.bootstrapLogs}", audited.await(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync {
        f.check(entry, view)
        assertEquals(0, f.nativeProducerCalls)
        assertTrue(f.nativeCache.entries.isEmpty())
        assertEquals("Bootstrap/replay must add no cache reads after owner acquisition",
          acquisitionCacheReads, f.nativeCache.reads)
        assertTrue(f.failures.toString(), f.failures.isEmpty())
      }
    } finally { instrumentation.runOnMainSync { f.close() } }
  }

  @Test fun naturalConsumerOwnerRecoversPreviouslyScannedEntriesAfterColdOwnerMiss() =
    coldExisting(connected = true, witnessAfterOwnerMiss = true, setup = { _, _ ->
      Registry.dependencies.clear()
    }) { entry, view ->
      assertTrue(bootstrapLogs.contains("Status-bar bootstrap native-owner=false"))
      assertTrue(bootstrapLogs.contains("Status-bar bootstrap native-owner=true source=consumer"))
      assertEquals(1, manager.updates)
      assertSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
      runtime.descriptor(manager, entry, false) { manager.descriptor(entry, false) }
      runtime.replay()
      assertEquals(1, manager.updates)
    }

  @Test fun coldExistingCopiedIconRebindsViaActualOwnerWithoutNativeCacheOrNewNotification() = coldExisting {
      entry, view ->
    assertEquals(1, manager.updates)
    assertEquals(1, manager.computes)
    assertSame(entry.notification, view.notification)
    assertSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
    runtime.replay()
    assertEquals(1, manager.updates)
    assertEquals(1, manager.computes)
  }

  @Test fun coldConnectedDisplayUsesExistingStoreOwnershipAndNativeListenerFanOut() =
    coldExisting(connected = true) { entry, view ->
      assertNull(entry.pack.statusBar)
      assertTrue(Manager::class.java.getDeclaredField("pipeline").type.isInterface)
      assertSame((manager.pipeline as Pipeline).collection, manager.listeners.single().store.collection)
      assertEquals(1, manager.updates)
      assertSame(entry.notification, view.notification)
      assertSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
    }

  @Test fun coldConnectedDisplayRejectsForeignCollectionEvenWithSameManagerAndKey() =
    coldExisting(connected = true, setup = { _, _ ->
      manager.listeners.single().store.collection = Collection()
    }) { _, _ ->
      assertEquals(0, manager.updates)
      assertEquals(0, manager.computes)
    }

  @Test fun coldExistingEntryWaitsForSharedReadyAndReplaysExactlyOnce() = coldExisting(readyInitially = false) {
      _, view ->
    assertEquals(0, manager.updates)
    assertEquals(0, manager.computes)
    cache["os.kei"] = bitmap(clipped = true)
    runtime.replay()
    assertEquals(1, manager.updates)
    assertSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
    runtime.replay()
    assertEquals(1, manager.updates)
  }

  @Test fun coldExistingEntryRequiresSbnObjectIdentityEvenWhenKeysMatch() = coldExisting(
    setup = { _, view -> view.notification = notification() },
  ) { entry, view ->
    assertEquals(entry.notification!!.key, view.notification!!.key)
    assertNotSame(entry.notification, view.notification)
    assertEquals(0, manager.updates)
  }

  @Test fun coldExistingEntryRejectsReboundViewBeforeLaterCacheReady() = coldExisting(readyInitially = false) {
      _, view ->
    val foreign = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
    view.selected = foreign
    cache["os.kei"] = bitmap(clipped = true)
    runtime.replay()
    assertEquals(0, manager.updates)
    assertSame(foreign, view.selected)
  }

  @Test fun coldImportantConversationIsSkippedAndFutureNativePeopleChoiceRemainsIntact() = coldExisting(
    setup = { entry, view ->
      val avatar = Descriptor(Icon.createWithBitmap(bitmap(Color.RED)))
      entry.sensitive = true
      entry.importantConversation = true
      view.selected = avatar
      entry.pack.small = avatar
      manager.makePeople = { avatar }
      view.refresh(false)
    },
  ) { entry, view ->
    assertEquals(0, manager.updates)
    assertEquals(0, manager.computes)
    assertEquals(0, lookups)
    assertSame(view.selected, descriptor(entry, people = true))
    assertNotSame(cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
    assertEquals(Color.RED, (view.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
  }

  @Test fun coldBootstrapDoesNotUpdateCurrentNonBitmapIcon() = coldExisting(
    setup = { entry, view ->
      view.selected = Descriptor(Icon.createWithResource("android", android.R.drawable.sym_def_app_icon))
      entry.pack.small = view.selected
      view.refresh(false)
    },
  ) { _, view ->
    assertEquals(Icon.TYPE_RESOURCE, view.selected!!.icon.type)
    assertEquals(0, manager.updates)
    assertEquals(0, manager.computes)
    assertEquals(0, lookups)
  }

  @Test fun ownerLookupReadsInitializedProviderInstanceWithoutCallingProviderOrLazyGet() = fixture(true) {
    val provider = ReadyProvider(manager)
    assertSame(manager, SystemUiStatusBarOwnerLookup.find(entryProfile, Manager::class.java,
      ApplicationHolder(Component(provider))))
    assertEquals(0, provider.gets)
    provider.instance = null
    assertNull(SystemUiStatusBarOwnerLookup.find(entryProfile, Manager::class.java,
      ApplicationHolder(Component(provider))))
    assertEquals(0, provider.gets)
  }

  @Test fun ownerLookupRejectsAmbiguousInitializedOwnersAndHonorsCancellation() = fixture(true) {
    Registry.dependencies["first"] = ManagerHolder(manager)
    Registry.dependencies["second"] = ManagerHolder(Manager())
    assertNull(SystemUiStatusBarOwnerLookup.find(entryProfile, Manager::class.java))
    Registry.dependencies.remove("second")
    assertSame(manager, SystemUiStatusBarOwnerLookup.find(entryProfile, Manager::class.java))
    assertNull(SystemUiStatusBarOwnerLookup.find(entryProfile, Manager::class.java, cancelled = { true }))
  }

  @Test fun installBootstrapRefreshesExistingNativeBitmapWithoutAnotherNotificationOrDescriptorCall() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val ready = CountDownLatch(1)
    lateinit var fixture: Fixture
    lateinit var view: View
    instrumentation.runOnMainSync {
      fixture = Fixture()
      val raw = fixture.bitmap()
      // Pre-existing view/descriptor: no runtime consumer or producer invocation.
      view = fixture.view(Descriptor(Icon.createWithBitmap(raw)))
      fixture.nativeCache.originals.put(0, mapOf("os.kei" to WeakReference(raw)))
      fixture.cache["os.kei"] = fixture.bitmap(clipped = true)
      fixture.onReady = { fixture.runtime.replay(); ready.countDown() }
      fixture.runtime.bootstrap { listOf(view) }
    }
    try {
      assertTrue("installation bootstrap did not publish", ready.await(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync {
        assertEquals(0, fixture.manager.computes)
        assertEquals(0, fixture.nativeProducerCalls)
        assertEquals(2, view.refreshes)
        assertSame(fixture.cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
        assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
      }
    } finally { instrumentation.runOnMainSync { fixture.close() } }
  }

  @Test fun bootstrapIgnoresAvatarsOtherUsersClonedBitmapsAndAmbiguousNativeArt() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val ready = CountDownLatch(1)
    lateinit var fixture: Fixture
    lateinit var views: List<View>
    instrumentation.runOnMainSync {
      fixture = Fixture()
      val original = fixture.bitmap()
      val otherUser = fixture.bitmap(Color.CYAN)
      val ambiguous = fixture.bitmap(Color.RED)
      views = listOf(original, fixture.bitmap(), fixture.bitmap(Color.GREEN), otherUser, ambiguous)
        .map { fixture.view(Descriptor(Icon.createWithBitmap(it))) }
      fixture.nativeCache.originals.put(0, mapOf(
        "os.kei" to WeakReference(original),
        "os.first" to WeakReference(ambiguous), "os.second" to WeakReference(ambiguous)))
      fixture.nativeCache.originals.put(10, mapOf("os.foreign" to WeakReference(otherUser)))
      fixture.cache["os.kei"] = fixture.bitmap(clipped = true)
      fixture.onReady = { fixture.runtime.replay(); ready.countDown() }
      fixture.runtime.bootstrap { views }
    }
    try {
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync {
        assertEquals(2, views.first().refreshes)
        views.drop(1).forEach { assertEquals(1, it.refreshes) }
        assertEquals(0, fixture.nativeCache.reads)
        assertEquals(0, fixture.nativeProducerCalls)
        assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
      }
    } finally { instrumentation.runOnMainSync { fixture.close() } }
  }

  @Test fun bootstrapBindingSurvivesColdSharedCacheUntilLaterReadyReplay() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val ready = CountDownLatch(1)
    lateinit var fixture: Fixture
    lateinit var view: View
    instrumentation.runOnMainSync {
      fixture = Fixture()
      val original = fixture.bitmap()
      view = fixture.view(Descriptor(Icon.createWithBitmap(original)))
      fixture.nativeCache.originals.put(0, mapOf("os.kei" to WeakReference(original)))
      fixture.onReady = { fixture.runtime.replay(); ready.countDown() }
      fixture.runtime.bootstrap { listOf(view) }
    }
    try {
      assertTrue(ready.await(5, TimeUnit.SECONDS))
      instrumentation.runOnMainSync {
        assertEquals(1, view.refreshes)
        fixture.cache["os.kei"] = fixture.bitmap(clipped = true)
        fixture.runtime.replay()
        assertEquals(2, view.refreshes)
        assertSame(fixture.cache["os.kei"], (view.drawable as BitmapDrawable).bitmap)
        fixture.runtime.requestStop()
        fixture.runtime.replay()
        assertEquals(2, view.refreshes)
        assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
      }
      assertTrue(fixture.runtime.stop(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
    } finally { instrumentation.runOnMainSync { fixture.close() } }
  }

  @Test fun parentRetirementKeepsBootstrapWorkerReachableEvenWhenSharedCacheIsAlreadyAbsent() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    lateinit var fixture: Fixture
    lateinit var parent: SystemUiAppIconRuntime
    instrumentation.runOnMainSync {
      fixture = Fixture()
      val raw = fixture.bitmap()
      val view = fixture.view(Descriptor(Icon.createWithBitmap(raw)))
      val original = mapOf("os.kei" to WeakReference(raw))
      val blocked = object : AbstractMap<String, WeakReference<Bitmap>>() {
        override val entries get() = original.entries
        override val size: Int get() {
          entered.countDown()
          while (release.count > 0) {
            try { release.await() } catch (_: InterruptedException) { /* Released explicitly below. */ }
          }
          return original.size
        }
      }
      fixture.nativeCache.originals.put(0, blocked)
      // AndroidTest does not inherit the module's compileOnly libxposed API.
      val constructor = SystemUiAppIconRuntime::class.java.declaredConstructors.single {
        it.parameterTypes.contentEquals(arrayOf(Context::class.java, ClassLoader::class.java,
          ThemeCompatibilityLifecycle::class.java, Function3::class.java, Function2::class.java,
          Function3::class.java))
      }.apply { isAccessible = true }
      val replace: (Any?, Any?, Any?) -> Unit = { _, _, _ -> }
      val report: (String, Throwable) -> Unit = { _, _ -> }
      val logger: (Int, String, String) -> Unit = { _, _, _ -> }
      parent = constructor.newInstance(fixture.context, fixture.context.classLoader, fixture.lifecycle,
        replace, report, logger) as SystemUiAppIconRuntime
      parent.javaClass.getDeclaredField("statusBar").apply { isAccessible = true }.set(parent, fixture.runtime)
      fixture.runtime.bootstrap { listOf(view) }
    }
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertFalse(parent.stop(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20)))
      assertTrue(parent.retirementStatus().contains("bootstrapAlive=true"))
      release.countDown()
      assertTrue(parent.stop(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
      assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
    } finally {
      release.countDown()
      parent.stop(System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
      instrumentation.runOnMainSync { fixture.close() }
    }
  }

  @Test fun nativeExceptionLeavesNoProducerFrameAndRetirementDoesNotWaitForLeakedCallback() = fixture {
    val error = IllegalStateException("ordinary native failure")
    assertSame(error, assertThrows(IllegalStateException::class.java) {
      runtime.descriptor(manager, Entry(), false) {
        runtime.produced(0, "os.kei", bitmap())
        throw error
      }
    })
    val native = Descriptor(Icon.createWithBitmap(bitmap()))
    assertSame(native, runtime.descriptor(manager, Entry(), false) { native })
    val view = view(native)
    cache["os.kei"] = bitmap(clipped = true)
    runtime.replay()
    assertEquals(1, view.refreshes)
    assertTrue(lifecycle.retire {})
  }

  @Test fun boundedWeakBindingsEvictOldViewsAndClearSources() = fixture {
    val bindings = SystemUiStatusBarIconBindings(2)
    val icons = (0..2).map { Icon.createWithBitmap(bitmap()) }
    val views = icons.map { View(context) }
    val records = icons.mapIndexed { i, icon ->
      bindings.remember(icon, "os.kei")
      bindings.bind(views[i], icon, null)
      bindings.snapshot().last()
    }
    assertEquals(2, bindings.snapshot().size)
    assertNull(bindings.current(records.first()))
    assertSame(views.last(), bindings.current(records.last()))
    bindings.clear()
    assertNull(bindings.name(icons.last()))
    assertNull(bindings.current(records.last()))
  }
}
