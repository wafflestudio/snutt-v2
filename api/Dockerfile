FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :api:bootJar

FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=builder /workspace/api/build/libs/snutt-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
