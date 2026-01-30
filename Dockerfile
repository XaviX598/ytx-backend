FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg ca-certificates curl python3 \
  && rm -rf /var/lib/apt/lists/*

RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
    -o /usr/local/bin/yt-dlp \
  && chmod +x /usr/local/bin/yt-dlp

WORKDIR /app
COPY . .

# Compilar con Maven Wrapper
RUN chmod +x mvnw && ./mvnw -DskipTests package

# Copiar el jar a un nombre fijo (sin comodines)
RUN cp target/*.jar /app/app.jar

RUN mkdir -p /tmp/yt && chmod 777 /tmp/yt

ENV YT_DLP_PATH=yt-dlp
ENV TMP_DIR=/tmp/yt

EXPOSE 8080

CMD ["java","-jar","/app/app.jar"]
