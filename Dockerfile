FROM azul/zulu-openjdk:18

# prepare
RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends \
      apt-transport-https \
      ca-certificates \
      curl \
      gettext \
      gnupg-agent \
      libnss-wrapper \
      openssh-client \
      software-properties-common \
    && \
    curl -fsSL https://download.docker.com/linux/$(. /etc/os-release; echo "$ID")/gpg | apt-key add - && \
    add-apt-repository \
       "deb [arch=amd64] https://download.docker.com/linux/$(. /etc/os-release; echo "$ID") \
       $(lsb_release -cs) \
       stable" && \
    apt-get update && \
    apt-get install -y --no-install-recommends docker-ce && \
    apt-get purge -y software-properties-common && \
    apt-get autoremove -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    useradd -s /bin/bash -m steep && \
    mkdir /steep && \
    chown steep:root /steep && \
    chmod g+rwx /steep && \
    #
    # link sh to bash (for convenience)
    ln -fs /bin/bash /bin/sh

# copy binaries and config
COPY --chown=steep:root docker/passwd.template /steep/passwd.template
COPY --chown=steep:root docker/entrypoint.sh /steep/entrypoint.sh
COPY --chown=steep:root build/install/steep /steep
COPY --chown=steep:root conf /steep/conf
WORKDIR /steep

USER steep

ENV JAVA_OPTS="-Xmx4096m -Xms1024m -Dvertx.disableDnsResolver=true"

CMD ["./entrypoint.sh"]
