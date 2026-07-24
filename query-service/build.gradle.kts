plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.reckon"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Reads the Postgres read models (the durable fallback and the source for history).
    // No Flyway: query-service does not own this schema, projection-service does. Two
    // services running migrations against one database would fight over its history table.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Reads hot balances from Redis.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus registry: exposes /actuator/prometheus for scraping.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
