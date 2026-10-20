FROM eclipse-temurin:25-jre
ARG SERVICE
RUN groupadd --gid 10001 shipora && useradd --uid 10001 --gid shipora --create-home shipora \
    && mkdir -p /var/lib/shipora && chown 10001:10001 /var/lib/shipora
WORKDIR /opt/shipora
COPY microservice/${SERVICE}/build/libs/*-SNAPSHOT.jar app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=selfhost
ENTRYPOINT ["java", "-jar", "/opt/shipora/app.jar"]
