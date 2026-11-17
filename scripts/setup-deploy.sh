#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 -m venv "$PROJECT_DIR/.deploy-venv"
"$PROJECT_DIR/.deploy-venv/bin/python" -m pip install -r "$PROJECT_DIR/scripts/deploy-requirements.txt"
if [[ ! -f "$PROJECT_DIR/deploy/live.json" ]]; then
    cp "$PROJECT_DIR/deploy/live.example.json" "$PROJECT_DIR/deploy/live.json"
    chmod 600 "$PROJECT_DIR/deploy/live.json"
fi
echo 'Deployment bereit. Verbindung in deploy/live.json prüfen; Passwörter werden verdeckt abgefragt.'
