FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :batch:bootJar

FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=builder /workspace/batch/build/libs/snutt-batch.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
