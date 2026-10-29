#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$PROJECT_DIR/server"
PID_FILE="$SERVER_DIR/.dev-server.pid"
WRAPPER_PID_FILE="$SERVER_DIR/.dev-server.wrapper.pid"
CONSOLE_FIFO="$SERVER_DIR/.console.fifo"
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
        stop_wrapper
        return
    fi

    echo "Paper wird sauber beendet (PID $pid) ..."
    kill -TERM "$pid"
    for _ in {1..60}; do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_FILE"
            stop_wrapper
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

stop_wrapper() {
    local wrapper_pid=""
    if [[ -f "$WRAPPER_PID_FILE" ]]; then
        wrapper_pid="$(tr -dc '0-9' < "$WRAPPER_PID_FILE")"
    fi
    if [[ -n "$wrapper_pid" ]] && kill -0 "$wrapper_pid" 2>/dev/null; then
        kill -TERM "$wrapper_pid" 2>/dev/null || true
        for _ in {1..25}; do
            kill -0 "$wrapper_pid" 2>/dev/null || break
            sleep 0.2
        done
    fi
    rm -f "$WRAPPER_PID_FILE" "$CONSOLE_FIFO"
}

start_server() {
    local current_pid jar wrapper_pid
    current_pid="$(read_pid || true)"
    if [[ -n "$current_pid" ]] && is_managed_server "$current_pid"; then
        echo "Der Server läuft bereits (PID $current_pid)." >&2
        exit 1
    fi

    jar="$(paper_jar)"
    mkdir -p "$SERVER_DIR/logs" "$SERVER_DIR/plugins"
    touch "$CONSOLE_LOG"

    if [[ ! -f "$SERVER_DIR/eula.txt" ]] || ! grep -Eq '^eula=true[[:space:]]*$' "$SERVER_DIR/eula.txt"; then
        echo "Fehler: Setze zuerst eula=true in $SERVER_DIR/eula.txt." >&2
        exit 1
    fi

    rm -f "$PID_FILE"
    stop_wrapper

    echo "Paper wird mit $MIN_MEMORY bis $MAX_MEMORY RAM gestartet ..."
    nohup bash -c '
        set -euo pipefail
        fifo=$1
        pid_file=$2
        log=$3
        min=$4
        max=$5
        jar=$6
        cd "$7"

        rm -f "$fifo"
        mkfifo -m 600 "$fifo"
        holder=""
        java_pid=""
        cleanup() {
            [[ -n "$holder" ]] && kill "$holder" 2>/dev/null || true
            if [[ -z "$java_pid" ]] || ! kill -0 "$java_pid" 2>/dev/null; then
                rm -f "$fifo"
            fi
        }
        trap cleanup EXIT

        # Keep a writer open so the server does not see EOF between commands.
        (
            exec 3>"$fifo"
            exec sleep 2147483647
        ) &
        holder=$!
        java -Xms"$min" -Xmx"$max" -jar "$jar" --nogui <"$fifo" >>"$log" 2>&1 &
        java_pid=$!
        printf "%s\n" "$java_pid" > "$pid_file"
        set +e
        wait "$java_pid"
        status=$?
        exit "$status"
    ' bash "$CONSOLE_FIFO" "$PID_FILE" "$CONSOLE_LOG" "$MIN_MEMORY" "$MAX_MEMORY" "$(basename "$jar")" "$SERVER_DIR" \
        >/dev/null 2>&1 &
    wrapper_pid=$!
    printf '%s\n' "$wrapper_pid" > "$WRAPPER_PID_FILE"

    current_pid=""
    for _ in {1..25}; do
        current_pid="$(read_pid || true)"
        if [[ -n "$current_pid" ]] && is_managed_server "$current_pid"; then
            break
        fi
        sleep 0.2
    done
    if ! is_managed_server "$current_pid"; then
        echo "Fehler: Paper konnte nicht gestartet werden. Letzte Logzeilen:" >&2
        tail -n 40 "$CONSOLE_LOG" >&2 || true
        rm -f "$PID_FILE"
        stop_wrapper
        exit 1
    fi

    echo "Server läuft mit PID $current_pid. Log: $CONSOLE_LOG"
    echo "Konsole: $0 console"
}

attach_console() {
    local pid tail_pid line

    detach_console() {
        [[ -n "${tail_pid:-}" ]] && kill "$tail_pid" 2>/dev/null || true
        [[ -n "${tail_pid:-}" ]] && wait "$tail_pid" 2>/dev/null || true
        echo
        if is_managed_server "$(read_pid || true)"; then
            echo "Konsole getrennt. Der Server läuft weiter."
        else
            echo "Konsole getrennt."
        fi
        exit 0
    }

    pid="$(read_pid || true)"
    if [[ -z "$pid" ]] || ! is_managed_server "$pid"; then
        echo "Kein durch dieses Skript gestarteter Server läuft." >&2
        exit 1
    fi
    if [[ ! -p "$CONSOLE_FIFO" ]]; then
        echo "Fehler: Der laufende Server hat keine anschließbare Konsole." >&2
        echo "Beende ihn und starte ihn mit '$0 start' neu." >&2
        exit 1
    fi

    mkdir -p "$SERVER_DIR/logs"
    touch "$CONSOLE_LOG"
    echo "Mit der Serverkonsole verbunden (PID $pid)."
    echo "Strg+C oder detach trennt nur diese Sitzung. stop beendet den Server."

    tail -n 40 -F "$CONSOLE_LOG" &
    tail_pid=$!
    trap detach_console INT
    trap '' PIPE
    exec 3>"$CONSOLE_FIFO"

    while IFS= read -r line; do
        if [[ "$line" == "detach" ]]; then
            detach_console
        fi
        if ! printf '%s\n' "$line" >&3; then
            echo "Serverkonsole wurde geschlossen." >&2
            kill "$tail_pid" 2>/dev/null || true
            wait "$tail_pid" 2>/dev/null || true
            exit 1
        fi
    done
    detach_console
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
