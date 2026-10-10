# CoRT - Code Review Tool

CoRT's aim is to spear-head a new generation of "cognitive support code review tools", that is review tools that are not simply tools to annotate portions of source code but that rather help the reviewer to understand the source code and to reduce the cognitive load. It is intended to be an industrial strength code review tool, as well as a platform for code review research.

CoRT is built to support "change-based code review", which means that the portions of the code that have to be reviewed are extracted from changes in a source code repository. It currently supports Subversion, but is extensible in this regard.

Modern IDEs already provide a lot of support in understanding source code (linking between caller and callee, syntax highlighting, ...). Therefore CoRT is implemented as an IDE plugin. Historically it was an Eclipse plugin; the repository now also contains an IntelliJ plugin that reuses the platform-independent core.

## The IntelliJ plugin

The IntelliJ plugin lives in a Gradle build (`settings.gradle.kts` in the repository root) with two projects:

- `reviewtool-core`: the platform-independent core, assembled directly from the sources of the pre-existing OSGi modules (`de.setsoftware.reviewtool.core.model`, `...reviewdata`, `...ordering`, `...changesources.git`, `...ticketconnectors.file`, `...ticketconnectors.jira` and the new `...ticketconnectors.youtrack`). It contains the Git access (via JGit) and the ticket system access (YouTrack, Jira and file based).
- `reviewtool-intellij`: the IntelliJ-specific UI layer (tool window, settings page, background job and logging adapters), built with the [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).

### Building

    ./gradlew :reviewtool-core:test                 # build and test the platform-independent core
    ./gradlew :reviewtool-intellij:test             # smoke tests of the tool window (headless IntelliJ test framework)
    ./gradlew :reviewtool-intellij:buildPlugin      # build the IntelliJ plugin zip (needs access to *.jetbrains.com)
    ./gradlew :reviewtool-intellij:runIde           # start a sandbox IDE with the plugin for manual testing

The plugin zip is created in `reviewtool-intellij/build/distributions` and can be installed via "Settings | Plugins | Install Plugin from Disk...".

### Features and configuration

The IntelliJ plugin supports the central review workflow:

- Tickets to review (or to fix) are loaded from **YouTrack** (REST API, authentication with a permanent token).
- The commits belonging to a ticket are determined from the **Git** history (via JGit, by matching the commit messages against a configurable pattern containing the ticket key). The "Changes" tab shows them with their files; added and deleted files are colored like in IntelliJ's VCS views and marked "new"/"deleted".
- The tool window ("CoRT") lists the tickets (sortable, with the number of days they are open and - loaded in the background from the YouTrack activity - the previous state and the previous reviewers) and shows the commits/changed files, the review tours, the review remarks and a change summary of the selected ticket. A bar above the tabs always shows which ticket they belong to; while a review or fixing is running, the ticket list is hidden (toolbar toggle "Show Ticket List") and the mode cannot be changed, and a paused review stays in the list. The mode combo box chooses between **reviewing** and **fixing**; the toolbar actions work on the selected ticket:
  - **Start Review** changes the ticket state, reloads the ticket (the review round changes) and creates the review tours. **Start Fixing** changes the state and jumps to the first open remark.
  - **End Review...** shows the remarks (still editable) and lets you choose the kind of end: "Pause" only saves the remarks, the other options also change the ticket state. The option matching the remarks is preselected (temporary markers: pause, remarks that need fixing: rejection, otherwise OK) and you are warned about remaining temporary markers.
  - **End Fixing...** saves the remarks and sets the ticket back to "ready for review" (with a warning if remarks have not been processed yet).
  - Changes to the review remarks that are not saved to the ticket yet are marked with a `*` in the "Remarks" tab; switching to another ticket asks whether to save or discard them. They are also kept as a local backup until they are saved: if saving fails (e.g. YouTrack is not reachable) and the IDE is closed, restoring them is offered when the ticket is loaded again (with a warning if the ticket's remarks have changed in the meantime).
  - **Errors** of the YouTrack communication are explained in the notification (server not reachable, token rejected, HTTP error with YouTrack's description) and offer "Retry" and - for connection and token problems - "Open settings". If the remarks of a ticket cannot be loaded, they are locked (and the bar above the tabs shows the error), so that saving cannot overwrite the remarks in the ticket with an empty text.
  - Looking at another ticket during a review or fixing keeps the tours (and the visit progress) of the ticket you work on; they are shown again when you return to it. "End Review/Fixing..." always refers to the ticket you work on (if another ticket is shown, it offers to show that ticket first).
  - "Open Ticket by ID..." shows a ticket that is not in the list of the current filter. The "More" menu offers "Clear Commit Cache" and "Enable Verbose Logging".
- **Ticket-less review:** "Review Commits without Ticket" lets you pick individual Git commits from the working copy and review them together, without any ticket system. The selected commits' changes are loaded into the "Changes" tab and can be turned into tours and a summary just like a ticket's changes.
- **Review tours** are created from the changes ("Create Tours", or automatically when starting a review). The "Tours" tab shows the tours and their stops as a tree; the top-level tours can be reordered, a tour can be activated, and double clicking a stop (or Enter) opens the file at the changed code. Jumping to a stop activates its tour and selects the stop in the tree. The relevant (non-irrelevant) changes of the active tour are projected onto the editor: the changed line ranges get a highlighted background, a gutter icon and a mark in the scrollbar; the stops of inactive tours are shown more faintly. You can step through the stops ("Previous Stop" / "Next Stop" / "Jump to Next Unvisited Stop", which reports when a new tour starts or the search wraps around); when the last relevant stop has been viewed (or checked), a notification offers to end the review. The stops are **view-tracked**: while you scroll through the files the lines you look at are recorded, so each stop is shown as unvisited / partly visited / visited (in addition to the manual "checked" mark) with icons like in Eclipse (a shape derived from the kind of change, so similar stops look similar, colored from yellow to green with the viewed share), a progress bar shows how many relevant stops are left, and the tree can hide checked, visited or irrelevant stops (the filters are remembered). Below the tree, the **stop details** show the selected stop (file, lines, classification, visit state, tour) and its diff, by default restricted to the changed sections of the stop (with context and the original line numbers; "Only the stop's changes" switches to the whole file; new files are compared with an empty file, the titles name the abbreviated commit and its time). When you edit and save files during the review, the **local changes are tracked**: the stops are traced through them, so their line numbers, markers and jump targets stay correct (can be switched off with "Track Local Changes"). How the stops are grouped and ordered (the relation types, their priority and whether they create a nesting level) can be configured in the settings.
- **Review remarks** are added with **"Add Review Remark (CoRT)..."** in the editor's context menu (or the toolbar): one dialog asks for the kind (type its first letter: m, k, d, p, t, s), the text (prefilled with the selected code; Ctrl+Enter adds the remark) and whether the remark refers to the line, the whole file or the review as a whole. When a stop is selected in the "Tours" tab (and the editor does not show its file), the remark refers to the stop; the stop's context menu also offers "Add Remark...". New remarks are added to the current review round of the ticket. The remarks are shown as markers in the editor gutters; clicking a marker opens a **quick-fix popup** to mark it fixed / won't fix / unclear (optionally with a comment), reopen it, add a comment or delete it. The "Remarks" tab groups the remarks like the Eclipse "fixing tasks" view ("To fix", "Already fixed", "Positive", "Other remarks", "Older remarks", with the follow-up comments below each remark) and offers the same actions in its context menu, plus "Jump to Next Open Remark". Below the tree, the remarks text as stored in the ticket can be edited directly; the tree and the markers follow the edits. If the text does not have the CoRT remark syntax, a banner shows the error (and an example of the syntax), and the remarks cannot be changed until the text is corrected, so that nothing is overwritten.
- A **change summary** is available in the "Summary" tab: it shows an overview and, grouped by file (with the added/removed line counts), the changed types and methods of the Java files as a collapsible tree, and the detected refactorings (renamed/moved classes and methods, changed signatures, extracted/inlined methods). Double click opens the file or the declaration after the refactoring.
- **Keyboard shortcuts** (like Alt+4/5/6 in the Eclipse plugin; all actions are also in "Tools | CoRT Code Review" and can be rebound in the keymap settings):

  | Action | Default keymap (Windows/macOS) | Linux keymaps (XWin/GNOME/KDE) |
  |---|---|---|
  | Add review remark | Ctrl+Alt+4 | Ctrl+Alt+Shift+4 |
  | Jump to next unvisited stop (review mode) / next open remark (fixing mode) | Ctrl+Alt+5 | Ctrl+Alt+Shift+5 |
  | Show the caret position in the review tours | Ctrl+Alt+6 | Ctrl+Alt+Shift+6 |

Configure the connection under "Settings | Tools | Code Review Tool (CoRT)": YouTrack URL, permanent token (stored in the IDE's password safe), the name of the text custom field for review remarks, the state names of your workflow and the search queries for the ticket filters. "Test Connection" checks the token, the queries and whether the configured fields exist in the tickets.

A detailed comparison of the Eclipse and IntelliJ features (in German) can be found in [reviewtool-intellij/ECLIPSE_FEATURE_GAPS.md](reviewtool-intellij/ECLIPSE_FEATURE_GAPS.md).

The review remark markers and the tour ordering UI have been ported to IntelliJ (see above); the markers are transient editor highlighters instead of persisted workspace markers. The cognitive support is wired up when creating the tours: the change classification strategies (the basic irrelevance filters, including the Java-source-parsing import and package-declaration filters) let you mark the detected categories as irrelevant via a dialog, the tour restructuring strategy offers an alternative tour structure to choose from, and the stop ordering algorithm groups and sorts the stops within each tour using the default relation matchers (same file, source folder, method calls/overrides, content similarity, ...). The "Tours" tab shows the classification on each stop and can hide the irrelevant ones. The clustering runs inside a cancelable background task. A lightweight change summary (changed files with line counts and, for Java files, the changed types/methods determined via JavaParser) is shown in the "Summary" tab; refactorings are detected by a lightweight JavaParser-based detector instead of RefDiff (which depends on Eclipse JDT); the delta-doc summary technique of the Eclipse UI is not reproduced. Telemetry is not wired up in the IntelliJ plugin yet. The new YouTrack connector (`de.setsoftware.reviewtool.ticketconnectors.youtrack`) is platform-independent and also provides an `IConfigurator` for the XML configuration mechanism (element `youtrackTicketStore`), so it can be used from the Eclipse side as well.

## Installation (Eclipse)

Download the Eclipse update site zip from the "releases" page or build it yourself by calling "./mvnw install". Then install it to Eclipse in the usual way.

## Configuration

CoRT is usually configured for a whole team. Therefore, it has an XML configuration file that can be committed to version control and that is referenced from Eclipse's settings dialog.

- The settings dialog can be found under "Window -> Preferences -> Reviewtool". You need to reference a configuration file there.
- The configuration file can contain placeholders, for example for user names. These need to be configured in the settings dialog, too.
- If a placeholder starts with the prefix "env." (i. e. "${env.USERNAME}"), the placeholder will be replaced with the value of a environment variable (environment variable "USERNAME" in this case).
- Two examples for configuration files can be found in the repository root ("testconfig1.xml" and "testconfig2.xml"). You need to adjust them for your specific situation.
- There is not much documentation for the config format at the moment. If you want to get into the details, have a look at the various subclasses of de.setsoftware.reviewtool.config.IConfigurator from the ...core project.

## The research

CoRT is built at the "Fachgebiet Software Engineering" of Leibniz University Hannover (http://se.uni-hannover.de). The principles behind CoRT were derived using sound research methodology, CoRT is evaluated in a research project, and is also used to provide data for code review research. Most of the research results can be found on the university homepage or at http://tobias-baum.de

## The name

Quite obviously, CoRT stands for "Code Review Tool". But as it is fashionable to name review tools after people's names (like Mondrian, Rietveld and Gerrit), we also have a corresponding explanation. In fact, we even have two:
* CoRT sounds similar to Cord. Cord Broyhan was a famous beer brewer in Hannover in the middle ages: https://de.wikipedia.org/wiki/Cord_Broyhan
* CoRT sounds similar to Kurt. Kurt Schwitters was an influential artist from Hannover: https://de.wikipedia.org/wiki/Kurt_Schwitters

[![Build Status](https://travis-ci.com/tobiasbaum/reviewtool.svg?branch=master)](https://app.travis-ci.com/tobiasbaum/reviewtool)
