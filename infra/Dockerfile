# syntax=docker/dockerfile:1
#
# Images of the two services (compose profile `apps`, architecture §16). One file, one build stage, two final stages:
#
#   docker build -f infra/Dockerfile --target order-service   -t opp/order-service   .
#   docker build -f infra/Dockerfile --target payment-service -t opp/payment-service .
#
# The context is the repository root. The jars are built inside the image from the sources, so a checkout with Docker
# alone is enough; the Maven repository is a BuildKit cache, so a rebuild downloads nothing. Tests are not run here
# (that is what `./mvnw verify` and CI do).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q -DskipTests -DskipITs -Djacoco.skip=true \
        -pl services/order-service,services/payment-service -am package

# ---------------------------------------------------------------------------------------------- runtime

FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
USER app
# The code works in UTC (injected Clock); the container takes 75 % of the memory it is given, not of the host's.
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS order-service
COPY --from=build /workspace/services/order-service/target/order-service-*-SNAPSHOT.jar /app/app.jar
EXPOSE 8081

FROM runtime AS payment-service
COPY --from=build /workspace/services/payment-service/target/payment-service-*-SNAPSHOT.jar /app/app.jar
EXPOSE 8082
