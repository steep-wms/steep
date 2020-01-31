#!/usr/bin/env bash

set -xe

docker pull {{ config["setups.default.docker.image"] }}

docker run -d --name steep --restart always \
  -p 5701:5701 -p {{ config["steep.cluster.publicPort"] }}:{{ config["steep.cluster.port"] }} \
  -e "STEEP_CLUSTER_PUBLICHOST={{ ipAddress }}" \
  -e "STEEP_CLUSTER_MEMBERS=[\"{{ config["steep.cluster.publicHost"] }}\"]" \
  -e "STEEP_CLUSTER_INTERFACES=[\"172.*.*.*\"]" \
  -e "STEEP_AGENT_ID={{ agentId }}" \
  -e "STEEP_AGENT_CAPABILITIES=[{% for cap in agentCapabilities %}\"{{ cap }}\"{% if not loop.last %},{% endif %}{% endfor %}]" \
  -v /data:/data \
  {{ config["setups.default.docker.image"] }}
