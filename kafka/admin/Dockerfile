FROM registry.access.redhat.com/ubi8/ubi-minimal:latest

USER root

ARG JAVA_VERSION=17

RUN microdnf update \
    && microdnf install java-${JAVA_VERSION}-openjdk-headless shadow-utils \
    && microdnf clean all

# Set JAVA_HOME env var
ENV JAVA_HOME /usr/lib/jvm/jre-17

# Add strimzi user with UID 1001
# The user is in the group 0 to have access to the mounted volumes and storage
RUN useradd -r -m -u 1001 -g 0 strimzi

ARG version=latest
ENV VERSION ${version}

COPY ./scripts/ /bin
COPY ./src/main/resources/log4j2.properties /bin/log4j2.properties

ADD target/test-client-kafka-admin.jar /

USER 1001

CMD ["/bin/run.sh", "/test-client-kafka-admin.jar"]