#!/bin/sh
set -eu

if [ ! -s /run/gridiq/cache/redis-password ]; then
  umask 077
  temporary=$(mktemp /run/gridiq/cache/password.XXXXXX)
  if [ -n "${GRIDIQ_REDIS_PASSWORD:-}" ]; then
    printf '%s' "$GRIDIQ_REDIS_PASSWORD" > "$temporary"
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$temporary"
  fi
  chmod 0444 "$temporary"
  mv "$temporary" /run/gridiq/cache/redis-password
fi

password_hash=$(sha256sum /run/gridiq/cache/redis-password | cut -d ' ' -f 1)
printf 'user default on #%s ~* &* +@all\n' "$password_hash" > /tmp/gridiq.acl
chmod 0444 /tmp/gridiq.acl
exec docker-entrypoint.sh redis-server --aclfile /tmp/gridiq.acl --appendonly yes
