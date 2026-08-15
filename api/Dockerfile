FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY . /app
RUN ./gradlew :api:bootJar
EXPOSE 8080
ENTRYPOINT java $JAVA_OPTS -jar api/build/libs/snutt-api.jar
