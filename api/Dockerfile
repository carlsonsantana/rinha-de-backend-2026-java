FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/api.jar /app/api.jar
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-Xmx96m", "-jar", "/app/api.jar"]
