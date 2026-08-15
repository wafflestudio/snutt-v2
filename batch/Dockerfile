FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY . /app
RUN ./gradlew :batch:bootJar
ENTRYPOINT java $JAVA_OPTS -jar batch/build/libs/snutt-batch.jar --job.name=${JOB_NAME}
