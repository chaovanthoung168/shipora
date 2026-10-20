FROM moby/buildkit:v0.34.0@sha256:b059f8d7226d0b326bc871af489a5dddf65e36af4d20d14251b072974d9ee87c AS buildkit
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 shipora \
    && if ! getent passwd 1000 >/dev/null; then useradd --uid 1000 --gid shipora --create-home shipora; fi \
    && mkdir -p /var/lib/shipora/worker /home/shipora && chown -R 1000:10001 /var/lib/shipora /home/shipora
COPY --from=buildkit /usr/bin/buildctl /usr/local/bin/buildctl
WORKDIR /opt/shipora
COPY microservice/deployment-worker/build/libs/*-SNAPSHOT.jar app.jar
USER 1000:10001
ENV HOME=/home/shipora
ENTRYPOINT ["java", "-jar", "/opt/shipora/app.jar"]
