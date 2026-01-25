plugins {
    kotlin("jvm") version "1.9.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "io.chainscape"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Netty for networking
    implementation("io.netty:netty-all:4.1.100.Final")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Database
    implementation("org.postgresql:postgresql:42.6.0")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Redis
    implementation("redis.clients:jedis:5.0.2")

    // Crypto for wallet verification
    implementation("org.bouncycastle:bcprov-jdk18on:1.76")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
}

application {
    mainClass.set("io.chainscape.GameServerKt")
}

tasks.shadowJar {
    archiveBaseName.set("server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
