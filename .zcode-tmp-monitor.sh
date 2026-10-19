#!/bin/bash
# Relay-worker monitor: snapshots HEAD/dirty/java-procs/PR every 60s. Read-only wrt repo.
cd /c/Users/Administrator/Desktop/wt-c-settings
for i in $(seq 1 30); do
  ts=$(date +%H:%M:%S)
  head=$(git log --oneline -1 2>/dev/null | cut -c1-9)
  dirty=$(git status --porcelain 2>/dev/null | grep -v '.zcode-tmp' | wc -l)
  javas=$(powershell -Command "(Get-Process java -ErrorAction SilentlyContinue | Measure-Object).Count" 2>/dev/null | tr -d '\r')
  pr=$(gh pr list --head codex/c-settings-service-feedback-20261019 --state all --limit 1 2>/dev/null | head -1)
  echo "$ts HEAD=$head dirty=$dirty java=$javas PR=[$pr]"
  sleep 60
done
