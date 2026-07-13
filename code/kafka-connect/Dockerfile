FROM confluentinc/cp-kafka-connect:7.9.0

USER root

COPY certs/zscaler-ca.pem /tmp/zscaler-ca.pem
RUN keytool -importcert -noprompt -trustcacerts \
    -alias zscaler-ca -file /tmp/zscaler-ca.pem \
    -cacerts -storepass changeit

RUN confluent-hub install --no-prompt confluentinc/kafka-connect-s3:latest

USER appuser
