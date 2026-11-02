plugins {
  `java-gradle-plugin`
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(17)
  }
}

buildscript {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
  dependencies {
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
  }
}

apply(plugin = "org.jetbrains.kotlin.jvm")

gradlePlugin {
  plugins {
    create("keihomeAndroidApplication") {
      id = "keihome.android.application"
      implementationClass = "os.kei.buildlogic.KeiHomeAndroidApplicationPlugin"
    }
  }
}

repositories {
  google()
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  implementation("com.android.tools.build:gradle:9.4.1")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
  implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
  implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.21")
  testImplementation("junit:junit:4.13.2")
}
