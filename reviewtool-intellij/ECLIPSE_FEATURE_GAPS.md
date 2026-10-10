# Eclipse- vs. IntelliJ-Plugin: Feature-Abgleich

Diese Übersicht vergleicht die Funktionen der Eclipse-UI (`de.setsoftware.reviewtool.core.ui-eclipse`) mit dem
IntelliJ-Plugin (`reviewtool-intellij`). Legende:

- ✅ umgesetzt (in dieser Änderung neu oder verbessert, siehe „Neu“)
- 🟡 teilweise umgesetzt
- ❌ noch nicht umgesetzt
- ➖ in IntelliJ nicht sinnvoll / nicht nötig

## Review-Ablauf (Modi)

| Eclipse | Vorher in IntelliJ | Jetzt |
|---|---|---|
| **Start review**: Ticket wählen, Tours erzeugen, Marker setzen, View-Tracking starten | Nur Statuswechsel im Ticket, Tours separat per Button | ✅ Neu: Start speichert ungespeicherte Anmerkungen, wechselt den Status, lädt das Ticket neu (neue Review-Runde) und erzeugt automatisch die Tours |
| **Start fixing** | Nur Statuswechsel | ✅ Neu: zusätzlich Wechsel in den Remarks-Tab und Sprung zur ersten offenen Anmerkung |
| **End review** mit Dialog: Anmerkungen editierbar, „Pause“, Vorauswahl je nach Anmerkungen, Warnung bei temporären Markern | Einfacher Auswahldialog ohne „Pause“, ohne Vorauswahl und ohne Warnungen | ✅ Neu: `EndReviewDialog` (Port) |
| **End fixing** (Ticket zurück auf „ready for review“, Warnung bei offenen Anmerkungen) | Fehlte komplett (`changeStateToReadyForReview` wurde nie aufgerufen) | ✅ Neu |
| „Offene Fixing-Tickets – stattdessen Fixing starten?“ | – | ❌ (der Modus wird in IntelliJ explizit über die Combo-Box gewählt) |
| Ungespeicherte Editoren vor Start/Ende speichern | – | ➖ IntelliJ speichert automatisch |
| Review-/Fixing-Perspektive | – | ➖ stattdessen wechselt das Tool-Window in den passenden Tab |

## Review-Anmerkungen

| Eclipse | Vorher in IntelliJ | Jetzt |
|---|---|---|
| **Create-Remark-Dialog**: ein Dialog mit Art (Auswahl per Anfangsbuchstabe), mehrzeiligem Text (vorbelegt mit der Selektion) und Bezug Zeile/Datei/global | Zwei aufeinanderfolgende Dialoge, Enum-Namen (`MUST_FIX`), nur Zeilenbezug | ✅ Neu: `CreateRemarkDialog` (Port), Strg+Enter zum Hinzufügen, letzte Art wird gemerkt |
| Anmerkungen landen in der **aktuellen Review-Runde**, Autor = Reviewer der Runde | Immer Runde 1, Autor = lokaler Systembenutzer | ✅ Neu: Runde und Reviewer werden aus dem YouTrack-Ticket ermittelt |
| **Fixing-Tasks-View**: Kategorien „To Fix“, „Already fixed“, „Positive“, „Other“, „Older“, Kommentare als Kindknoten, Icons je Art/Auflösung | Liste gruppiert nach Runde, ohne Kommentare/Icons | ✅ Neu |
| **Jump to next open remark** (Alt+5 im Fixing-Modus) | – | ✅ Neu (Toolbar, Menü, Tastenkürzel) |
| Quick-Fixes am Marker (fixed / won't fix / unclear / Kommentar / löschen) | Vorhanden | ✅ Verbessert: gemeinsame Aktionsgruppe für Gutter und Remarks-Tab, zustandsabhängige Einträge, Löschbestätigung, mehrzeilige Kommentare |
| Review-Info-View: Anmerkungstext editierbar, Marker werden nach Änderung neu geladen | Textfeld im Changes-Tab, Änderungen erst nach „Reload from Editor“ sichtbar; **ungespeicherte Änderungen gingen beim Ticketwechsel kommentarlos verloren** | ✅ Neu: Text im Remarks-Tab, automatische Synchronisation nach dem Tippen, „ungespeichert“-Anzeige (`*` im Tab-Titel), Rückfrage Speichern/Verwerfen/Abbrechen bei Ticketwechsel |
| Write remarks to ticket system / Reload review remarks | „Save Remarks to Ticket“ | ✅ Save nur aktiv bei Änderungen, neue Aktion „Reload Remarks and Changes of the Ticket“ |
| Marker für Anmerkungen zu ganzen Dateien | Wurden nicht angezeigt | ✅ Neu: werden in der ersten Zeile angezeigt |
| Marker automatisch beim Start | Erst nach „Show Remark Markers“ | ✅ Neu: Marker werden beim Laden eines Tickets angezeigt |
| `CorrectSyntaxDialog` bei nicht parsebaren Anmerkungen | Parse-Fehler wurde nur geloggt; **das Modell war dann leer, und die nächste Änderung (Anmerkung hinzufügen, als fixed markieren, …) überschrieb den gesamten Anmerkungstext** | ✅ Fehlerbanner mit Fehlermeldung und Syntax-Beispiel im Remarks-Tab; Änderungen, Hinzufügen und Review-/Fixing-Ende sind gesperrt, bis der Text korrigiert ist |

## Review-Tours

| Eclipse | Vorher in IntelliJ | Jetzt |
|---|---|---|
| **Jump to next unvisited stop** mit Hinweis bei Tour-Wechsel und Wrap-around (Alt+5 im Review-Modus) | Eigene Logik ohne Tour-Aktivierung und ohne Hinweise | ✅ Neu: nutzt `ViewStatistics.getNextUnvisitedStop` des Cores, Hinweise als Notification |
| Sprung zu einem Stop aktiviert die Tour und selektiert den Stop im Baum | Tour blieb inaktiv, Baum-Selektion wurde nicht nachgeführt | ✅ Neu |
| Sprung selektiert **nicht** den geänderten Code (sonst überschreibt Tippen ihn / Anmerkungen werden damit vorbelegt) | Ganzer geänderter Bereich wurde selektiert | ✅ Neu: Cursor an den Anfang, Bereich bleibt durch den Stop-Marker hervorgehoben |
| **Show in Review content** (Alt+6): nächstgelegenen Stop zur Cursor-Position zeigen | – | ✅ Neu („Show Caret Position in Review Tours“) |
| **Fortschrittsbalken** (irrelevant / besucht / teilweise / unbesucht) | Nur Anzahl Tours/Stops | ✅ Neu: `ReviewProgressBar` |
| Filter (checked / visited / irrelevant) werden gespeichert | Nicht gespeichert | ✅ Neu |
| Icons je Stop nach Besuchsgrad (IconGrammar-Formen) | Text „(unvisited)“ | 🟡 Status-Icons (checked / besucht / teilweise / unbesucht / irrelevant), keine IconGrammar-Formen |
| Stop-Info-View mit kombiniertem Diff | „Show Stop Diff“ öffnet IntelliJ-Diff | ✅ Detailbereich unter dem Tour-Baum: Stop-Infos (Datei, Zeilen, Klassifikation, Besuchsstatus, Tour) und eingebetteter Diff, zur Stop-Zeile gescrollt; ein-/ausblendbar. 🟡 Diff der ganzen Datei statt nur der Hunks des Stops |
| Open in text editor | – | ➖ IntelliJ öffnet Textdateien ohnehin im Texteditor |
| **Lokale Änderungen verfolgen** (Stop-Positionen folgen Edits, `ChangeManager`) | – | ✅ Nach dem Speichern werden die lokalen Änderungen (entprellt, im Hintergrund) analysiert und die Stops nachgeführt: Zeilennummern im Baum, Marker, Sprungziele und View-Tracking passen wieder; abschaltbar („Track Local Changes“, Port von „Stop local change tracking“) |

## Ticketauswahl

| Eclipse | Vorher in IntelliJ | Jetzt |
|---|---|---|
| Spalten „Prev. reviewers“, „Prev. state“, „Open for“ | Key, Summary, State, Component | 🟡 „Open (days)“ ergänzt, Tabelle sortierbar; Vorgänger-Reviewer/-Status bräuchten pro Ticket eine Activity-Abfrage |
| Zuletzt genutzter Filter wird gemerkt | – | ✅ Modus wird gemerkt, Wechsel lädt die Tickets neu |
| Ticket-ID direkt eingeben | – | ✅ „Open Ticket by ID…“ (auch für Tickets, die nicht im aktuellen Filter sind) |

## Befehle und Tastenkürzel

| Eclipse | Jetzt in IntelliJ |
|---|---|
| Alt+4 Add review remark | ✅ Strg+Alt+4 (Linux-Keymaps: Strg+Alt+Umschalt+4), Editor-Kontextmenü, Tools-Menü |
| Alt+5 Jump to next stop / open remark | ✅ Strg+Alt+5 (Linux: Strg+Alt+Umschalt+5) |
| Alt+6 Show in Review content | ✅ Strg+Alt+6 (Linux: Strg+Alt+Umschalt+6) |
| Menü „CoRT code review“ | ✅ Gruppe „CoRT Code Review“ im Tools-Menü |
| Clear commit cache / Enable verbose logging / Stop change tracking | ✅ „More“-Menü der Toolbar bzw. Toggle im Tours-Tab |

Alt+Ziffer ist in IntelliJ für Tool-Windows belegt, und in der Linux-Keymap „Default for XWin“ (von der GNOME/KDE erben)
sind auch Strg+Alt+4/5/6 schon vergeben – deshalb dort die Variante mit Umschalt.

## Konfiguration und Erweiterbarkeit

| Eclipse | IntelliJ |
|---|---|
| Team-Konfiguration als XML-Datei mit Platzhaltern | ❌ Einstellungsseite pro Projekt |
| Relation-Matcher-Einstellungen für die Stop-Reihenfolge | ✅ Tabelle in den Einstellungen: Relationen aktivieren, Priorität per Hoch/Runter, Verschachtelung im Tour-Baum |
| Telemetrie, Umfrage am Review-Ende, End-Review-Extensions, „preferred transition“-Strategien | ❌ |
| SVN als Change-Source | ❌ nur Git |
| Summary: Refactoring-Erkennung, Delta-Doc | ❌ (siehe README) |

## Weitere Usability-Verbesserungen (ohne direktes Eclipse-Pendant)

- IntelliJ-Toolbars mit Icons, Tooltips und Aktivierung je nach Zustand statt langer Button-Reihen; die zentralen Aktionen
  („Start Review/Fixing“, „End Review/Fixing…“) zeigen ihren Text.
- Leere Zustände mit Hinweisen und Links („YouTrack is not configured yet – Open settings“, „Refresh tickets“,
  „Or review commits without a ticket“).
- Fehler bei fehlender Konfiguration bieten in der Benachrichtigung „Open settings“ an; Info-Meldungen erscheinen als
  Notification statt als modaler Dialog.
- Beim schnellen Wechsel zwischen Tickets werden veraltete Ladeergebnisse verworfen (vorher konnte ein langsamer
  Ladevorgang die Daten des neu gewählten Tickets überschreiben).
- Dateisymbole und Pfade im Changes-Baum, Enter öffnet Dateien/Stops/Anmerkungen.
- Das Tool-Window stoppt beim Schließen seinen View-Tracking-Timer und entfernt die Marker.

Zweite Runde (nach einem Durchlauf in einer echten Sandbox-IDE):

- Eigenes Tool-Window-Icon (vorher identisch mit dem Git-Icon) und eigene Stop-Icons im Gutter.
- Die Hervorhebung der Stops richtet sich nach dem Editor-Farbschema statt nach dem IDE-Theme (bei dunkler IDE und
  hellem Editor sah der geänderte Code vorher wie selektiert aus).
- Tour-Baum und Stop-Details stehen nebeneinander (das Tool-Window ist unten meist flach und breit); die Aufteilung
  wird gemerkt.
- Commit-Auswahl ohne Ticket als Tabelle mit Suche (Nachricht, Autor, Hash – z. B. ein Ticket-Key), „Select All
  Shown“ und Zähler; die Meldung nach dem Laden bietet direkt „Create review tours“ an.
- Beim ersten Sprung in die Tours keine überflüssige „Start of a new tour“-Meldung mehr.

## Gefundene und behobene Fehler

- **Unsichtbare Gutter-Icons** (zweite Runde, im Praxistest gefunden): Die Gutter-Icons der Stops und Anmerkungen
  wurden in der neuen IntelliJ-UI nicht angezeigt (veraltete Balloon-/Arrow-Icons mit linker Ausrichtung) – damit
  fehlte im Editor auch der Einstieg in das Quick-Fix-Popup der Anmerkungen. Jetzt mit eigenen Stop-Icons bzw. den
  Standard-Warn-/Info-Icons.
- **Datenverlust bei nicht parsebaren Anmerkungen** (zweite Runde): War der Anmerkungstext eines Tickets nicht im
  CoRT-Format (z. B. frei formulierter Text), wurde das Modell stillschweigend leer – die nächste Änderung schrieb
  dann nur noch die neue Anmerkung zurück und löschte den Rest. Jetzt werden Änderungen in diesem Zustand verweigert
  (Tests `testRemarksWithSyntaxErrorsAreNotOverwritten`).

- **Root-Commit** (`GitRevision`): Für den ersten Commit eines Repositories wurde gegen den „Empty Tree“ `4b825dc…`
  gedifft, den JGit nicht als Objekt im Repository hat → `MissingObjectException`. Das betraf jeden Review, bei dem
  der erste Commit eines Repositories im analysierten Zeitraum liegt. Die vorhandenen Tests haben das verdeckt, weil
  ihr Test-Repository immer mit einem leeren Commit beginnt (der den leeren Tree schreibt). Neuer Regressionstest:
  `GitChangeSourceTest.testRootCommit`.
- Die Editor-Aktion suchte das Tool-Window unter der ID „CoRT“, im gepackten Plugin heißt es aber „CoRTOriginal“.
- Anmerkungen wurden immer Runde 1 zugeordnet (siehe oben).
