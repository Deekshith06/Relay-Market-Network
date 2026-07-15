# Stage 1: Compile and build the project
FROM maven:3.8.8-eclipse-temurin-17 AS build
WORKDIR /app

# Copy configuration and source files
COPY pom.xml .
COPY src ./src
COPY frontend ./frontend

# Build the package (skip tests to speed up deployment)
RUN mvn clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the built JAR from Stage 1
COPY --from=build /app/target/relay-delivery-1.0.0.jar app.jar

# Copy static frontend assets (so they can be served by the jar)
COPY --from=build /app/frontend ./frontend

# Expose the application port
EXPOSE 8080

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
