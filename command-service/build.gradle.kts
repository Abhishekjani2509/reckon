plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.reckon"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        // Pin the compiler to Java 21 explicitly rather than inheriting whatever JDK
        // happens to launch Gradle. Without this the build silently follows the machine,
        // which is how "works locally, fails in CI" starts.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Spring JDBC, deliberately not Spring Data JPA.
    //
    // JPA maps objects to rows and keeps a dirty-checking cache that writes UPDATEs
    // for you. That is precisely the model event sourcing rejects: we never update a
    // row, we append facts and fold them into state ourselves. JPA's machinery would
    // be dead weight fighting the design, and it would hide the SQL that this system's
    // correctness argument rests on.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Exposes /actuator/health, including a `db` check that proves the Postgres
    // connection is live rather than merely that the web server started.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    runtimeOnly("org.postgresql:postgresql")

    // Schema migrations as versioned SQL in the repo, applied on boot. Since Flyway
    // 10 the Postgres support ships as its own module, so both artifacts are needed.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
