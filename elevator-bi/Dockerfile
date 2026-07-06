# Package the pre-built uber jar onto the official Spark 3.5.3 (Scala 2.12, Java 17) runtime.
# The jar is built on the host first (scripts/bi-up.sh runs `mvn -f elevator-bi/pom.xml package`),
# so this image stays tiny and fast to rebuild. Uber jar bundles the Kafka source + Postgres driver;
# Spark itself is provided by this base image.
FROM apache/spark:3.5.3-java17

COPY target/elevator-bi.jar /opt/spark/jobs/elevator-bi.jar
# Log4j2 config used by both the driver and executors (console + durable rolling file).
COPY conf/log4j2.properties /opt/spark/conf/log4j2.properties
