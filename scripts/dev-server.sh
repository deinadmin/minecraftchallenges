#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$PROJECT_DIR/server"
PID_FILE="$SERVER_DIR/.dev-server.pid"
CONSOLE_LOG="$SERVER_DIR/logs/dev-console.log"
MIN_MEMORY="${MC_MIN_MEMORY:-2G}"
MAX_MEMORY="${MC_MAX_MEMORY:-4G}"

paper_jar() {
    local jars=("$SERVER_DIR"/paper-*.jar)
    if [[ ! -e "${jars[0]}" ]]; then
        echo "Fehler: Keine paper-*.jar in $SERVER_DIR gefunden." >&2
        exit 1
    fi
    if (( ${#jars[@]} > 1 )); then
        echo "Fehler: Mehrere Paper-JARs gefunden. Bitte nur eine im Serverordner behalten." >&2
        exit 1
    fi
    printf '%s\n' "${jars[0]}"
}

read_pid() {
    [[ -f "$PID_FILE" ]] && tr -dc '0-9' < "$PID_FILE"
}

is_managed_server() {
    local pid="$1"
    [[ -n "$pid" ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    ps -p "$pid" -o command= 2>/dev/null | grep -q -- 'paper-.*\.jar'
}

stop_server() {
    local pid
    pid="$(read_pid || true)"

    if [[ -z "$pid" ]]; then
        echo "Kein durch dieses Skript gestarteter Server läuft."
        return
    fi
    if ! is_managed_server "$pid"; then
        echo "Veraltete PID-Datei entfernt; der zugehörige Paper-Prozess läuft nicht mehr."
        rm -f "$PID_FILE"
        return
    fi

    echo "Paper wird sauber beendet (PID $pid) ..."
    kill -TERM "$pid"
    for _ in {1..60}; do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_FILE"
            echo "Server wurde beendet."
            return
        fi
        sleep 1
    done

    echo "Fehler: Der Server wurde nach 60 Sekunden nicht beendet." >&2
    echo "Prüfe $CONSOLE_LOG und beende ihn manuell; es wird kein harter Kill ausgeführt." >&2
    exit 1
}

build_plugin() {
    mkdir -p "$SERVER_DIR/plugins"
    if [[ ! -w "$SERVER_DIR/plugins" ]]; then
        echo "Fehler: $SERVER_DIR/plugins ist für deinen Benutzer nicht beschreibbar." >&2
        echo "Der Server wurde vermutlich einmal mit sudo gestartet." >&2
        echo "Führe einmal aus:" >&2
        echo "  sudo chown -R $(id -un):$(id -gn) '$SERVER_DIR'" >&2
        exit 1
    fi
    echo "Plugin wird gebaut und nach server/plugins installiert ..."
    "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" build
}

start_server() {
    local current_pid jar
    current_pid="$(read_pid || true)"
    if [[ -n "$current_pid" ]] && is_managed_server "$current_pid"; then
        echo "Der Server läuft bereits (PID $current_pid)." >&2
        exit 1
    fi

    jar="$(paper_jar)"
    mkdir -p "$SERVER_DIR/logs" "$SERVER_DIR/plugins"

    if [[ ! -f "$SERVER_DIR/eula.txt" ]] || ! grep -Eq '^eula=true[[:space:]]*$' "$SERVER_DIR/eula.txt"; then
        echo "Fehler: Setze zuerst eula=true in $SERVER_DIR/eula.txt." >&2
        exit 1
    fi

    echo "Paper wird mit $MIN_MEMORY bis $MAX_MEMORY RAM gestartet ..."
    (
        cd "$SERVER_DIR"
        nohup java -Xms"$MIN_MEMORY" -Xmx"$MAX_MEMORY" -jar "$(basename "$jar")" --nogui \
            >> "$CONSOLE_LOG" 2>&1 &
        printf '%s\n' "$!" > "$PID_FILE"
    )

    sleep 2
    current_pid="$(read_pid || true)"
    if ! is_managed_server "$current_pid"; then
        echo "Fehler: Paper konnte nicht gestartet werden. Letzte Logzeilen:" >&2
        tail -n 40 "$CONSOLE_LOG" >&2 || true
        rm -f "$PID_FILE"
        exit 1
    fi

    echo "Server läuft mit PID $current_pid. Log: $CONSOLE_LOG"
}

show_logs() {
    mkdir -p "$SERVER_DIR/logs"
    touch "$CONSOLE_LOG"
    tail -n 80 -F "$CONSOLE_LOG"
}

case "${1:-restart}" in
    build)
        build_plugin
        ;;
    start)
        start_server
        ;;
    stop)
        stop_server
        ;;
    restart)
        stop_server
        build_plugin
        start_server
        ;;
    logs)
        show_logs
        ;;
    *)
        echo "Verwendung: $0 {build|start|stop|restart|logs}" >&2
        exit 2
        ;;
esac
