
FROM azul/zulu-openjdk-alpine:21-jre
EXPOSE 8080/tcp
COPY out.jar /out.jar
ENTRYPOINT ["java", "-jar", "/out.jar"]