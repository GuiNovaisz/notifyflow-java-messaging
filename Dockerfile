FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY . .
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-alpine
RUN addgroup -S notifyflow && adduser -S -G notifyflow notifyflow
WORKDIR /app
ARG MODULE
COPY --from=build /build/${MODULE}/target/${MODULE}-0.1.0-dev-exec.jar /app/app.jar
USER notifyflow
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","/app/app.jar"]
