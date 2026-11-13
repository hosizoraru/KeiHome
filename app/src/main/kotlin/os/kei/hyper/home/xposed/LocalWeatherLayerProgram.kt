package os.kei.hyper.home.xposed

import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram

/** Admission shares the exact display schemas used by generation, without performing provider IO. */
internal object LocalWeatherLayerProgram {
  fun resources(bytes: ByteArray, base: String, paths: Set<String>): Set<String>? =
    NativeWeatherLayerProgram.resources(bytes, base, paths)
}
