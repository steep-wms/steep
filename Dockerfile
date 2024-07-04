FROM azul/zulu-openjdk:21

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
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
      $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
      tee /etc/apt/sources.list.d/docker.list > /dev/null && \
    apt-get update && \
    apt-get install -y --no-install-recommends docker-ce-cli && \
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
