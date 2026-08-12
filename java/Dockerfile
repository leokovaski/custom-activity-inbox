# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# ---- Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/custom-activity-1.0.0.jar app.jar

ENV PORT=8080
EXPOSE 8080

# API por padrão; o worker sobrescreve com a env SPRING_PROFILES_ACTIVE=worker
CMD ["java", "-jar", "app.jar"]
