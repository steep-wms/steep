<h1 align="center">
	<br>
	<br>
	<img width="500" src="https://steep-wms.github.io/images/steep-logo.svg" alt="Steep">
	<br>
	<br>
	<br>
</h1>

> A Scientific Workflow Management System made for the Cloud

[![Apache License, Version 2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0) [![Actions Status](https://github.com/steep-wms/steep/workflows/Java%20CI/badge.svg)](https://github.com/steep-wms/steep/actions)

Steep has the following fey features:

* *Dynamic and scalable workflow management* without apriori
  runtime-time knowledge through incremental conversion of workflow graphs to
  so-called process chains
* Support for cyclic workflow graphs (with *recursive for-each actions*)
* Optimized, capability-based *process chain scheduling* through
  parallelization and distribution to multiple agents (i.e. Steep instances
  running in the Cloud or in a cluster)
* *Automatic failover:* crashed workflows can be resumed without loss of
  information
* *Service metadata* allows processing services with *arbitrary interfaces*
  to be integrated/executed
* Built-in runtime environments for *executable binaries* and *Docker*.
* Can be extended with *plugins:*
  * *Output adapter* plugins modify the way agents collect process
    chain results
  * *Process chain adapter* plugins modify generated process chains before they
    are executed
  * *Runtime* plugins add custom runtime environments (e.g. Python,
    AWS Lambda, Web Processing Services).
* Supported database back-ends:
  * MongoDB
  * PostgreSQL
  * In-memory
* Tight integration with *Kubernetes* enables optimized resource usage through
  *auto-scaling*
* *OpenStack Cloud Connector* allows virtual machines hosting additional
  Steep agents to be created on demand
* REST-like *HTTP interface*
* Web-based *user interface* for monitoring
* Provides metrics to *Prometheus*
* Asynchronous event-driven architecture
* Very high test coverage

Building and running
--------------------

This project requires at least JDK 11.

    ./gradlew build
    ./gradlew run

Building and running the Docker image
-------------------------------------

    docker build -t steep .
    docker run --name steep --rm -p 8080:8080 \
      -e STEEP_HTTP_HOST=0.0.0.0 steep

License
-------

Steep is licensed under the
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0).

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
