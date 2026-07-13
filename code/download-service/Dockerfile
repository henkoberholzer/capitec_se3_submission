FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY certs/zscaler-ca.pem /tmp/zscaler-ca.pem
RUN keytool -importcert -noprompt -trustcacerts \
    -alias zscaler-ca -file /tmp/zscaler-ca.pem \
    -cacerts -storepass changeit
COPY download-service/pom.xml download-service/checkstyle.xml ./
RUN mvn dependency:go-offline -q
COPY download-service/src src
RUN mvn package -DskipTests -q

# Runtime: JRE only (build stage already produced the fat jar). Curl is for Compose healthchecks.
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system sdsgroup \
    && adduser --system --ingroup sdsgroup sdsuser
COPY --from=build /build/target/*.jar app.jar
RUN chown sdsuser:sdsgroup app.jar
USER sdsuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
