package os.kei.hyper.home.xposed

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisteredCallbackReaderTest {
  private val reader = RegisteredCallbackReader.resolve(
    FixtureCallbackList::class.java,
    FixtureArrayMap::class.java,
    FixtureCallback::class.java,
    FixtureBinder::class.java,
  )

  @Test
  fun snapshotDoesNotEnterAnActiveFrameworkBroadcast() {
    val callbacks = FixtureCallbackList()
    val callback = fixtureCallback()
    val cookie = Any()
    callbacks.register(callback, cookie)
    callbacks.beginBroadcast()
    try {
      val entries = reader.snapshot(callbacks)!!
      assertEquals(1, entries.size)
      assertSame(callback, entries.single().callback)
      assertSame(cookie, entries.single().cookie)
      assertEquals(1, callbacks.broadcastStarts)
    } finally {
      callbacks.finishBroadcast()
    }
  }

  @Test
  fun matchesTheExactCookieAndBinderAcrossMultipleRegistrations() {
    val callbacks = FixtureCallbackList()
    val first = fixtureCallback()
    val second = fixtureCallback()
    val firstCookie = EqualCookie(1)
    val secondCookie = EqualCookie(1)
    callbacks.register(first, firstCookie)
    callbacks.register(second, secondCookie)

    val entries = reader.snapshot(callbacks)!!
    assertEquals(2, entries.size)
    assertNull(reader.findRegistered(callbacks, EqualCookie(1)) { true })
    assertNull(reader.findRegistered(callbacks, firstCookie) { it === second })
    assertSame(second, reader.findRegistered(callbacks, secondCookie) {
      assertFalse(Thread.holdsLock(callbacks.registryLock()))
      (it as FixtureCallback).binder === second.binder
    })
  }

  @Test
  fun snapshotIsAtomicAgainstUnregisterAndRevalidatesTheCookieOutsideTheLock() {
    val callbacks = FixtureCallbackList()
    val callback = fixtureCallback()
    val oldCookie = Any()
    callbacks.register(callback, oldCookie)
    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    val unregisterStarted = CountDownLatch(1)
    val unregisterFinished = CountDownLatch(1)
    val snapshot = AtomicReference<List<RegisteredCallbackEntry>?>()
    callbacks.onReadItem = {
      reading.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    val readerThread = Thread { snapshot.set(reader.snapshot(callbacks)) }
    val unregisterThread = Thread {
      unregisterStarted.countDown()
      callbacks.unregister(callback)
      unregisterFinished.countDown()
    }
    try {
      readerThread.start()
      assertTrue(reading.await(5, TimeUnit.SECONDS))
      unregisterThread.start()
      assertTrue(unregisterStarted.await(5, TimeUnit.SECONDS))
      assertFalse(unregisterFinished.await(100, TimeUnit.MILLISECONDS))
    } finally {
      release.countDown()
      readerThread.join(5_000)
      unregisterThread.join(5_000)
    }
    assertFalse(readerThread.isAlive)
    assertFalse(unregisterThread.isAlive)
    assertTrue(unregisterFinished.await(1, TimeUnit.SECONDS))
    assertSame(callback, snapshot.get()!!.single().callback)
    assertNull(reader.findRegistered(callbacks, oldCookie) { true })

    callbacks.onReadItem = null
    val newCookie = Any()
    callbacks.register(callback, newCookie)
    assertNull(reader.findRegistered(callbacks, oldCookie) { true })
    assertSame(callback, reader.findRegistered(callbacks, newCookie) {
      assertFalse(Thread.holdsLock(callbacks.registryLock()))
      it === callback
    })
  }

  @Test
  fun binderDeathRemovesTheRegistrationBeforeTheNextDispatchCheck() {
    val callbacks = FixtureCallbackList()
    val callback = fixtureCallback()
    val cookie = Any()
    callbacks.register(callback, cookie)
    assertSame(callback, reader.snapshot(callbacks)!!.single().callback)
    callbacks.binderDied(callback.binder)
    assertNull(reader.findRegistered(callbacks, cookie) { it === callback })
    assertTrue(reader.snapshot(callbacks)!!.isEmpty())
  }

  @Test
  fun rejectsOverriddenAccessorsMutatorsAndShadowedRegistry() {
    assertNull(reader.snapshot(OverriddenAccessorList()))
    assertNull(reader.snapshot(OverriddenMutatorList()))
    assertNull(reader.snapshot(ShadowedRegistryList()))
  }

  @Test
  fun rejectsInconsistentEntriesAndAChangedRegistry() {
    val callbacks = FixtureCallbackList()
    val callback = fixtureCallback()
    callbacks.register(callback, Any())
    callbacks.returnWrongCount = true
    assertNull(reader.snapshot(callbacks))
    callbacks.returnWrongCount = false
    callbacks.returnWrongCookie = true
    assertNull(reader.snapshot(callbacks))
    callbacks.returnWrongCookie = false
    callbacks.onReadItem = { error("broken registered callback getter") }
    assertNull(reader.snapshot(callbacks))
    callbacks.onReadItem = null
    callbacks.addMismatchedBinderKey(callback)
    assertNull(reader.snapshot(callbacks))
    callbacks.unregister(callback)
    callbacks.kill()
    callbacks.register(callback, Any())
    callbacks.replaceRegistryOnCount = true
    assertNull(reader.snapshot(callbacks))
  }

  @Test
  fun rejectsUnsupportedFieldAndEntryStructures() {
    assertThrows(IllegalArgumentException::class.java) {
      RegisteredCallbackReader.resolve(
        WrongRegistryField::class.java,
        FixtureArrayMap::class.java,
        FixtureCallback::class.java,
        FixtureBinder::class.java,
      )
    }
    assertThrows(NoSuchElementException::class.java) {
      RegisteredCallbackReader.resolve(
        MissingEntryClass::class.java,
        FixtureArrayMap::class.java,
        FixtureCallback::class.java,
        FixtureBinder::class.java,
      )
    }
    assertThrows(NoSuchMethodException::class.java) {
      RegisteredCallbackReader.resolve(
        MissingRegisteredMethods::class.java,
        FixtureArrayMap::class.java,
        FixtureCallback::class.java,
        FixtureBinder::class.java,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      RegisteredCallbackReader.resolve(
        FixtureCallbackList::class.java,
        FixtureArrayMap::class.java,
        FixtureCallback::class.java,
        WrongBinder::class.java,
      )
    }
  }

  private fun fixtureCallback(): FixtureCallback = object : FixtureCallback {
    override val binder: FixtureBinder = object : FixtureBinder {}
  }

  private data class EqualCookie(val value: Int)
}

interface FixtureBinder

interface WrongBinder

interface FixtureCallback {
  val binder: FixtureBinder
}

class FixtureArrayMap : LinkedHashMap<FixtureBinder, Any>() {
  fun keyAt(index: Int): Any? = keys.elementAt(index)

  fun valueAt(index: Int): Any? = values.elementAt(index)
}

open class FixtureCallbackList {
  @Volatile
  private var mInterfaces = FixtureArrayMap()
  private var broadcasting = false
  var broadcastStarts = 0
    private set
  @Volatile var onReadItem: (() -> Unit)? = null
  var returnWrongCount = false
  var returnWrongCookie = false
  var replaceRegistryOnCount = false

  inner class Interface(
    val mBinder: FixtureBinder,
    val mInterface: FixtureCallback,
    val mCookie: Any?,
  )

  open fun register(callback: FixtureCallback): Boolean = register(callback, null)

  open fun register(callback: FixtureCallback, cookie: Any?): Boolean {
    val registry = mInterfaces
    return synchronized(registry) {
      registry[callback.binder] = Interface(callback.binder, callback, cookie)
      true
    }
  }

  open fun unregister(callback: FixtureCallback): Boolean {
    val registry = mInterfaces
    return synchronized(registry) { registry.remove(callback.binder) != null }
  }

  open fun kill() {
    val registry = mInterfaces
    synchronized(registry) { registry.clear() }
  }

  fun binderDied(binder: FixtureBinder) {
    val registry = mInterfaces
    synchronized(registry) { registry.remove(binder) }
  }

  fun addMismatchedBinderKey(callback: FixtureCallback) {
    val registry = mInterfaces
    synchronized(registry) {
      registry[object : FixtureBinder {}] = Interface(callback.binder, callback, null)
    }
  }

  open fun getRegisteredCallbackCount(): Int {
    val registry = mInterfaces
    return synchronized(registry) {
      if (replaceRegistryOnCount) mInterfaces = FixtureArrayMap()
      registry.size + if (returnWrongCount) 1 else 0
    }
  }

  open fun getRegisteredCallbackItem(index: Int): FixtureCallback? {
    val registry = mInterfaces
    return synchronized(registry) {
      onReadItem?.invoke()
      (registry.valueAt(index) as Interface).mInterface
    }
  }

  open fun getRegisteredCallbackCookie(index: Int): Any? {
    val registry = mInterfaces
    return synchronized(registry) {
      if (returnWrongCookie) Any() else (registry.valueAt(index) as Interface).mCookie
    }
  }

  fun beginBroadcast() {
    val registry = mInterfaces
    synchronized(registry) {
      check(!broadcasting)
      broadcasting = true
      broadcastStarts += 1
    }
  }

  fun finishBroadcast() {
    val registry = mInterfaces
    synchronized(registry) { broadcasting = false }
  }

  fun registryLock(): Any = mInterfaces
}

class OverriddenAccessorList : FixtureCallbackList() {
  override fun getRegisteredCallbackCount(): Int = super.getRegisteredCallbackCount()
}

class OverriddenMutatorList : FixtureCallbackList() {
  override fun unregister(callback: FixtureCallback): Boolean = super.unregister(callback)
}

class ShadowedRegistryList : FixtureCallbackList() {
  @Suppress("unused")
  private val mInterfaces = Any()
}

class WrongRegistryField {
  @Suppress("unused")
  private val mInterfaces: Any = FixtureArrayMap()
}

class MissingEntryClass {
  @Suppress("unused")
  private val mInterfaces = FixtureArrayMap()
}

class MissingRegisteredMethods {
  @Suppress("unused")
  private val mInterfaces = FixtureArrayMap()

  inner class Interface(
    val mBinder: FixtureBinder,
    val mInterface: FixtureCallback,
    val mCookie: Any?,
  )
}
