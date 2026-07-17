# Build stage
FROM maven:3.9.9-eclipse-temurin-24-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:24-jre-alpine
WORKDIR /app
COPY --from=build /app/target/queuectl-1.0.0.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar", "dashboard"]
