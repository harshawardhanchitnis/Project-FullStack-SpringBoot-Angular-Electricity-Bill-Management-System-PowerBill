#!/bin/sh
set -eu

if [ ! -s /run/gridiq/ml/ml-api-key ]; then
  umask 077
  temporary=$(mktemp /run/gridiq/ml/key.XXXXXX)
  if [ -n "${GRIDIQ_ML_API_KEY:-}" ]; then
    printf '%s' "$GRIDIQ_ML_API_KEY" > "$temporary"
  else
    python -c "import secrets; print(secrets.token_hex(32), end='')" > "$temporary"
  fi
  chmod 0444 "$temporary"
  mv "$temporary" /run/gridiq/ml/ml-api-key
fi

exec python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --no-access-log
