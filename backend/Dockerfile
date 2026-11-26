FROM eclipse-temurin:21-jdk AS build
WORKDIR /opt/gridiq
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /opt/gridiq
RUN groupadd --gid 10001 gridiq \
    && useradd --uid 10001 --gid gridiq --no-create-home gridiq
COPY --from=build /opt/gridiq/target/gridiq-*.jar app.jar
USER gridiq
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/gridiq/app.jar"]
