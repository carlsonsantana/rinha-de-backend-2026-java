FROM maven:3-eclipse-temurin-25 AS build-indexer
WORKDIR /build/indexer
COPY indexer/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY indexer/src ./src
RUN mvn -B -q -DskipTests install
COPY indexer/src/main/resources/references.json.gz /data/references.json.gz
COPY api/src/main/resources/normalization.json     /data/normalization.json
COPY api/src/main/resources/mcc_risk.json          /data/mcc_risk.json
RUN java -Xmx256m -jar target/indexer.jar \
        /data/references.json.gz /data/index.bin

FROM maven:3-eclipse-temurin-25 AS build-api
WORKDIR /build/api
COPY --from=build-indexer /root/.m2 /root/.m2
COPY api/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY api/src ./src
RUN mvn -B -q -DskipTests clean package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build-api   /build/api/target/api.jar /app/api.jar
COPY --from=build-indexer /data/index.bin         /data/index.bin
COPY api/src/main/resources/normalization.json /data/normalization.json
COPY api/src/main/resources/mcc_risk.json      /data/mcc_risk.json
CMD ["java", "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector", "-jar", "/app/api.jar"]
