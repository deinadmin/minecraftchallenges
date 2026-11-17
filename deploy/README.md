# Lokaler Entwicklungsserver → Kinetic Live

Die Skripte bauen und testen das Plugin, erstellen einen unveränderlichen lokalen Snapshot und
übertragen ihn per SFTP in ein Staging-Verzeichnis. Jede hochgeladene Datei wird vor dem Austausch
per SHA-256 geprüft. Ein Plugin-Update ersetzt ausschließlich `plugins/MinecraftChallenges.jar`.
Ein Komplett-Ersatz übernimmt Welten, Spielerdaten, alle Plugins und deren Einstellungen.

## Bereits eingerichtet

`deploy/live.json` enthält die mitgeteilten SFTP-Daten. Server-ID, Startdatei, Spielport und Java-Version
wurden am 29.10.2026 im Kinetic-Panel überprüft: `18599ff1`, `server.jar`, `25567`, Java 25.
Die Datei ist von Git ausgeschlossen und enthält kein Passwort. `live.example.json` ist die Vorlage.
Die SFTP-Abhängigkeit liegt separat in `.deploy-venv/`; auf einem neuen Rechner einmal ausführen:

```bash
./scripts/setup-deploy.sh
```

Bei der ersten Verbindung wird der SSH-Host-Fingerprint angezeigt und nach Bestätigung lokal in
`deploy/known_hosts` gespeichert. Das SFTP-Passwort wird verdeckt abgefragt und nicht gespeichert.
Optional unterstützt die Konfiguration `private_key`; alternativ kann das Passwort über die
Umgebungsvariable `KINETIC_SFTP_PASSWORD` bereitgestellt werden.

## Plugin aktualisieren

```bash
./scripts/deploy-live.sh plugin --dry-run
./scripts/deploy-live.sh plugin
```

Ohne `--auto` lädt das Skript die neue JAR zuerst hoch. Anschließend fordert es dazu auf, den
Live-Server **im Panel sauber zu stoppen und auf offline zu warten**. Erst nach der Eingabe
`GESTOPPT kinetic-live` wird die aktive JAR ausgetauscht. Danach den Server im Panel starten.
Keine Welten, Inventare, Coins oder Live-Konfigurationen werden durch ein Plugin-Update ersetzt.
Kein `/reload` verwenden.

## Lokalen Server vollständig live übernehmen

```bash
./scripts/deploy-live.sh replace --dry-run
./scripts/deploy-live.sh replace
```

Der tatsächliche Komplett-Upload stoppt den vom Projekt verwalteten lokalen Dev-Server und prüft
zusätzlich die Welt-Dateisperren. Ein anders gestarteter lokaler Server muss vorher selbst sauber
beendet werden. Der lokale Server bleibt nach dem Snapshot gestoppt und kann anschließend mit
`./scripts/dev-server.sh start` wieder gestartet werden.

Nach dem Upload den Live-Server im Panel stoppen. Die Eingabe `ERSETZEN kinetic-live` bestätigt
den Offline-Zustand und den vollständigen Ersatz. Alte Live-Einträge werden in einen Sicherungsordner
verschoben; veraltete Plugins/Welten bleiben damit nicht aktiv neben dem neuen Stand liegen.
Bei einem Fehler während des Austauschs versucht das Skript, die bereits verschobenen Einträge
zurückzusetzen. Bei Verbindungsabbrüchen den Live-Server erst nach Prüfung des Sicherungsordners starten.

Die lokale `paper-*.jar` wird unter dem konfigurierten Startnamen `server.jar` hochgeladen.
Kinetics JVM-Startparameter und Java-Auswahl liegen außerhalb des Datei-Uploads. Der zugewiesene
Live-Spielport sowie vorhandene Live-Netzwerk-/Management-Einstellungen aus `server.properties`
bleiben erhalten; `minecraft_port` ist der explizite Port für die Erstinstallation.

Ausgelassen werden generierte Logs, Crashberichte, Paper-Bibliotheken/Caches/Versions-Caches,
`plugins/.paper-remapped`, lokale Prozess-/FIFO-Dateien, `session.lock` und `.DS_Store`.
Diese Dateien sind keine Spielstände und werden bei Bedarf neu erzeugt. Vorhandene lokale
SMP-Backups werden mit übertragen. Symlinks und sonstige Spezialdateien werden abgelehnt.

## Automatisches Stoppen und Starten

Die dokumentierte Kinetic Client-API wird optional mit `--auto` verwendet:

```bash
./scripts/deploy-live.sh plugin --auto
./scripts/deploy-live.sh replace --auto
```

Dafür im Kinetic-Panel unter **API credentials** einen Client-API-Key anlegen. Der vollständige
`ptlc_...`-Key wird beim Aufruf verdeckt abgefragt oder aus `KINETIC_API_KEY` gelesen. Er wird nicht
in Konfigurationen, Release-Manifeste oder Logs geschrieben. Die API verwendet die Berechtigungen
des Kontos/Unterbenutzers; benötigt werden Serverstatus sowie Start-/Stop-Rechte.
Das Skript legt selbst keine API-Keys an und verändert keine Kontoberechtigungen.

Im automatischen Modus wird vor dem Dateiaustausch sauber gestoppt, auf den dokumentierten
Offline-Status gewartet und dieser erneut geprüft. Ein Timeout bricht ab; es wird kein harter Kill
verwendet. Nach erfolgreichem Austausch wird gestartet und auf den laufenden Prozess gewartet.
Das ist noch kein Nachweis, dass Minecraft fertig geladen hat: danach die Konsole prüfen.
Der Komplett-Ersatz verlangt weiterhin die Bestätigung `ERSETZEN kinetic-live`.

Für bewusst nichtinteraktive Aufrufe nach vorherigem Stop bzw. mit API-Steuerung:

```bash
./scripts/deploy-live.sh plugin --live-stopped
./scripts/deploy-live.sh replace --live-stopped --confirm-replace kinetic-live
./scripts/deploy-live.sh replace --auto --confirm-replace kinetic-live
```

`--live-stopped` ist eine Bestätigung des Bedieners und keine technische Statusprüfung. Für eine
technische Prüfung den API-Modus verwenden. Eine Vorschau mit `--dry-run` verbindet sich weder
mit SFTP noch mit der API und stoppt keinen Server.

## Sicherungen und Rollback

Jeder Austausch erhält eine eigene Release-ID, beispielsweise `20261029T180000Z-1234abcd`.
Die Sicherungen liegen im SFTP-Serverordner unter:

```text
.minecraftchallenges-deploy/releases/<Release-ID>/
  manifest.json   # Dateihashes, ersetzte Einträge und Status
  previous/       # vorheriger Live-Stand
  new/            # noch nicht aktivierte Upload-Dateien
  failed/         # bei Fehlern wieder aus dem Live-Verzeichnis entfernte neue Dateien
```

```bash
./scripts/deploy-live.sh rollback <Release-ID>
./scripts/deploy-live.sh rollback <Release-ID> --auto
```

Auch beim Rollback muss Live offline sein. Der aktuelle Stand wird dabei als weiteres Release
gesichert, bevor die frühere Sicherung zurückverschoben wird. Ein Komplett-Rollback setzt auch die
Spielerfortschritte auf den gesicherten Stand zurück. Die alte Sicherung wird dabei verbraucht;
der unmittelbar vorherige Stand bleibt im neuen Rollback-Release erhalten.

Die Sicherungen belegen Speicherplatz auf demselben Server. Sie werden nicht automatisch gelöscht
und ersetzen keine externen Backups. Ein Lock verhindert parallele Skript-Deployments. Nach einem
Prozessabbruch kann `.minecraftchallenges-deploy/lock` verbleiben: erst sicherstellen, dass kein
anderer Deploy läuft und der Dateistand konsistent ist. Bei einem Abbruch ausschließlich während
des Uploads kann der Lock mit `--recover-lock <abgebrochene-Release-ID>` gesichert werden.
Das Skript lehnt die Wiederherstellung ab, sobald Manifest oder Sicherungsverzeichnisse auf einen
begonnenen Austausch hinweisen. Der Lock wird archiviert; unvollständige Upload-Dateien bleiben erhalten.
Beispiel nach geprüftem Offline-Zustand und ohne weiteren laufenden Deploy:

```bash
./scripts/deploy-live.sh replace --recover-lock <abgebrochene-Release-ID>
```
Bei einem harten Verbindungs-/Prozessabbruch dienen Manifest und `previous/` zur Wiederherstellung.

## Tests

```bash
python3 -m unittest discover -s scripts/tests -v
./gradlew build
```

Die Deployment-Tests verwenden ein lokales SFTP-Testverzeichnis und simulierte API-Antworten.
Sie prüfen u. a. Komplett-Ersatz, Rollback, Fehler mitten im Austausch, beschädigte Uploads,
Live-Port-Erhalt, Dateisperren und API-Stop-/Start-Status. Sie verändern keinen echten Live-Server.

API-Dokumentation: https://kineticpanel.net/documentation (im angemeldeten Panel).
Verwendet werden ausschließlich `GET /api/client/servers/{server}/resources` und
`POST /api/client/servers/{server}/power` mit den Signalen `stop`/`start`,
`Authorization: Bearer ...` und `Accept: application/json`. Dokumentationsstand: Panel
`v26.28.4-0.89.1`, am 29.10.2026 in Chrome geprüft. Die API ist vom Anbieter als Beta gekennzeichnet;
unbekannte Statusantworten führen zum sicheren Abbruch.
