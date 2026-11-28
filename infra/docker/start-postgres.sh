#!/bin/sh
set -eu

if [ ! -s /run/gridiq/db/db-password ]; then
  umask 077
  temporary=$(mktemp /run/gridiq/db/password.XXXXXX)
  if [ -n "${GRIDIQ_DB_PASSWORD:-}" ]; then
    printf '%s' "$GRIDIQ_DB_PASSWORD" > "$temporary"
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$temporary"
  fi
  chmod 0444 "$temporary"
  mv "$temporary" /run/gridiq/db/db-password
fi

if [ "${GRIDIQ_APP_PROFILE:-dev}" = dev ] && [ ! -s /run/gridiq/db/jwt-local-secret ]; then
  umask 077
  temporary=$(mktemp /run/gridiq/db/signing.XXXXXX)
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$temporary"
  chmod 0444 "$temporary"
  mv "$temporary" /run/gridiq/db/jwt-local-secret
fi

export POSTGRES_PASSWORD
POSTGRES_PASSWORD=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
exec docker-entrypoint.sh postgres
