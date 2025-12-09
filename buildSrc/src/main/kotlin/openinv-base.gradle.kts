plugins {
  `java-library`
  id("net.ltgt.errorprone")
}

java {
  toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
  mavenCentral()
  maven("https://repo.papermc.io/repository/maven-public/")
  maven("https://hub.spigotmc.org/nexus/content/groups/public/")
}

dependencies {
  val libs = versionCatalogs.named("libs")
  compileOnly(libs.findLibrary("annotations").orElseThrow())
  compileOnly(libs.findLibrary("spigotapi").orElseThrow())
  errorprone(libs.findLibrary("errorprone-core").orElseThrow())
}

tasks {
  withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = Charsets.UTF_8.name()
  }
  withType<Javadoc>().configureEach {
    options.encoding = Charsets.UTF_8.name()
  }
}
