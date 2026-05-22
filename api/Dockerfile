FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build
COPY api/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY api/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre AS indexer
WORKDIR /app
COPY --from=build /build/target/api.jar /app/api.jar
COPY api/src/main/resources/references.json.gz  /data/references.json.gz
COPY api/src/main/resources/normalization.json  /data/normalization.json
COPY api/src/main/resources/mcc_risk.json       /data/mcc_risk.json
RUN java -Xmx256m -cp /app/api.jar rinha.index.BuildIndex \
        /data/references.json.gz /data/index.bin

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/api.jar /app/api.jar
COPY --from=indexer /data/index.bin         /data/index.bin
COPY api/src/main/resources/normalization.json /data/normalization.json
COPY api/src/main/resources/mcc_risk.json      /data/mcc_risk.json
CMD ["java", "--enable-native-access=ALL-UNNAMED", "-Xmx96m", "-jar", "/app/api.jar"]
