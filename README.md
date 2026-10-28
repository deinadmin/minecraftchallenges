# MinecraftChallenges

Ein erweiterbares Challenge-Plugin für **Paper 26.3** (Minecraft 26.3, Java 25), komplett ohne Client-Mods.

## Enthalten

- geschützte Lobby im Adventure-Modus
- die vorhandene Welt `lobby` wird automatisch als feste Lobby-Welt geladen
- in der Lobby bleiben Uhrzeit dauerhaft auf Tag und Wetter dauerhaft klar
- unverlierbarer Kompass in Inventarslot 5
- nicht veränderbares 5×9-Challenge-GUI mit Glasrahmen
- erste Challenge **„Mob-Jagd“**: fünf Minuten lang persönliche Zufalls-Mobs finden und töten
- zehn häufige Ziel-Mobs, persönliche Bossbar und drei feste Skips pro Spieler
- Admin-/Mod-Steuerung, Ready-Up mit 30-Sekunden-Vorbereitung und 10-Sekunden-Countdown
- Rangformatierung für Admins, Mods und Spieler in Chat und Tab-Liste
- Admin-Steuerungsmenü zum Starten der Runde oder vollständigen Neugenerieren der Challenge-Welt
- gespeicherte Zeitpresets für 5, 15 oder 30 Minuten sowie 1 oder 2 Stunden
- 40-Blöcke-Warteradius, animierte Border-Öffnung beim Countdown und zufällige sichere Endpositionen
- `/lobby` bringt Spieler jederzeit zurück in die feste Lobby-Welt
- `/edit` schaltet für Admins in `lobby` zwischen geschütztem Adventure-Modus und speicherbarem Kreativ-Bearbeitungsmodus um
- während einer Neugenerierung warten Map-Spieler mit einem fixierten Blaze Powder in Slot 5 in der Lobby
- pro Challenge maximal eine laufende Instanz
- automatisch neu erzeugte, getrennte Survival-Welt
- deaktivierte Dimensionsportale, damit niemand in die Hauptwelt-Dimensionen entkommt
- Nachjoinen in laufende Runden als Spectator
- Rückkehr in die Lobby und Weltbereinigung nach Rundenende
- ausgegraute Plätze für zukünftige Challenges
- Admin-Befehle und konfigurierbarer Lobby-Spawn
- konfigurierbare Druckplatten starten dynamische, kollisionsgeprüfte Lobby-Jump'n'Runs
- Crafting-Randomizer mit pro Runde fester Rezeptmischung, persönlichen Craft-Aufgaben und drei Skips
- Block-Randomizer mit zufälligem Item pro Block, eigener Nether-/End-Dimension und optionaler Stoppuhr bis zum Enderdrachen
- getrennte Challenge-Welten mit jeweils 20 Teilnehmerplätzen und unbegrenzt vielen Zuschauern
- **Eigene Challenges**: bis zu fünf von Spielern erstellte Challenges in frischen Zufallswelten
  - beim Erstellen wählbar: 13 Sonderregeln (z. B. Ein-Herz-Survival, Inventarzerfall, zufällige Drops/Rezepte, geteilte Herzen, nur ein Leben), Ziel (Enderdrache, Wither oder Überleben bis Zeitablauf), Zeitlimit und maximale Spielerzahl
  - Komparator in der Hotbar öffnet die Einstellungen; Ersteller, Mods und Admins ändern Regeln vor dem Start und starten die Runde
  - eigene Nether-/End-Dimension; verlassen alle Spieler die Challenge, wird sie nach 15 Sekunden samt Welt gelöscht und der Platz frei
  - sind alle fünf Plätze belegt, können Spieler ihre Challenge trotzdem einrichten und reihen sich in eine Warteschlange ein (Barriere in der Hotbar zum Verlassen, Position in der Actionbar); wird ein Platz frei, wird die Challenge des Ersten automatisch erstellt
  - Admins erhalten bei vollen Plätzen einen Zusatzplatz (bis zu 14 zusätzliche Challenges)

## Entwicklungsworkflow in Cursor / Visual Studio Code

Voraussetzung: JDK 25. Gradle wird über den enthaltenen Wrapper automatisch bereitgestellt.

```bash
./gradlew build
```

Jeder erfolgreiche Build erzeugt `build/libs/MinecraftChallenges.jar` und kopiert die Datei automatisch nach `server/plugins/MinecraftChallenges.jar`. Andere Plugins im Ordner bleiben unberührt.

In Cursor oder Visual Studio Code:

1. Projektordner öffnen.
2. `Cmd+Shift+B` drücken.
3. Der Standardtask **Minecraft: Build + Server neu starten** baut das Plugin, beendet einen zuvor durch diesen Flow gestarteten Paper-Server sauber und startet ihn wieder.

Weitere Tasks sind über **Terminal → Run Task…** verfügbar:

- `Minecraft: Nur bauen + Plugin installieren`
- `Minecraft: Server starten`
- `Minecraft: Server stoppen`
- `Minecraft: Server-Log anzeigen`

Dieselben Aktionen funktionieren im Terminal:

```bash
./scripts/dev-server.sh restart
./scripts/dev-server.sh stop
./scripts/dev-server.sh logs
```

Der Server muss als einzelne `paper-*.jar` direkt in `server/` liegen. Standardmäßig nutzt er 2–4 GB RAM. Andere Werte können vor dem Aufruf gesetzt werden:

```bash
MC_MIN_MEMORY=1G MC_MAX_MEMORY=3G ./scripts/dev-server.sh restart
```

Wichtig: Starte den Entwicklungsserver nie mit `sudo`. Falls `server/` bereits `root` gehört, repariere das einmalig mit:

```bash
sudo chown -R "$(id -un)":"$(id -gn)" ./server
```

Die Serverausgabe landet in `server/logs/dev-console.log` und im normalen Paper-Log.

## Einrichtung

1. Stelle dich an den gewünschten Lobby-Spawn.
2. Führe `/challenge setlobby` als Operator aus.
3. Spieler erhalten beim Join automatisch den Kompass.

## Befehle

| Befehl | Funktion | Recht |
|---|---|---|
| `/challenge menu` | Öffnet das Challenge-Menü | alle |
| `/challenge stop` | Beendet die laufende Runde | `minecraftchallenges.challenge.manage` |
| `/challenge setlobby` | Speichert den aktuellen Standort als Lobby | `minecraftchallenges.command.setlobby` |
| `/challenge reload` | Lädt `config.yml` neu | `minecraftchallenges.command.reload` |
| `/lobby` | Teleportiert den Spieler in die Lobby | alle |
| `/edit` | Aktiviert oder speichert und beendet den Lobby-Edit-Modus | `minecraftchallenges.command.edit` |
| `/jumpnrun trigger set` | Aktiviert die Druckplatte, auf die du schaust | `minecraftchallenges.command.jumpnrun` |
| `/jumpnrun trigger list` | Listet alle aktivierten Druckplatten | `minecraftchallenges.command.jumpnrun` |
| `/jumpnrun trigger reset` | Löscht alle Trigger und laufenden Strecken | `minecraftchallenges.command.jumpnrun` |
| `/jumpnrun spawn set/remove` | Verwaltet das Navigationsziel | `minecraftchallenges.command.jumpnrun` |

## Ränge und Permissions

- `minecraftchallenges.rank.admin` ist standardmäßig für Operatoren aktiv und umfasst sämtliche Verwaltungsrechte.
- `minecraftchallenges.rank.mod` umfasst ausschließlich `minecraftchallenges.challenge.manage` für Start, Stop, Zeit und Weltregeneration.
- Normale Spieler besitzen standardmäßig Menü-, Lobby- und Ready-Up-Rechte.

## Konfiguration

In `plugins/MinecraftChallenges/config.yml` können Weltname, Seed und Größe der Worldborder angepasst werden. `challenge.seed: 0` erzeugt bei jedem Start eine zufällige Welt. Solange noch kein `/challenge setlobby` ausgeführt wurde, wird automatisch der Spawnpunkt der Hauptwelt verwendet. Unter `jump-n-run` lassen sich Plattformmaterial, Starthöhe und Aufräumradius einstellen; die aktivierten Druckplatten werden dort automatisch gespeichert.

## Weitere Challenges ergänzen

Der Lebenszyklus liegt in `ChallengeManager`, die Darstellung in `ChallengeMenu`. Für mehrere echte Challenge-Typen sollte als nächster Schritt ein `Challenge`-Interface mit `start`, `stop`, `onJoinSpectator` und Event-Hooks eingeführt und im Manager registriert werden.
