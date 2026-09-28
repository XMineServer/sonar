plugins {
  `maven-publish`
}

dependencies {
  implementation(project(":api"))
  implementation(project(":common"))

  compileOnly(rootProject.libs.velocity)

  implementation(rootProject.libs.bstats.velocity)
  implementation(rootProject.libs.libby.velocity)
}

tasks {
  processResources {
    val props = mapOf(
      "version" to rootProject.version.toString(),
      "description" to rootProject.description,
      "url" to "https://sonar.top/discord/",
      "main" to "xyz.jonesdev.sonar.velocity.SonarVelocityPlugin"
    )
    inputs.properties(props)
    filesMatching("velocity-plugin.json") {
      expand(props)
    }
  }
}

// XMine start - публикация в свой Reposilite
// Своя координата ru.xmine.thirdparty:sonar-velocity в разделе-кандидате пары форков
// fork-snapshot (вики, ADR-0056) - оттуда jar берёт VelocityServer (plugins.yml).
// Версия неизменяема и считается из коммита в xmine-publish.yml, сюда приходит через
// -PxmineVersion, раздел - через XMINE_MAVEN_URL. Значения по умолчанию - только чтобы
// локальная сборка без них не падала.
publishing {
  publications.create<MavenPublication>("xmineFork") {
    groupId = "ru.xmine.thirdparty"
    artifactId = "sonar-velocity"
    version = providers.gradleProperty("xmineVersion").getOrElse("0.0.0-xmine-local-SNAPSHOT")
    artifact(tasks["shadowJar"]) {
      classifier = null
    }
  }
  repositories {
    maven {
      name = "xmine"
      url = uri(providers.environmentVariable("XMINE_MAVEN_URL").getOrElse("https://maven.xmine.world/fork-snapshot"))
      credentials {
        username = providers.environmentVariable("XMINE_MAVEN_USERNAME").orNull
        password = providers.environmentVariable("XMINE_MAVEN_PASSWORD").orNull
      }
    }
  }
}
// XMine end - публикация в свой Reposilite
