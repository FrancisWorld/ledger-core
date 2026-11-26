FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config config
# Resolve dependencies first so this layer is cached until the pom changes.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Formatting and style gates run in CI; the image build only needs the artifact.
RUN ./mvnw -B -q -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true package \
    && java -Djarmode=tools -jar target/ledger-core-*.jar extract --layers --destination target/extracted

FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --system --gid 10001 ledger \
    && useradd --system --uid 10001 --gid ledger --no-create-home --shell /usr/sbin/nologin ledger
WORKDIR /app
COPY --from=build --chown=ledger:ledger /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=ledger:ledger /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=ledger:ledger /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=ledger:ledger /workspace/target/extracted/application/ ./
USER ledger:ledger
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/ledger-core-0.1.0.jar"]
