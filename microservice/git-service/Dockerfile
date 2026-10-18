FROM eclipse-temurin:25-jre
RUN groupadd --gid 10001 shipora \
    && useradd --uid 10001 --gid shipora --no-create-home shipora \
    && mkdir -p /app /var/lib/shipora \
    && chown shipora:shipora /var/lib/shipora
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar /app/git-service.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=selfhost SHIPORA_DATA_DIR=/var/lib/shipora
EXPOSE 8084
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/git-service.jar"]
