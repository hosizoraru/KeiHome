package os.kei.hyper.home.xposed

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.os.UserHandle
import java.io.FileDescriptor

/** Cache boot-class members only. User, UID, ownership and process results stay live. */
internal object NativeMonetPlatformMembers {
  val currentUser by lazy { ActivityManager::class.java.getMethod("getCurrentUser") }
  val contextUser by lazy { Context::class.java.getMethod("getUserId") }
  val packageUid by lazy {
    PackageManager::class.java.getMethod("getPackageUidAsUser", String::class.java,
      Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
  }
  val socketConstructor by lazy {
    LocalSocket::class.java.getConstructor(FileDescriptor::class.java)
  }
  val userHandle by lazy {
    UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
  }
  val userContext by lazy {
    Context::class.java.getMethod("createContextAsUser", UserHandle::class.java,
      Int::class.javaPrimitiveType)
  }
  val enabledProfiles by lazy {
    android.os.UserManager::class.java.getMethod("getEnabledProfiles", Int::class.javaPrimitiveType)
  }
  val userInfoHandle by lazy {
    Class.forName("android.content.pm.UserInfo").getMethod("getUserHandle")
  }
}
