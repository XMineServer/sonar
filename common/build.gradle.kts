dependencies {
  compileOnly(project(":api"))
  implementation(project(":captcha"))
  compileOnly(rootProject.libs.adventure.nbt)

  // XMine: shared verified list. Bundled and relocated, never downloaded at runtime.
  // org.json is under the "JSON License" (not to be used for evil), which GPL-3 does not
  // allow; Jedis needs it only for RedisJSON. Gson and SLF4J are on the proxy already.
  implementation(rootProject.libs.jedis) {
    exclude(group = "org.json")
    exclude(group = "com.google.code.gson")
    exclude(group = "org.slf4j")
  }

  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  testImplementation(project(":api"))
  testImplementation(rootProject.libs.caffeine)
  testImplementation(rootProject.libs.netty)
  testImplementation("org.testcontainers:testcontainers:${rootProject.libs.versions.testcontainers.get()}")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter:${rootProject.libs.versions.testcontainers.get()}")
  testCompileOnly(rootProject.libs.annotations)
}

tasks {
  test {
    useJUnitPlatform()
  }
}
