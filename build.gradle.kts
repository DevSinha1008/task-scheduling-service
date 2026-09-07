// build.gradle.kts — Gradle build using the Kotlin DSL.
//
// Language split (deliberate, and the reason is structural rather than
// preferential): the application itself is Java, and only the test source
// set is Kotlin. Gradle supports this directly — the `kotlin` plugin adds a
// Kotlin compilation step, and because Kotlin and Java both compile to JVM
// bytecode, Kotlin tests can instantiate and call Java classes with no
// bridging code at all. Kotlin's value in tests is concrete: string
// templates for readable assertions, `apply`/`let` for building fixtures,
// and data classes for test payloads without boilerplate.

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "1.9.25"
}

group = "com.scheduler"
version = "0.1.0"

java {
    toolchain {
        // Pin the JDK rather than inheriting whatever is on PATH, so the
        // build is reproducible across machines and in CI.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Web layer: embedded Tomcat, Spring MVC, Jackson JSON ---
    implementation("org.springframework.boot:spring-boot-starter-web")

    // --- Persistence: Spring Data JPA over Hibernate ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // --- Bean Validation (@NotNull, @Min, ...) on request DTOs ---
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Spring Security: filter chain, password encoding, authorisation ---
    implementation("org.springframework.boot:spring-boot-starter-security")

    // --- JWT creation and verification (JJWT) ---
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // --- PostgreSQL driver (runtime only; code never imports it) ---
    runtimeOnly("org.postgresql:postgresql")

    // --- Test dependencies ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(kotlin("test"))

    // Testcontainers boots a REAL PostgreSQL in Docker for integration tests.
    // This matters for correctness here: the job queue relies on Postgres
    // features (SKIP LOCKED, optimistic locking under real transactions)
    // that an in-memory H2 database does not faithfully emulate. Testing
    // against H2 would prove the code works on a database we never ship.
    testImplementation("org.testcontainers:junit-jupiter:1.20.2")
    testImplementation("org.testcontainers:postgresql:1.20.2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")  // honour Java nullability annotations
    }
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
