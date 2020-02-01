Steep
=====

[![Actions Status](https://github.com/steep-wms/steep/workflows/Java%20CI/badge.svg)](https://github.com/steep-wms/steep/actions)

Scientific Workflow Management System

**Key features:**

* *Dynamic and scalable workflow management* without apriori ~~design-time~~
  runtime-time knowledge through incremental conversion of workflow graphs to
  so-called process chains
* Support for cyclic workflow graphs (i.e. *recursive for-each actions*)
* Optimized *process chain scheduling* through parallelization and distribution
  to multiple agents (i.e. Steep instances running in the Cloud or in a
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
  Steep agents to be created on demand
* Endpoint providing metrics to *Prometheus*
* REST-like *HTTP interface*
* Web-based *user interface* for monitoring
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
