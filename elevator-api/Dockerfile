FROM maven:3.9.9-eclipse-temurin-21-jammy AS build
WORKDIR /home/build

COPY . .
# Tests are gated by the Build & Test workflow on the same commit; skip them here.
RUN mvn -B -ntp clean package -pl elevator-api -am -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /srv/elevator-api
COPY --from=build /home/build/elevator-api/target/elevator-api-*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]
EXPOSE 8080