# Package the pre-built uber jar onto official Spark 3.5.3 (Scala 2.12, Java 17).
# The jar is built on the host first (scripts/bi-up.sh) so the image stays tiny; it
# bundles the Kafka source + Postgres driver, Spark comes from this base image.
FROM apache/spark:3.5.3-java17

COPY target/elevator-bi.jar /opt/spark/jobs/elevator-bi.jar
COPY conf/log4j2.properties /opt/spark/conf/log4j2.properties
