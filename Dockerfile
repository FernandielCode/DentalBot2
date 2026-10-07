# Stage 1: Build the application using Eclipse Temurin JDK 21
FROM eclipse-temurin:21-jdk-alpine AS build
#WORKDIR : The Linux equivalent of mkdir /app && cd /app.
WORKDIR /app

# Copy Maven wrapper and pom.xml
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Copy source code (pom.xml already excludes vue-project1 from resource copying)
COPY src ./src

# Package the application into an executable JAR
RUN ./mvnw clean package -DskipTests

# Stage 2: Create lightweight production JRE runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as a non-privileged user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy JAR artifact from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose Spring Boot default port
EXPOSE 8080

# Launch Spring Boot
ENTRYPOINT ["java", "-jar", "app.jar"]