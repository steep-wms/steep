#!/usr/bin/env bash

set -xe

apt-get update
apt-get install -y \
   apt-transport-https \
   ca-certificates \
   curl \
   software-properties-common

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | apt-key add -

add-apt-repository \
   "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
   $(lsb_release -cs) \
   stable"

apt-get update
apt-get install -y docker-ce

# finally, login to docker registry but do not print command
set +x
echo {{ config["setups.default.docker.password"] }} | docker login --username={{ config["setups.default.docker.username"] }} --password-stdin {{ config["setups.default.docker.registry"] }}
