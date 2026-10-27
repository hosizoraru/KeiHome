pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    maven {
      name = "MiuixSnapshots"
      url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
      credentials {
        username = providers.gradleProperty("gpr.user")
          .orElse(providers.environmentVariable("GITHUB_ACTOR"))
          .orNull
        password = providers.gradleProperty("gpr.key")
          .orElse(providers.environmentVariable("GITHUB_TOKEN"))
          .orNull
      }
      content {
        includeGroup("top.yukonga.miuix.kmp")
      }
    }
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KeiHome"

include(":app")
