FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
  mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S core && adduser -S core -G core \
  && mkdir -p /data/files && chown -R core:core /data
WORKDIR /app
COPY --from=build /workspace/target/core-platform-runtime-*.jar app.jar
USER core
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
