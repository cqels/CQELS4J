# The SPARQL 1.1 Protocol endpoint as a container image, built from this directory:
#
#   docker build -t cqels-sparql-endpoint .
#
# No image is published; this file is how you get one. README.md § "Run in Docker" has the
# run commands, and scripts/docker-smoke.sh checks every claim made there against a real
# container (CI runs it on each PR).

# --- build -----------------------------------------------------------------------------
# The jar is built inside the image build, from source, so `docker build` needs nothing on
# the host but Docker: no JDK, no Maven, and no credentials, since the pom names the
# anonymous cqels/maven repository itself. Tests are skipped here because the CI job that
# builds this image has already run them against the same sources; running them again
# would double the build time and test nothing new.
#
# Base images are pinned by tag, not by digest, on purpose: the JRE tag tracks Temurin's
# security patches, and an image built from a digest frozen today would carry every JRE
# CVE fixed after it. What the image runs is pinned where it matters, by the pom.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# The cache mount keeps ~/.m2 across rebuilds on the same machine (BuildKit, the default
# builder since Docker 23); it is not part of the image.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# --- run -------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

# Not root. /data is where a durable store goes (CQELS_SPARQL_STORE_PATH=/data); creating
# it here, owned by this user, is what lets a *named* volume mounted there be writable,
# because Docker initialises a new named volume from the image's directory, ownership
# included. A host directory bind-mounted there keeps its host ownership instead and must
# be writable by uid 10001.
RUN groupadd --system --gid 10001 cqels \
 && useradd --system --uid 10001 --gid cqels --home-dir /nonexistent --shell /usr/sbin/nologin cqels \
 && mkdir /data && chown cqels:cqels /data

COPY --from=build /src/target/cqels-sparql-endpoint.jar /opt/cqels/cqels-sparql-endpoint.jar

# 127.0.0.1 inside a container is the container's own loopback, unreachable through a
# published port, so the image binds all interfaces. That makes the launcher's start-up
# rule apply: a non-loopback bind with no CQELS_SPARQL_AUTH_TOKEN refuses to start (exit 2)
# unless CQELS_SPARQL_ALLOW_UNAUTHENTICATED_REMOTE=true. The image deliberately sets
# neither, so `docker run` with no options fails closed rather than serving the graph to
# whatever network the port is published on.
ENV CQELS_SPARQL_HOST=0.0.0.0 \
    CQELS_SPARQL_PORT=8089
EXPOSE 8089

USER cqels
# Exec form, so the JVM is PID 1 and receives `docker stop`'s SIGTERM directly: its
# shutdown hook stops Jetty, the engine and the store (a NativeStore must be shut down to
# release its lock cleanly). The smoke test checks for exit code 143, not 137 (killed).
ENTRYPOINT ["java", "-jar", "/opt/cqels/cqels-sparql-endpoint.jar"]
