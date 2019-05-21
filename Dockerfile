FROM openjdk:11-jdk

# prepare
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      apt-transport-https \
      ca-certificates \
      gettext \
      gnupg-agent \
      libnss-wrapper \
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
    chmod g+rwx /jobmanager && \
    #
    # link sh to bash (for convenience)
    ln -fs /bin/bash /bin/sh

# copy binaries and config
COPY --chown=jobmanager:root docker/passwd.template /jobmanager/passwd.template
COPY --chown=jobmanager:root docker/entrypoint.sh /jobmanager/entrypoint.sh
COPY --chown=jobmanager:root build/install/jobmanager3 /jobmanager
COPY --chown=jobmanager:root conf /jobmanager/conf
WORKDIR /jobmanager

USER jobmanager

ENV JAVA_OPTS="-Xmx4096m -Xms1024m -Dvertx.disableDnsResolver=true"

CMD ["./entrypoint.sh"]
