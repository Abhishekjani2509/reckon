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
    // web is here for the actuator HTTP surface and health endpoint, not for serving an
    // API -- projection-service consumes events, it does not answer queries. That is the
    // query service's job, later.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Same reasoning as command-service: plain JDBC, no JPA. The read model is built with
    // deliberate, guarded SQL (the idempotency guard is a WHERE clause), and JPA's
    // dirty-checking would only obscure it.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus registry: exposes /actuator/prometheus for scraping.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // The Kafka consumer. This service is a consumer, where command-service was a producer.
    implementation("org.springframework.kafka:spring-kafka")

    // Redis, written as a second read model. The projector keeps hot balances here
    // alongside the Postgres read model -- cache-as-projection, one writer.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    runtimeOnly("org.postgresql:postgresql")

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
