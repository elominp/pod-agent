#Sample Dockerfile
FROM eclipse-temurin:17.0.5_8-jre

ADD target/memory_cushion /usr/bin
ADD target/pod-agent.jar /opt
ADD target/server.jar /opt

CMD ["bash"]
