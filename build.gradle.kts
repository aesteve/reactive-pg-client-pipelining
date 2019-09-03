import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val vertxVersion = "3.8.0"

plugins {
    kotlin("jvm") version "1.3.50"
}

group = "com.github.aesteve"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    fun vertx(vararg modules: String) {
        modules.forEach { implementation("io.vertx:$it:$vertxVersion") }
    }
    implementation(kotlin("stdlib-jdk8"))
    vertx(
        "vertx-web",
        "vertx-lang-kotlin",
        "vertx-rx-java2",
        "vertx-pg-client",
        "vertx-web-client"
    )


}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}
