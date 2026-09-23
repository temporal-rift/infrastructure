# Build context is the infrastructure repo root (docker-compose.e2e.yml's e2e-tests service sets
# `dockerfile: browser-e2e/Dockerfile` with `context: .`) -- the browser-e2e test sources share one
# Maven module with the rest of this repo's system tests, so the whole module has to be present to
# compile, not just src/test/java/.../browser/.
FROM mcr.microsoft.com/playwright/java:v1.61.0-noble

# The base image ships OpenJDK 25 (confirmed via microsoft/playwright-java's own Dockerfile); this
# project's baseline is Java 26 -- do not rely on the base image's default JDK.
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends wget apt-transport-https gnupg ca-certificates \
    && wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends temurin-26-jdk \
    && ln -s "$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")" /usr/lib/jvm/temurin-26 \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/temurin-26
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# dlcdn.apache.org only serves the current release of each line -- the pinned version 404s once
# the next 3.9.x ships. Maven Central keeps every version and publishes a checksum to verify.
ARG MAVEN_VERSION=3.9.13
RUN maven_base="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && wget -q "$maven_base" -O /tmp/maven.tar.gz \
    && echo "$(wget -qO- "${maven_base}.sha512" | cut -d' ' -f1)  /tmp/maven.tar.gz" | sha512sum -c - \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz
ENV PATH="/opt/apache-maven-${MAVEN_VERSION}/bin:${PATH}"

WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --quiet dependency:go-offline
COPY src ./src

ENTRYPOINT ["mvn", "--batch-mode", "-Pbrowser-e2e", "verify"]
