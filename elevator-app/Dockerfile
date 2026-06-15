FROM maven:3.9.9-eclipse-temurin-21-jammy AS build
WORKDIR /home/build

COPY . .
RUN mvn clean package -pl elevator-app -am

FROM eclipse-temurin:21-jre-jammy
WORKDIR /srv/elevator-app

COPY --from=build /home/build/elevator-app/src/main/resources/logback.xml logback.xml
COPY --from=build /home/build/elevator-app/target/elevator-app-*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]