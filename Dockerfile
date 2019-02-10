FROM openjdk:8-jdk

# prepare
RUN useradd -s /bin/bash -m jobmanager && \
    mkdir /jobmanager && \
    #
    # link sh to bash (for convenience)
    ln -fs /bin/bash /bin/sh

# copy binaries and config
COPY build/install/jobmanager3 /jobmanager
COPY conf /jobmanager/conf
RUN chown -R jobmanager:root /jobmanager && \
    chmod -R g+rw /jobmanager
WORKDIR /jobmanager

USER jobmanager

ENV JAVA_OPTS="-Xmx4096m -Xms1024m -Dvertx.disableDnsResolver=true"

CMD ["bin/jobmanager3"]
