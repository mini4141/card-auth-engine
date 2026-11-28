FROM eclipse-temurin:21.0.9_10-jre
ARG JAR
COPY ${JAR} /app/app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
