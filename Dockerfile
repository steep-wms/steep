FROM openjdk:11-jdk

# prepare
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      apt-transport-https \
      ca-certificates \
      gnupg-agent \
      software-properties-common \
    && \
    curl -fsSL https://download.docker.com/linux/$(. /etc/os-release; echo "$ID")/gpg | apt-key add - && \
    add-apt-repository \
       "deb [arch=amd64] https://download.docker.com/linux/$(. /etc/os-release; echo "$ID") \
       $(lsb_release -cs) \
       stable" && \
    apt-get update && \
    apt-get install -y --no-install-recommends docker-ce && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    useradd -s /bin/bash -m jobmanager && \
    mkdir /jobmanager && \
    chown jobmanager:root /jobmanager && \
    #
    # link sh to bash (for convenience)
    ln -fs /bin/bash /bin/sh

# copy binaries and config
COPY --chown=jobmanager:root build/install/jobmanager3 /jobmanager
COPY --chown=jobmanager:root conf /jobmanager/conf
WORKDIR /jobmanager

USER jobmanager

ENV JAVA_OPTS="-Xmx4096m -Xms1024m -Dvertx.disableDnsResolver=true"

CMD ["bin/jobmanager3"]
