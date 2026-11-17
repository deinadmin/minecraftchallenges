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
  - eigene Nether-/End-Dimension; laufende Runden ohne Zeitlimit werden nach fünf Minuten ohne Spieler samt Welt gelöscht, andere leere eigene Challenges nach 15 Sekunden
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
- `Minecraft: Server-Konsole öffnen`
- `Minecraft: Server-Log anzeigen`

Dieselben Aktionen funktionieren im Terminal:

```bash
./scripts/dev-server.sh restart
./scripts/dev-server.sh stop
./scripts/dev-server.sh console
./scripts/dev-server.sh logs
```

`console` verbindet sich mit der Konsole des laufenden Servers: Befehle wie `list` oder `say Hallo` einfach eintippen. `Strg+C` oder `detach` trennt nur die Sitzung, der Server läuft weiter; `stop` beendet ihn.

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
| `/keepalive` | Hält die laufende Challenge offen, auch wenn alle Spieler gehen (Welt und Spielerstände werden gespeichert). Ohne Keepalive endet eine Runde nach 30 Minuten, in denen alle Teilnehmer AFK oder offline sind. Nicht im SMP oder Speedrun. | `minecraftchallenges.command.keepalive` (Admin) |
| `/challenge setlobby` | Speichert den aktuellen Standort als Lobby | `minecraftchallenges.command.setlobby` |
| `/challenge reload` | Lädt `config.yml` neu | `minecraftchallenges.command.reload` |
| `/lobby` | Teleportiert den Spieler in die Lobby | alle |
| `/npc` | Öffnet die Lobby-NPC-Verwaltung | `minecraftchallenges.rank.admin` |
| `/edit` | Aktiviert oder speichert und beendet den Lobby-Edit-Modus | `minecraftchallenges.command.edit` |
| `/jumpnrun trigger set` | Aktiviert die Druckplatte, auf die du schaust | `minecraftchallenges.command.jumpnrun` |
| `/jumpnrun trigger list` | Listet alle aktivierten Druckplatten | `minecraftchallenges.command.jumpnrun` |
| `/jumpnrun trigger reset` | Löscht alle Trigger und laufenden Strecken | `minecraftchallenges.command.jumpnrun` |
| `/jumpnrun spawn set/remove` | Verwaltet das Navigationsziel | `minecraftchallenges.command.jumpnrun` |

## SMP: Wirtschaft, Grundstücke und NPCs

Das SMP ist ein eigener Spielmodus mit Coins. Alle Befehle sind englisch, alle Texte deutsch.

| Befehl | Funktion | Recht |
|---|---|---|
| `/shop` | Tagesangebote: neun zufällige Angebote, die jeden Tag um 0 Uhr (Europe/Berlin) wechseln. Baublöcke sind unbegrenzt kaufbar, alles andere einmal pro Spieler und Tag. | alle |
| `/shop shuffle` | Mischt die Tagesangebote sofort neu; der Wechsel um 0 Uhr bleibt. | `minecraftchallenges.command.shop` |
| `/sell` | Verkaufs-GUI: Items hineinlegen, Erlös sehen, bestätigen. Nicht Verkauftes kommt beim Schließen zurück. | alle |
| `/prices` | Aktuelle Verkaufskurse und die heute gesuchten Items. | alle |
| `/claim buy` | Kauft ein Grundstück (41×41 Blöcke) um die eigene Position, nach Vorschau und Bestätigung. | alle |
| `/claim upgrade` | Vergrößert das Grundstück um 10 Blöcke Radius (bis Radius 80). | Besitzer |
| `/claim trust\|untrust <Spieler>` | Erlaubt oder entzieht Bauen und Benutzen. | Besitzer |
| `/claim sell <Spieler> <Preis>` · `/claim transfer <Spieler>` | Verkauft oder verschenkt das Grundstück; der Empfänger bestätigt mit `/claim accept`. | Besitzer |
| `/claim info` · `list` · `border [toggle]` · `abandon` | Infos, eigene Grundstücke, Grenze anzeigen, aufgeben (50 % Erstattung). | alle |
| `/claim delete` | Löscht das Grundstück, auf dem man steht, ohne Erstattung. | `minecraftchallenges.command.claim-admin` |
| `/quest` · `/quest cancel` | Zeigt die laufende Quest oder bricht sie ab. | alle |
| `/smpnpc trader\|quest <Typ>\|reroll\|remove\|list` | Setzt, würfelt neu, entfernt und listet SMP-NPCs. | `minecraftchallenges.command.smpnpc` |

**Markt.** Jedes verkaufbare Item hat in `plugins/MinecraftChallenges/smp-values.yml` einen Referenzwert und eine
Markttiefe. `/sell` zahlt bei unberührtem Markt ein Sechstel des Werts; jeder Verkauf drückt den Kurs für alle, und
er erholt sich mit einer Halbwertszeit von 24 Stunden. Der Erlös wächst nur logarithmisch mit der Menge, Farmen
drucken also kein Geld. Täglich sind drei Items „gesucht“ und bringen pro Spieler für eine begrenzte Menge das
Doppelte. Kaufpreise liegen bei 85–125 % des Werts (Wanderhändler 70–85 %), Verkaufen zahlt nie mehr als die Hälfte
des günstigsten Kaufpreises. Dieselbe Datei legt die Pools des Daily Shops fest. Änderungen greifen nach einem Neustart.

**Grundstücke.** Quadratisch um die Kaufposition, über die volle Welthöhe, nur in der SMP-Oberwelt. Fremde können
dort nur herumlaufen und kämpfen (PvP bleibt an); Explosionen, Feuer, Flüssigkeiten, Kolben, Werfer und Trichter
wirken nicht über die Grenze. Grundstücke halten Abstand zum Spawn, zu fremden Grundstücken, zu Festungen mit
Endportal und zu Quest-NPCs. Die Grenze erscheint als farbige Partikellinie, nur für den Spieler in der Nähe
(grün = eigenes, blau = vertraut, rot = fremd). Preise, Größen und Abstände stehen unter `smp.claims` in `config.yml`.

**NPCs.** Bis zu drei Wanderhändler stehen an zufälligen Orten und ziehen nach ein bis zwei Tagen weiter; ihr Standort
wird im SMP-Chat grob angekündigt. Quest-NPCs erscheinen einmal pro passendem Bauwerk (Netherfestung, Bastion,
Endstadt, Monument, Antike Stadt, Trial Chambers, Anwesen, Festung, Außenposten, Tempel, Iglu, Sumpfhütte, sowie
teilweise Dörfer, Minen, Schiffswracks und Portalruinen), sobald dessen Chunks geladen werden. Eine Quest läuft auf
Zeit; es zählt nur, was der Spieler nach der Annahme selbst tötet, abbaut (keine selbst platzierten Blöcke), erntet
oder angelt. Pro Spieler läuft eine Quest, jede Belohnung gibt es einmal pro Spieler und NPC, und Scheitern oder
Abbrechen sperrt nur diesen NPC für 24 Stunden. Seltene Belohnungen sind Artefakte mit einer Verzauberung über dem
normalen Maximum. Einstellungen stehen unter `smp.npcs`, die Skins in `smp-skins.yml` (signierte Texturen aus der
MineSkin-Galerie; dort lässt sich pro NPC auch ein Minecraft-Benutzername eintragen).

`./gradlew economyTest claimRulesTest` prüft Marktkurve, Wertliste, Shop-Rotation und Grundstücksregeln; beide laufen
beim Build mit. `./scripts/smp-smoke.sh` startet zusätzlich einen isolierten Wegwerf-Server und prüft dort Items,
Artefakte, Grundstücksschutz und das Erscheinen eines Quest-NPCs an einer Netherfestung.

## Lobby-NPCs

Als Admin in der Lobby an die gewünschte NPC-Position stellen, in die gewünschte Richtung schauen
und `/npc` öffnen. **NPC erstellen** → Navigator-Aktion oder **Eigener Befehl** auswählen →
Skin-Username und gegebenenfalls Befehl im Eingabemenü speichern.
Der schwebende Name entspricht immer der gewählten Challenge oder Aktion.
Position und Blickrichtung stammen vom Öffnen des Befehls. Im selben Menü lassen sich vorhandene
NPCs bearbeiten, an die aktuelle Position versetzen oder nach Bestätigung löschen.
Im `/edit`-Modus öffnet ein Rechtsklick auf einen NPC direkt dessen Bearbeitungsmenü;
dort lassen sich Skin und Aktion ändern. Nach erneutem `/edit` führt der Rechtsklick wieder die NPC-Aktion aus.
Die Daten bleiben in `plugins/MinecraftChallenges/npcs.yml` über Neustarts erhalten.
Es wird kein zusätzliches NPC-Plugin benötigt.
Jeder Spieler sieht die NPCs individuell zu seiner eigenen Augenposition schauen. Kopf- und Körperrotation
werden nur an den jeweiligen Client gesendet; die gespeicherte Position und Ausrichtung bleiben erhalten.

- Challenge-NPCs: Linksklick und Berühren versuchen Quick Join; Rechtsklick öffnet die bekannte Lobby-Auswahl.
- Quick Join wählt ausschließlich Wartelobbys und Countdowns mit freien Teilnehmerplätzen,
  immer die mit den meisten Teilnehmern; bei Gleichstand die niedrigste Lobby-Nummer.
  Noch ungeladene Standard-Lobbys zählen als leere Wartelobbys und werden beim Beitritt vorbereitet.
  Laufende Runden (auch die eigene), beendete Runden und regenerierende Welten werden übersprungen.
  Während des Countdowns treten neue Spieler als Teilnehmer bei; der Countdown läuft weiter.
  Gibt es keine passende Lobby, öffnet sich die Lobby-Auswahl. Wiederbeitritt in laufende Runden
  bleibt über die manuelle Auswahl möglich.
- Im Navigator nutzen die Challenge-Einträge dieselbe Auswahl: Linksklick versucht Quick Join,
  Rechtsklick öffnet die Lobby-Auswahl bzw. die Liste eigener Challenges. Gibt es kein Quick-Join-Ziel,
  öffnet auch Linksklick die passende Auswahl. SMP und Jump'n'Run bleiben direkte Ziele.
- Eigene Challenges: Quick Join berücksichtigt nur bereits erstellte, beitretbare Challenges.
  Ohne passende Challenge entfällt der Quick-Join-Hinweis; alle Interaktionen öffnen die Liste zum Beitreten oder Erstellen.
- Jump'n'Run und SMP nutzen dieselben Ziele und Zugangsregeln wie der Navigator.
- Befehle werden bei Rechtsklick oder Berühren als interagierender Spieler mit dessen Rechten ausgeführt.
  Ein führender `/` ist optional; `{player}` wird durch den Spielernamen ersetzt.
- Berühren wird einmal beim Hineinlaufen ausgelöst. Ein kurzer Cooldown verhindert Mehrfachaktionen;
  beim Verwalten und im Lobby-Bearbeitungsmodus werden keine Berührungsaktionen ausgeführt.

Unbegrenzte Standard-Runden werden nach fünf Minuten ohne Spieler automatisch beendet und regeneriert.
Die Frist beginnt bei jedem Beitritt erneut; Spieler und Zuschauer in allen zugehörigen Dimensionen zählen.

`./gradlew npcPolicyTest` prüft die Quick-Join-Auswahl inklusive voller Lobbys und geschlossener eigener Challenges.
Diese Prüfungen laufen auch bei `./gradlew build`.

## Ränge und Permissions

- `minecraftchallenges.rank.admin` ist standardmäßig für Operatoren aktiv und umfasst sämtliche Verwaltungsrechte.
- `minecraftchallenges.rank.mod` umfasst ausschließlich `minecraftchallenges.challenge.manage` für Start, Stop, Zeit und Weltregeneration.
- Normale Spieler besitzen standardmäßig Menü-, Lobby- und Ready-Up-Rechte.

## Chat-Format

Spielernachrichten verwenden den Rang und Namen als Präfix. Servermeldungen und Vanilla-Befehlsausgaben
verwenden je nach Empfänger `Lobby »`, `SMP »` oder `Challenge »`; die Konsole verwendet bei
Plugin-Meldungen `System »`. Vanilla-Ankündigungen bekannter Spieler verwenden ebenfalls deren Rang.
Bereits formatierte Nachrichten werden nicht doppelt präfixiert. Übersetzungen, Farben sowie klickbare
und Hover-Texte bleiben erhalten; Actionbars werden nicht als Chat formatiert.
Die Hilfe heißt innerhalb einer Challenge **Challenge Commands**, in der Lobby **Lobby Commands**
und im SMP **SMP Commands**.

Die Ausgabe-Anpassung ist auf die Mojang-benannten Chat-Pakete von Paper 26.3 abgestimmt und benötigt
kein zusätzliches Plugin. Netty wird nur zum Kompilieren eingebunden und zur Laufzeit vom Server bereitgestellt.
`./gradlew chatFormatTest` prüft das Komponentenformat; diese Prüfungen laufen ebenfalls beim Build.

## Konfiguration

In `plugins/MinecraftChallenges/config.yml` können Weltname, Seed und Größe der Worldborder angepasst werden. `challenge.seed: 0` erzeugt bei jedem Start eine zufällige Welt. Solange noch kein `/challenge setlobby` ausgeführt wurde, wird automatisch der Spawnpunkt der Hauptwelt verwendet. Unter `jump-n-run` lassen sich Plattformmaterial, Starthöhe und Aufräumradius einstellen; die aktivierten Druckplatten werden dort automatisch gespeichert.

## Weitere Challenges ergänzen

Der Lebenszyklus liegt in `ChallengeManager`, die Darstellung in `ChallengeMenu`. Für mehrere echte Challenge-Typen sollte als nächster Schritt ein `Challenge`-Interface mit `start`, `stop`, `onJoinSpectator` und Event-Hooks eingeführt und im Manager registriert werden.

## Performance und Hosting

Die Challenge-Welten bleiben bedarfsgeladen. Eine gemeinsame FIFO-Warteschlange serialisiert
Welt-Erstellung und Spawn-Vorbereitung; weitere Beitritte teilen dieselbe Vorbereitung.
Eigene Challenges reservieren ihren Platz bereits beim Einreihen. Datei-Löschungen bleiben asynchron.
Standard-Blockrandomizer und Schwammwelt laden Nether und End erst beim ersten Portalversuch;
nach der Vorbereitung übernimmt weiterhin Minecraft die Portal-Suche, Portal-Erstellung und End-Plattform.
Auch die Dimensions-Erstellung eigener Challenges nutzt die Warteschlange.

Die 49 Plugin-Chunk-Tickets der Wartezone werden beim Rundenstart entfernt und für geladene
Wartezonen-Chunks bei Bedarf wieder gesetzt. Spieler laden ihre Umgebung weiterhin normal.
Sichtweite, Simulationsdistanz, Inventar-Schutz, Spielerlimits, Regeln und Leerstandsfristen bleiben unverändert.

Alle Schwammwelten teilen ein Konvertierungsbudget: standardmäßig höchstens vier gleichzeitig
vorgehaltene Chunk-Snapshots, höchstens zwei neue Scans pro Tick sowie höchstens 2048 geprüfte
Blockpositionen bzw. ungefähr 2 ms für die Arbeit auf dem Serverthread pro Tick. Einzelne API-Aufrufe
können das Zeitbudget überschreiten. Das Budget lässt sich unter `performance.sponge` einstellen.
Scans laufen weiterhin asynchron; Blockänderungen erfolgen auf dem Serverthread. Entladene Welten
und deaktivierte Regeln verwerfen ausstehende Arbeit. Die Standard-Schwammwelt wartet vor dem
Beitritt auf die abgeschlossene Konvertierung der geladenen Wartezone.

Unveränderte Scoreboard-Werte und Tab-Header werden nicht erneut geschrieben. NPCs verfolgen
Spieler weiterhin alle zwei Ticks; identische Blickpakete entfallen zwischen den sekündlichen
Sicherheitsaktualisierungen. Neu sichtbare NPCs erhalten wieder ein vollständiges Blick-Update.

`./gradlew build` enthält Regressionstests für Warteschlangen-Reihenfolge, Fehlerbehandlung,
Herunterfahren und Scoreboard-Änderungen. `./scripts/performance-smoke.sh` startet zusätzlich einen
isolierten Paper-Testserver in einem neuen temporären Ordner und beendet ihn nach den Prüfungen.
Er verwendet die vorhandenen Paper-Dateien und die bereits akzeptierte `server/eula.txt`, keine bestehenden
Welten oder Spielerdaten. Java 25+ ist erforderlich; optional lässt sich `MC_SMOKE_JAVA` setzen.
Der Test benötigt einen lokalen Netzwerk-Socket. Das separat erzeugte `PerformanceServerSmoke.jar`
ist ausschließlich für diesen Weg gedacht und gehört nicht auf einen Live-Server.

## Live-Deployment zu Kinetic

`./scripts/deploy-live.sh plugin` aktualisiert nur die Plugin-JAR.
`./scripts/deploy-live.sh replace` übernimmt den vollständigen lokalen Serverstand und sichert vorher
den bisherigen Live-Stand. Mit `--dry-run` lässt sich der jeweilige Umfang ohne Verbindung oder
Änderungen prüfen. Optional übernimmt `--auto` Stop/Start und Statusprüfungen über die Kinetic-API.
Zugangsdaten, Sicherungen und Rollback sind in [deploy/README.md](deploy/README.md) beschrieben.

Der Bereitschafts-Schalter ignoriert doppelte Block-/Item-Interaktionen innerhalb von vier Ticks
und Nebenhand-Ereignisse. Ein Rechtsklick auf einen Block kann die soeben gesetzte Bereitschaft
nicht mehr durch das zugehörige zweite Ereignis zurücknehmen; ein späterer Klick bricht wie gewohnt ab.
