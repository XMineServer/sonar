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
// Своя координата ru.xmine.thirdparty:sonar-velocity в разделе third-party - оттуда jar
// берёт VelocityServer (plugins.yml). Версия - настоящий maven-SNAPSHOT, приходит из
// xmine-publish.yml через -PxmineVersion: для SNAPSHOT Reposilite сам ведёт
// maven-metadata.xml, без которого fetch.py образа не соберёт имя файла.
// Значение по умолчанию - только чтобы локальная сборка без -P не падала.
publishing {
  publications.create<MavenPublication>("xmineThirdParty") {
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
      url = uri(providers.environmentVariable("XMINE_MAVEN_URL").getOrElse("https://maven.xmine.world/third-party"))
      credentials {
        username = providers.environmentVariable("XMINE_MAVEN_USERNAME").orNull
        password = providers.environmentVariable("XMINE_MAVEN_PASSWORD").orNull
      }
    }
  }
}
// XMine end - публикация в свой Reposilite
