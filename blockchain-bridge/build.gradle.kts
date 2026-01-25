plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "io.chainscape"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Solana Java SDK
    implementation("com.github.skynetcap:solanaj:1.17.6")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Database
    implementation("org.postgresql:postgresql:42.6.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:9.22.3")

    // Redis
    implementation("redis.clients:jedis:5.0.2")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // WebSocket
    implementation("org.java-websocket:Java-WebSocket:1.5.4")

    // Metrics
    implementation("io.micrometer:micrometer-core:1.12.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.assertj:assertj-core:3.24.2")
}

application {
    mainClass.set("io.chainscape.bridge.BridgeServiceMain")
}

tasks.shadowJar {
    archiveBaseName.set("bridge-service")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}
