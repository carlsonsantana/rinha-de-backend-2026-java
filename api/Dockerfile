FROM maven:3-eclipse-temurin-25 AS build-api
WORKDIR /build
COPY api/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY api/src ./src
RUN mvn -B -q -DskipTests clean package

FROM maven:3-eclipse-temurin-25 AS build-indexer
WORKDIR /build
COPY indexer/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY indexer/src ./src
RUN mvn -B -q -DskipTests clean package

FROM eclipse-temurin:25-jre AS indexer
WORKDIR /app
COPY --from=build-indexer /build/target/indexer.jar /app/indexer.jar
COPY api/src/main/resources/references.json.gz  /data/references.json.gz
COPY api/src/main/resources/normalization.json  /data/normalization.json
COPY api/src/main/resources/mcc_risk.json       /data/mcc_risk.json
RUN java -Xmx256m -jar /app/indexer.jar \
        /data/references.json.gz /data/index.bin

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build-api /build/target/api.jar /app/api.jar
COPY --from=indexer /data/index.bin         /data/index.bin
COPY api/src/main/resources/normalization.json /data/normalization.json
COPY api/src/main/resources/mcc_risk.json      /data/mcc_risk.json
CMD ["java", "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector", "-jar", "/app/api.jar"]
