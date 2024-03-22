#!/usr/bin/env bash

set -xe

docker pull {{ config["setups.default.docker.image"] }}

docker run -d --name steep --restart=on-failure \
  -p 5701:5701 -p {{ config["steep.cluster.eventBus.publicPort"] }}:{{ config["steep.cluster.eventBus.port"] }} \
  -e "STEEP_CLUSTER_EVENTBUS_PUBLICHOST={{ ipAddress }}" \
  -e "STEEP_CLUSTER_HAZELCAST_PUBLICADDRESS={{ ipAddress }}:5701" \
  -e "STEEP_CLUSTER_HAZELCAST_PORT=5701" \
  -e "STEEP_CLUSTER_HAZELCAST_MEMBERS=[\"{{ config["steep.cluster.hazelcast.publicAddress"] }}\"]" \
  -e "STEEP_CLUSTER_HAZELCAST_INTERFACES=[\"172.*.*.*\"]" \
  -e "STEEP_CLUSTER_HAZELCAST_TCPENABLED=true" \
  -e "STEEP_AGENT_ID={{ agentId }}" \
  -e "STEEP_AGENT_CAPABILITIES=[{% for cap in agentCapabilities %}\"{{ cap }}\"{% if not loop.last %},{% endif %}{% endfor %}]" \
  -e "STEEP_AGENT_AUTOSHUTDOWNTIMEOUT={% if setup.autoShutdownTimeout is empty %}{{ config["setups.default.agent.autoShutdownTimeout"] }}{% else %}{{ setup.autoShutdownTimeout }}{% endif %}" \
  -e "STEEP_SCHEDULER_ENABLED=false" \
  {{ config["setups.default.docker.image"] }}
