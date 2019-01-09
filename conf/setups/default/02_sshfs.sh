#!/usr/bin/env bash

set -xe

apt-get install -y sshfs
mkdir /data

set +x
echo {{ config["setups.default.sshfs.password"] }} | sshfs -o password_stdin -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o allow_other,default_permissions {{ config["setups.default.sshfs.url"] }}:/data/ /data
