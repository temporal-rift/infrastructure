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
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/temurin-26-jdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

ARG MAVEN_VERSION=3.9.13
RUN wget -q "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -O /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz
ENV PATH="/opt/apache-maven-${MAVEN_VERSION}/bin:${PATH}"

WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --quiet dependency:go-offline
COPY src ./src

ENTRYPOINT ["mvn", "--batch-mode", "-Pbrowser-e2e", "verify"]
