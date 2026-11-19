# Builds BBCinema without needing Java or Maven on the host.

# Build stage: compile and package the jar. The Maven cache mount keeps downloaded libraries
# between builds, so a rebuild after a code change takes seconds instead of minutes.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

# Run stage: only the JRE and the jar, running as a normal user instead of root.
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Ho_Chi_Minh
RUN useradd --system --create-home --uid 10001 bbcinema \
    && mkdir -p /app/uploads \
    && chown -R bbcinema:bbcinema /app
COPY --from=build --chown=bbcinema:bbcinema /app/target/bbcinema.jar app.jar
USER bbcinema
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
