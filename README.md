JobManager v3
=============

This is the third major version of the JobManager.

**Key features:**

* *Dynamic and scalable workflow management* without apriori ~~design-time~~
  runtime-time knowledge through incremental conversion of workflow graphs to
  so-called process chains
* Support for cyclic workflow graphs (i.e. *recursive for-each actions*)
* Optimized *process chain scheduling* through parallelization and distribution
  to multiple agents (i.e. JobManager instances running in the Cloud or in a
  cluster)
* *Automatic failover:* crashed workflows can be resumed without loss of
  information
* *Service metadata* allows process services with *arbitrary interfaces* to be
  integrated/executed
* *Rule-based* on-line modification of process chains
* Built-in runtime environments for *executable binaries* and *Docker*.
* *Plugins:*
  * An *output adapter* plugin modifies the way the agents collect process
    chain results
  * A *runtime* plugin adds a custom runtime environment (e.g. Python,
    AWS Lambda, Web Processing Services).
* Database back-ends:
  * In-memory
  * PostgreSQL
  * MongoDB
* Tight integration with *Kubernetes* enables optimized resource usage through
  *auto-scaling*
* *OpenStack Cloud Connector* allows virtual machines hosting additional
  JobManager agents to be created on demand
* Endpoint providing metrics to *Prometheus*
* REST-like *HTTP interface*
* Web-based *user interface* for monitoring
* Asynchronous event-driven architecture
* Very high test coverage

Building and running
--------------------

    ./gradlew build
    ./gradlew run

Building and running the Docker image
-------------------------------------

    docker build -t jobmanager3 .
    docker run --name jobmanager3 --rm -p 8080:8080 \
      -e JOBMANAGER_HTTP_HOST=0.0.0.0 jobmanager3
