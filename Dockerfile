# How Railway (or any Docker host) builds and runs CPA Uganda.

# 1. Build the app with Maven and Java 21
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# 2. Run it on a small Java 21 image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Uganda time, so transactions get the right date; keep memory within a small server
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Africa/Kampala -XX:MaxRAMPercentage=75"
# Railway sets PORT; the app reads it (application.properties)
EXPOSE 8082
CMD ["java", "-jar", "app.jar"]
