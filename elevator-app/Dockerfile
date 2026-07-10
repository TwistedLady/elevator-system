# Multi-stage build for elevator-app; tests are gated by CI, so skipped here. Runtime is distroless
# + AppCDS: first start auto-creates the shared archive at /tmp (writable in distroless), later starts
# memory-map it to cut class-load time. LABEL links the GHCR package to the repo.
FROM maven:3.9.9-eclipse-temurin-21-jammy AS build
WORKDIR /home/build

COPY . .
RUN mvn -B -ntp clean package -pl elevator-app -am -DskipTests

FROM gcr.io/distroless/java21-debian12:nonroot
LABEL org.opencontainers.image.source="https://github.com/TwistedLady/elevator-system"
WORKDIR /srv/elevator-app

COPY --from=build /home/build/elevator-app/src/main/resources/logback.xml logback.xml
COPY --from=build /home/build/elevator-app/target/elevator-app-*.jar app.jar
ENTRYPOINT ["java","-XX:+AutoCreateSharedArchive","-XX:SharedArchiveFile=/tmp/app.jsa","-jar","app.jar"]