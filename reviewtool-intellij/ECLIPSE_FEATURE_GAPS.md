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
| Icons je Stop nach Besuchsgrad (IconGrammar-Formen) | Text „(unvisited)“ | ✅ Port der IconGrammar (`StopIconGrammar`, `StopIcons`): Form aus Klassifikation, Quellordner, Historie und Größe des Stops (ähnliche Stops sehen ähnlich aus), Farbe von Gelb (angefangen) bis Grün (vollständig gesehen) – Umriss = maximaler, Füllung = durchschnittlicher Besuchsgrad; unbesucht in Textfarbe, irrelevant grau, „checked“ als grüner Haken. Tours zeigen die häufigste Form ihrer Stops, die aktive Tour einen roten Punkt. HiDPI- und Dark-Theme-fähig |
| Stop-Info-View mit kombiniertem Diff | „Show Stop Diff“ öffnet IntelliJ-Diff | ✅ Detailbereich unter dem Tour-Baum: Stop-Infos (Datei, Zeilen, Klassifikation, Besuchsstatus, Tour) und eingebetteter Diff, zur Stop-Zeile gescrollt; ein-/ausblendbar. ✅ Neu: Der Diff zeigt standardmäßig nur die geänderten Abschnitte des Stops (mit 3 Kontextzeilen, Zeilennummern der Originaldatei, andere Änderungen der Datei ausgeblendet); per „Only the stop's changes“ umschaltbar auf die ganze Datei, gilt auch für „Show Stop Diff“ |
| Open in text editor | – | ➖ IntelliJ öffnet Textdateien ohnehin im Texteditor |
| **Lokale Änderungen verfolgen** (Stop-Positionen folgen Edits, `ChangeManager`) | – | ✅ Nach dem Speichern werden die lokalen Änderungen (entprellt, im Hintergrund) analysiert und die Stops nachgeführt: Zeilennummern im Baum, Marker, Sprungziele und View-Tracking passen wieder; abschaltbar („Track Local Changes“, Port von „Stop local change tracking“) |

## Ticketauswahl

| Eclipse | Vorher in IntelliJ | Jetzt |
|---|---|---|
| Spalten „Prev. reviewers“, „Prev. state“, „Open for“ | Key, Summary, State, Component | ✅ Alle Spalten: „Prev. State“ und „Prev. Reviewers“ werden nach dem Laden der Liste im Hintergrund aus der YouTrack-Activity jedes Tickets ermittelt (Fortschrittsanzeige, die Liste bleibt sofort bedienbar); „Open (days)“ zählt ab dem letzten Statuswechsel. Tabelle sortierbar |
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
| Summary: Refactoring-Erkennung (RefDiff) | ✅ Eigener, leichtgewichtiger Detektor auf Basis von JavaParser (`RefactoringDetector`, ohne JDT/RefDiff): umbenannte/verschobene Klassen, umbenannte/verschobene Methoden, geänderte Signaturen, extrahierte und inlinte Methoden – als Abschnitt „Detected refactorings“ im Summary-Tab |
| Summary: Delta-Doc | ❌ |

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

Dritte Runde (Usability-Test des kompletten Ablaufs Einstellungen → Ticketliste → Review → Anmerkung → Review-Ende →
Fixing → Fixing-Ende in der Sandbox-IDE, gegen eine lokale YouTrack-Attrappe):

- **Aktuelles Ticket immer sichtbar:** Über den Tabs steht, zu welchem Ticket sie gehören (Key, Titel, Status und ggf.
  „reviewing/fixing (review round n)“). Vorher verschwand das Ticket beim Start aus der Liste (neuer Status passt nicht
  mehr zum Filter), und nichts zeigte mehr an, woran man gerade arbeitet. Das Ticket bleibt jetzt außerdem fett in der
  Liste, auch nach dem Ende.
- **Platz für die Tours:** Während eines Reviews/Fixings wird die Ticketliste ausgeblendet (Toggle „Show Ticket List“
  in der Toolbar), danach wieder eingeblendet. Vorher bekam die Ticketliste ~60 % der Breite und der Tour-Baum nur
  wenige Pixel (die Diff-Ansicht daneben erzwang ihre Mindestbreite).
- **Pausierte Reviews wiederfinden:** Ein pausiertes Review (bzw. Fixing) wird in der Liste seines Modus auch nach einem
  Neustart angezeigt, obwohl es nicht mehr zum Filter passt.
- **Modus gesperrt während der Arbeit:** Der Modus kann während eines Reviews/Fixings nicht mehr (z. B. versehentlich
  per Pfeiltaste) umgestellt werden; „Start Review/Fixing“ ist für das laufende Ticket deaktiviert.
- **Einstellungen:** in Abschnitte gegliedert, mit Hinweisen und **„Test Connection“** (prüft Token, Abfragen und ob die
  konfigurierten Felder in den Tickets existieren). Nach „OK/Apply“ und beim Öffnen des Tool-Windows wird die
  Ticketliste automatisch geladen.
- **Anmerkungen:** Der Dialog nennt das Ziel („This line (Calculator.java:17)“), das Gutter-Popup zeigt die Anmerkung als
  Titel. Beim Fixing erscheint nach der letzten bearbeiteten Anmerkung „All review remarks … processed – End fixing…“.
- **Review-Ende:** Der Dialog warnt, wenn relevante Stops noch nicht (vollständig) angesehen wurden.
- **Summary:** Pfade relativ zum Projekt, Icons, Doppelklick/Enter öffnet die Datei bzw. die Deklaration nach dem
  Refactoring; Änderungen innerhalb einer Zeile zählen als +1/−1 (vorher „+0/−0“).
- Ticket-Tabelle: Mindestbreiten für Key und Status.

Vierte Runde (Usability-Test mit einem Ticket aus mehreren Commits, mit Fehlerfällen – YouTrack nicht erreichbar,
falsches Token, Neustart mit ungespeicherten Anmerkungen –, Ticketwechsel während des Reviews, Tastatur und hellem Theme):

- **Verständliche YouTrack-Fehler:** „YouTrack is not reachable at … – check the URL and your network connection“,
  „YouTrack rejected the token (HTTP 401: …)“ bzw. die Fehlerbeschreibung von YouTrack statt einer rohen
  `IOException`. Die Benachrichtigung bietet „Retry“ (Tickets laden, Anmerkungen laden, Start, Ende, Speichern) und bei
  Verbindungs-/Token-Problemen „Open settings“ an.
- **Anmerkungen gesperrt, solange sie nicht geladen sind:** Während des Ladens und nach einem Ladefehler ist der
  Anmerkungstext schreibgeschützt; Hinzufügen, Speichern und Review-/Fixing-Ende werden mit Erklärung verweigert, die
  Leiste über den Tabs zeigt den Fehler (siehe Fehlerliste).
- **Lokale Sicherung ungespeicherter Anmerkungen:** Lässt sich nicht speichern und wird die IDE geschlossen, bietet das
  Plugin beim nächsten Laden des Tickets „Restore them“ / „Discard them“ an (einmal pro Sitzung; mit Warnung, falls
  sich die Anmerkungen im Ticket inzwischen geändert haben).
- **Ticketwechsel:** Touren, Summary, Stop-Markierungen, Anmerkungsbaum und -marker des vorherigen Tickets
  verschwinden sofort (vorher blieben sie stehen, bis das neue Ticket geladen war, bzw. ganz). Schaut man während eines
  Reviews ein anderes Ticket an, werden die Touren des laufenden Reviews samt Fortschritt beiseitegelegt und bei der
  Rückkehr wiederhergestellt. „End Review…“ bei angezeigtem fremdem Ticket beendet nicht mehr dessen (gar nicht
  gestartetes) Review, sondern bietet an, das laufende Ticket anzuzeigen.
- **Review-Ende anbieten:** Sobald der letzte relevante Stop angesehen bzw. abgehakt ist, erscheint „All relevant stops
  of … have been viewed – End review…“.
- **Anmerkung zum Stop:** Mit einem im Tour-Baum ausgewählten Stop bezieht sich „Add Remark“ auf dessen Zeile (vorher
  nur „Global“, solange kein Editor offen war); zusätzlich „Add Remark…“ im Kontextmenü des Stops.
- „Mark as Checked“ behält die Auswahl im Tour-Baum (vorher stand rechts danach „No stop selected“).
- **Changes-Tab:** neue/gelöschte Dateien in den IntelliJ-VCS-Farben und mit „new“/„deleted“; gelöschte Dateien
  werden mit ihrem alten Pfad angezeigt.
- **Diff:** Neue Dateien werden mit einer leeren Datei verglichen (vorher „2 differences“ wegen einer leeren Zeile
  links); die Titel zeigen „ed6a915, 2026-10-10 12:15“ statt Hash und Unix-Zeit.
- Ein Wechsel des Themes/Farbschemas zeichnet die Stop-Markierungen in den neuen Farben neu.
- Die Ticketzeile übernimmt nach dem Laden den aktuellen Status, ohne die Spalten „Prev. state/reviewers“ zu verlieren.

Fünfte Runde (Wunschliste: Fortschritt über Neustarts, Anmerkungen im Diff, kleinere Punkte):

- **Review-Fortschritt bleibt über Neustarts erhalten.** Pro Ticket und Review-Runde werden gespeichert: die Auswahl
  beim Erzeugen der Touren (Tour-Struktur, als irrelevant markierte Kategorien), wie oft die Zeilen der Dateien
  angesehen wurden, und die abgehakten Stops. Gespeichert wird im Systemverzeichnis der IDE (pro Projekt), kurz nach
  jeder Änderung, beim Pausieren und beim Schließen; nach dem Review-Ende wird der Stand gelöscht, der Stand einer
  älteren Runde wird verworfen. Nach einem Neustart bietet eine Benachrichtigung „Continue review“ an: Die Touren
  werden ohne Rückfragen genauso erzeugt wie vorher, und Besuchsstatus und Häkchen sind wieder da. Auch „Start Review“
  auf dem Ticket übernimmt den gespeicherten Stand.
- **Anmerkungen direkt im Diff.** „Add Review Remark (CoRT)…“ steht in der Toolbar des Stop-Diffs (eingebettet und
  im eigenen Fenster), im Kontextmenü beider Seiten und im Gutter-Menü; Strg+Alt+4 funktioniert dort ebenfalls. Die
  Zeile wird auf die aktuelle Datei umgerechnet – auch im Ausschnitt „Only the stop's changes“ und für die linke
  (alte) Seite: Eine geänderte oder gelöschte Zeile landet an der Stelle, an der sie geändert bzw. gelöscht wurde.
  Vorher entstand dort nur eine globale Anmerkung.
- **Nur ein Review/Fixing gleichzeitig:** „Start Review“ für ein anderes Ticket während eines laufenden Reviews
  erklärt das und bietet an, das laufende Ticket anzuzeigen (vorher hätte ein zweiter Start Touren und Fortschritt
  des ersten überschrieben).
- Bewusst nicht geändert: Reviewer-Namen erscheinen weiter in Großbuchstaben. Das ist die CoRT-Konvention (auch im
  Jira-Connector der Eclipse-Version), und die Kürzel landen so auch im Anmerkungstext. Die fehlenden Dialogtitel
  im Test lagen am virtuellen Bildschirm ohne Fenstermanager; alle Dialoge setzen einen Titel.

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
- **„Slow operations are prohibited on EDT“** (dritte Runde, im Praxistest als „IDE error occurred“ sichtbar): Beim Laden
  der Anmerkungen wurden die Dateien über den Dateinamen-Index im UI-Thread gesucht, beim Öffnen einer Anmerkung/Datei
  wurde der Code-Style (PSI) im UI-Thread bestimmt, und Einstellungsseite/Ticketliste lasen das Token im UI-Thread aus
  dem Password-Safe. Jetzt geschieht das im Hintergrund (bzw. das Token wird zwischengespeichert), und der Sprung
  verwendet Offsets. Im abschließenden Durchlauf enthielt das IDE-Log keine Fehler mehr.
- Anmerkungen wurden immer Runde 1 zugeordnet (siehe oben).
- **Datenverlust bei nicht erreichbarem YouTrack** (vierte Runde): Der Connector behandelte jeden Fehler beim Laden
  eines Tickets wie „Ticket nicht gefunden“, das Panel zeigte dann leere Anmerkungen. Eine neue Anmerkung und das
  anschließende Speichern (oder ein Review-Ende) hätten die vorhandenen Anmerkungen im Ticket überschrieben. Jetzt
  gilt nur HTTP 404 als „nicht gefunden“, alle anderen Fehler werden gemeldet, und die Anmerkungen bleiben gesperrt,
  bis sie geladen werden konnten (Tests in `YouTrackConnectorTest`). Auch ein abgelehnter Statuswechsel wegen
  falschem Token/URL wird nicht mehr verschluckt.
- **Falsche Aktions-ID im gepackten Plugin** (fünfte Runde, durch einen Test gefunden): Das gepackte plugin.xml
  benennt die Aktions-IDs um („…cortoriginal.…“). Code, der eine Aktion über ihre ID sucht, muss beide IDs kennen.
