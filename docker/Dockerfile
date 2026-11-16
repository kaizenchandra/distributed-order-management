FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q package -DskipTests
FROM eclipse-temurin:21-jdk
ARG SERVICE
WORKDIR /app
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar app.jar
COPY docker/healthcheck.sh /app/healthcheck.sh
USER 10001
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
