# Сборка из корня репозитория: docker build -f backend/Dockerfile .
# --- сборка
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B -DskipTests package

# --- рантайм: JRE + GDAL (ogr2ogr/ogrinfo для импорта)
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends gdal-bin && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/geoportal.jar /app/geoportal.jar
COPY demo-data /app/demo-data
ENV SEED_DIR=/app/demo-data STORAGE_DIR=/data/storage
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/geoportal.jar"]
