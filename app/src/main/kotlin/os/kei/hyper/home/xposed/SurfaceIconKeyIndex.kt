package os.kei.hyper.home.xposed

/** Generation-owned keys; the owner's bounded working set controls admission and eviction. */
internal class SurfaceIconKeyIndex {
  data class Key(val name: String, val activity: String?)

  private val packages = HashMap<String, MutableMap<String?, Key>>()
  var size: Int = 0
    private set

  fun find(name: String, activity: String?): Key? = packages[name]?.get(activity)

  fun retain(name: String, activity: String?): Key {
    val activities = packages.getOrPut(name) { HashMap() }
    return activities.getOrPut(activity) {
      size++
      Key(name, activity)
    }
  }

  fun remove(key: Key) {
    val activities = packages[key.name] ?: return
    if (activities.remove(key.activity) != null) size--
    if (activities.isEmpty()) packages.remove(key.name)
  }

  fun clear() {
    packages.clear()
    size = 0
  }
}
