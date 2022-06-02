FROM alpine
 
MAINTAINER 'JKstack Developers <qa@jkstack.com>'

WORKDIR /jacoco/agent

COPY ./target/classes/jacocoagent.jar .

# docker build . -t harbor.jkservice.org/infra/jacoco-agent:latest