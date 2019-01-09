#!/usr/bin/env bash

set -xe

docker pull {{ config["setups.default.docker.image"] }}

docker run -d --name jobmanager --restart always \
  -p 5701:5701 -p {{ config["jobmanager.cluster.publicPort"] }}:{{ config["jobmanager.cluster.port"] }} \
  -e "JOBMANAGER_CLUSTER_PUBLICHOST={{ ipAddress }}" \
  -e "JOBMANAGER_CLUSTER_MEMBERS=[\"{{ config["jobmanager.cluster.publicHost"] }}\"]" \
  -e "JOBMANAGER_CLUSTER_INTERFACES=[\"172.*.*.*\"]" \
  -e "JOBMANAGER_AGENT_ID={{ agentId }}" \
  -e "JOBMANAGER_AGENT_CAPABILITIES=[{% for cap in agentCapabilities %}\"{{ cap }}\"{% if not loop.last %},{% endif %}{% endfor %}]" \
  -v /data:/data \
  {{ config["setups.default.docker.image"] }}
