JobManager v3
=============

This is the third major version of the JobManager.

Building and running
--------------------

    ./gradlew build
    ./gradlew run

Building and running the Docker image
-------------------------------------

    docker build -t jobmanager3 .
    docker run --name jobmanager3 --rm -p 8080:8080 \
      -e JOBMANAGER_HTTP_HOST=0.0.0.0 jobmanager3
