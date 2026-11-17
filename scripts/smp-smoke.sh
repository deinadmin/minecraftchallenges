#!/usr/bin/env bash
# Checks the SMP economy, plots and NPCs on a fresh, disposable server; never touches server/ itself.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# "jar" instead of "build": build would install the plugin into server/plugins, possibly under a running dev server.
"$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" jar smpSmokeJar
SMOKE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/minecraftchallenges-smp-smoke.XXXXXX")"
for folder in libraries cache versions; do
    cp -R "$PROJECT_DIR/server/$folder" "$SMOKE_DIR/$folder"
done
jars=("$PROJECT_DIR"/server/paper-*.jar)
if [[ ${#jars[@]} -ne 1 || ! -f "${jars[0]}" ]]; then
    echo 'Expected exactly one cached Paper JAR in server/.' >&2
    exit 1
fi
cp "${jars[0]}" "$SMOKE_DIR/paper.jar"
cp "$PROJECT_DIR/server/eula.txt" "$SMOKE_DIR/eula.txt"
mkdir -p "$SMOKE_DIR/plugins"
cp "$PROJECT_DIR/build/libs/MinecraftChallenges.jar" "$SMOKE_DIR/plugins/"
cp "$PROJECT_DIR/build/smoke/SmpServerSmoke.jar" "$SMOKE_DIR/plugins/"
cat > "$SMOKE_DIR/server.properties" <<'PROPERTIES'
server-ip=127.0.0.1
server-port=0
online-mode=false
view-distance=3
simulation-distance=3
max-players=1
max-tick-time=120000
level-seed=12345
PROPERTIES
cd "$SMOKE_DIR"
echo "Disposable server and test log: $SMOKE_DIR"
"${MC_SMOKE_JAVA:-java}" -Dterminal.jline=false -Dterminal.ansi=false \
    -Dminecraftchallenges.smp-smoke=true -Xms1G -Xmx3G -jar paper.jar --nogui \
    > smoke-console.log 2>&1
if ! grep -q 'SMP_SMOKE_PASSED' smoke-console.log; then
    tail -n 80 smoke-console.log
    exit 1
fi
grep 'PASS:\|SMP_SMOKE_PASSED' smoke-console.log
