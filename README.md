<h1 align="center">
	<br>
	<br>
	<img width="500" src="https://steep-wms.github.io/images/steep-logo.svg" alt="Steep">
	<br>
	<br>
	<br>
</h1>

> A Scientific Workflow Management System made for the Cloud. https://steep-wms.github.io/

[![Apache License, Version 2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0) [![Actions Status](https://github.com/steep-wms/steep/workflows/Java%20CI/badge.svg)](https://github.com/steep-wms/steep/actions) [![Code Coverage](https://img.shields.io/codecov/c/github/steep-wms/steep)](https://codecov.io/gh/steep-wms/steep)

Getting started
---------------

Visit https://steep-wms.github.io/#download-and-get-started to download Steep and run your first workflow.

Documentation
-------------

Visit https://steep-wms.github.io/#documentation to view the documentation.

Building and running
--------------------

This project requires at least JDK 21.

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
