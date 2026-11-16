#!/bin/bash
# No JVM per probe: check the application's HTTP readiness with Bash builtins.
set -eu
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health/readiness HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
IFS= read -r -t 2 status <&3
case "$status" in
  *' 200 '*) exit 0 ;;
  *) exit 1 ;;
esac
