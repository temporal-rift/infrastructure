FROM maven:3.9.16-eclipse-temurin-26 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q -Dspotless.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true
COPY src ./src
RUN mvn package -DskipTests -q -Dspotless.skip=true -Dcheckstyle.skip=true

FROM eclipse-temurin:26
WORKDIR /app
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --no-create-home app
COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar
EXPOSE 8888
USER app
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl --fail --silent http://localhost:8888/actuator/health || exit 1
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
