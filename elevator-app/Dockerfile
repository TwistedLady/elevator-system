FROM maven:3.9.9-eclipse-temurin-21-jammy AS build
WORKDIR /home/build

COPY . .
# Tests are gated by the Build & Test workflow on the same commit; skip them here.
RUN mvn -B -ntp clean package -pl elevator-app -am -DskipTests

FROM eclipse-temurin:21-jre-jammy
# Links the GHCR package to this repository (github.com → Packages).
LABEL org.opencontainers.image.source="https://github.com/TwistedLady/elevator-system"
WORKDIR /srv/elevator-app

COPY --from=build /home/build/elevator-app/src/main/resources/logback.xml logback.xml
COPY --from=build /home/build/elevator-app/target/elevator-app-*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]