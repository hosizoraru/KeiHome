package os.kei.hyper.home.xposed

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/** Reads initialized generic provider slots before following unrelated DI graph edges. */
internal object SystemUiStatusBarOwnerPriority {
  data class Result(val matched: Boolean, val owner: Any?, val fields: Int)

  fun inspect(
    ownerType: Class<*>, application: Any?, registryValues: List<Any>,
    cancelled: () -> Boolean = { false }, eligible: (Any) -> Boolean = { true },
  ): Result {
    var inspected = 0
    var matched = false
    var owner: Any? = null
    fun rejected() = Result(true, null, inspected)
    fun fields(type: Class<*>): List<Field>? {
      val result = ArrayList<Field>()
      var current: Class<*>? = type
      while (current != null && current !== Any::class.java && current.classLoader != null) {
        val declared = current.declaredFields
        if (result.size + declared.size > 4_096 || inspected + declared.size > 8_192) return null
        inspected += declared.size
        result += declared
        current = current.superclass
      }
      return result
    }
    fun adopt(value: Any?): Boolean {
      if (value == null || !ownerType.isInstance(value)) return true
      if (owner != null && owner !== value) return false
      owner = value
      return true
    }
    val roots = ArrayList<Any>()
    val seen = java.util.IdentityHashMap<Any, Boolean>()
    fun root(value: Any?) {
      if (value != null && seen.put(value, true) == null) roots += value
    }
    root(application)
    registryValues.forEach(::root)
    if (roots.size > 1_024 || cancelled()) return rejected()
    // Only the application contributes one additional level of component roots.
    // A component's unrelated children are left to the existing bounded fallback.
    if (application != null) {
      val declared = fields(application.javaClass) ?: return rejected()
      for (field in declared) {
        if (cancelled()) return rejected()
        if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive ||
          field.type === String::class.java) continue
        field.isAccessible = true
        val child = field.get(application)
        if (child != null && eligible(child)) root(child)
      }
    }
    if (roots.size > 1_024) return rejected()
    for (value in roots) {
      if (cancelled()) return rejected()
      if (!adopt(value)) return rejected()
      if (value !== application && !eligible(value)) continue
      val declared = fields(value.javaClass) ?: return rejected()
      for (field in declared) {
        if (cancelled()) return rejected()
        if (Modifier.isStatic(field.modifiers)) continue
        if (field.type === ownerType) {
          field.isAccessible = true
          if (!adopt(field.get(value))) return rejected()
          continue
        }
        val signature = field.genericType as? ParameterizedType ?: continue
        val raw = signature.rawType as? Class<*> ?: continue
        if (!raw.isInterface || signature.actualTypeArguments.singleOrNull() !== ownerType) continue
        field.isAccessible = true
        val provider = field.get(value) ?: continue
        val cached = (fields(provider.javaClass) ?: return rejected()).filter {
          !Modifier.isStatic(it.modifiers) && Modifier.isVolatile(it.modifiers) &&
            it.type === Any::class.java
        }
        if (cached.isEmpty()) continue
        if (cached.size > 4) return rejected()
        matched = true
        for (instance in cached) {
          if (cancelled()) return rejected()
          instance.isAccessible = true
          if (!adopt(instance.get(provider))) return rejected()
        }
      }
    }
    return Result(matched, owner, inspected)
  }
}
