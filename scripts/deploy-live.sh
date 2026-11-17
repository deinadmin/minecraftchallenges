#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="$PROJECT_DIR/.deploy-venv/bin/python"
if [[ ! -x "$PYTHON" ]]; then PYTHON="${MC_DEPLOY_PYTHON:-python3}"; fi
exec "$PYTHON" "$PROJECT_DIR/scripts/deploy_live.py" "$@"
