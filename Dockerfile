# Multi-stage build: compile with the full JDK, ship only the JRE.
# The build stage carries the compiler, Gradle, and the dependency cache
# (~700MB); none of it is needed at runtime, so only the built jar is
# copied into the slim final image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Copy build files first, separately from source. Docker caches layers, so
# dependency resolution is only re-run when the build files change, not on
# every source edit.
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
COPY gradlew .
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
