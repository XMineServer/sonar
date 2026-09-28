repositories {
  maven(url = "https://jitpack.io/") // simple-yaml
}

dependencies {
  compileOnly(rootProject.libs.simpleyaml) {
    exclude(group = "org.yaml")
  }
  compileOnly(rootProject.libs.annotations)

  // XMine: tests of the transfer tokens; Gson is provided by the platform at runtime
  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation("com.google.code.gson:gson:2.13.2")
  testCompileOnly(rootProject.libs.annotations)
}

tasks {
  test {
    useJUnitPlatform()
  }

  shadowJar {
    archiveFileName = "sonar-api-${rootProject.version}.jar"
  }
}
