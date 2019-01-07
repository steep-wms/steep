FROM openjdk:8-jdk

# prepare
RUN apt-get update && \
    apt-get install -y httpie && \
    rm -rf /var/lib/apt/lists/* && \
    useradd -s /bin/bash -m jobmanager && \
    mkdir /jobmanager && \
    #
    # link sh to bash (for convenience)
    ln -fs /bin/bash /bin/sh && \
    #
    # create shortcut scripts
    echo "cat /jobmanager/processChainLog/processChainLog.log" > /usr/bin/jobmanager_processchainlog && \
    echo "http localhost:8080/processchainmanager/debug" > /usr/bin/jobmanager_debug && \
    echo "http localhost:8080/processchainmanager/nodes" > /usr/bin/jobmanager_nodes && \
    echo "curl localhost:8080/metrics" > /usr/bin/jobmanager_metrics && \
    echo "http POST localhost:8080 < /jobmanager/examples/cp.json" > /usr/bin/jobmanager_example_cp && \
    echo "http POST localhost:8080 < /jobmanager/examples/bornheim_1file.json" > /usr/bin/jobmanager_example_bornheim_1file && \
    chmod +x /usr/bin/jobmanager_* && \
    #
    # create global bash history file
    touch /.bash_history && \
    chmod go+rw /.bash_history && \
    #
    # create global HTTPIE config file
    mkdir /.httpie && echo "{}" > /.httpie/config.json && \
    chmod -R go+rwXs /.httpie && \
    #
    # put all shortcut scripts into bash history (for convenience)
    for p in $( ls /usr/bin/jobmanager_* ); do echo $p; done >> /.bash_history

# copy binaries, examples, and config
# COPY examples /jobmanager/examples
COPY build/install/jobmanager3 /jobmanager
COPY conf /jobmanager/conf
RUN chown -R jobmanager:root /jobmanager && \
    chmod -R g+rw /jobmanager
WORKDIR /jobmanager

USER jobmanager

ENV JAVA_OPTS="-Xmx4096m -Xms1024m -Dvertx.disableDnsResolver=true"

CMD ["bin/jobmanager3"]
