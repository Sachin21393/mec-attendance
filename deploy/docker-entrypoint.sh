#!/bin/sh
set -eu

if [ -n "${DATABASE_URL:-}" ] && [ -z "${DB_URL:-}" ]; then
  stripped="${DATABASE_URL#postgres://}"
  stripped="${stripped#postgresql://}"
  creds="${stripped%%@*}"
  host_db="${stripped#*@}"

  DB_USER_PARSED="${creds%%:*}"
  DB_PASS_PARSED="${creds#*:}"

  host_port="${host_db%%/*}"
  db_part="${host_db#*/}"

  case "$host_port" in
    *:*)
      pg_host="${host_port%%:*}"
      pg_port="${host_port#*:}"
      ;;
    *)
      pg_host="$host_port"
      pg_port="5432"
      ;;
  esac

  case "$pg_host" in
    *.render.com)
      case "$db_part" in
        *\?*) ;;
        *) db_part="${db_part}?sslmode=require" ;;
      esac
      ;;
  esac

  export DB_URL="jdbc:postgresql://${pg_host}:${pg_port}/${db_part}"
  export DB_USERNAME="${DB_USERNAME:-$DB_USER_PARSED}"
  export DB_PASSWORD="${DB_PASSWORD:-$DB_PASS_PARSED}"
fi

if [ -n "${PORT:-}" ]; then
  export SERVER_PORT="$PORT"
fi

exec java -XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=110m -jar /app/app.jar "$@"
