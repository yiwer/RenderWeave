FROM maven:3.9.12-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml ./
COPY renderweave-schema/pom.xml renderweave-schema/pom.xml
COPY renderweave-validation/pom.xml renderweave-validation/pom.xml
COPY renderweave-inference/pom.xml renderweave-inference/pom.xml
COPY renderweave-app/pom.xml renderweave-app/pom.xml
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY renderweave-schema renderweave-schema
COPY renderweave-validation renderweave-validation
COPY renderweave-inference renderweave-inference
COPY renderweave-app renderweave-app
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S renderweave && adduser -S renderweave -G renderweave
WORKDIR /app
COPY --from=build /workspace/renderweave-app/target/renderweave-app-1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /var/lib/renderweave/blobs && chown -R renderweave:renderweave /app /var/lib/renderweave
USER renderweave
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

