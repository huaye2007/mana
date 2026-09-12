#!/usr/bin/env bash
set -euo pipefail
# /src and /cache are read-only host mounts; all builds stay in the container.
mkdir -p /work/project /work/repository /results
tar -C /src --exclude=.m2 --exclude=target --exclude=.git -cf - . | tar -C /work/project -xf -
if [[ -d /cache ]]; then cp -a /cache/. /work/repository/; fi
cd /work/project
finish() {
  local status=$?
  mkdir -p /results/reports
  if [[ -d game-network-netty/target/surefire-reports ]]; then
    cp -a game-network-netty/target/surefire-reports/. /results/reports/
  fi
  find game-network-netty/target -maxdepth 1 -name 'load-*.json' -exec cp {} /results/ \; 2>/dev/null || true
  exit "$status"
}
trap finish EXIT
java -version 2>&1 | tee /results/environment.txt
uname -a | tee -a /results/environment.txt
printf 'nofile=' | tee -a /results/environment.txt
ulimit -n | tee -a /results/environment.txt
args=(-B -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=/work/repository)
if [[ "${OFFLINE:-0}" == 1 ]]; then args+=(-o); fi
if [[ "${MODE:-verify}" == load ]]; then
  mvn "${args[@]}" -Dtest=NetworkLoadIT -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dnetwork.load.protocol=${PROTOCOL:-tcp}" "-Dnetwork.load.connections=${CONNECTIONS:-10000}" \
    "-Dnetwork.load.seconds=${SECONDS_TO_RUN:-60}" \
    '-DargLine=-Xms512m -Xmx3g -XX:MaxDirectMemorySize=1g' test 2>&1 | tee /results/build.log
else
  mvn "${args[@]}" verify 2>&1 | tee /results/build.log
fi
if grep -q 'LEAK:.*release()' /results/build.log; then
  echo 'Netty leak detector reported an unreleased buffer' >&2
  exit 1
fi
