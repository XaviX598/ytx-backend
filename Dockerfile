FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg ca-certificates curl python3 \
  && rm -rf /var/lib/apt/lists/*

RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
    -o /usr/local/bin/yt-dlp \
  && chmod +x /usr/local/bin/yt-dlp

WORKDIR /app
COPY . .

# Recomendado: Maven Wrapper (mvnw)
RUN chmod +x mvnw && ./mvnw -DskipTests package

RUN mkdir -p /tmp/yt && chmod 777 /tmp/yt

ENV YT_DLP_PATH=yt-dlp
ENV TMP_DIR=/tmp/yt

EXPOSE 8080
CMD ["java","-jar","target/*.jar"]
