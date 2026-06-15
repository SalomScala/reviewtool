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
    ./gradlew :reviewtool-intellij:buildPlugin      # build the IntelliJ plugin zip (needs access to *.jetbrains.com)
    ./gradlew :reviewtool-intellij:runIde           # start a sandbox IDE with the plugin for manual testing

The plugin zip is created in `reviewtool-intellij/build/distributions` and can be installed via "Settings | Plugins | Install Plugin from Disk...".

### Features and configuration

The IntelliJ plugin supports the central review workflow:

- Tickets to review (or to fix) are loaded from **YouTrack** (REST API, authentication with a permanent token).
- The commits belonging to a ticket are determined from the **Git** history (via JGit, by matching the commit messages against a configurable pattern containing the ticket key).
- The tool window ("CoRT") lists the tickets, shows the commits and changed files of the selected ticket (double click opens the file), lets you edit the review remarks and start/end the review. Review remarks and state transitions are written back to the YouTrack ticket.
- **Ticket-less review:** "Review Commits (no ticket)" lets you pick individual Git commits from the working copy and review them together, without any ticket system. The selected commits' changes are loaded into the "Changes" tab and can be turned into tours and a summary just like a ticket's changes.
- **Review tours** can be created from the changes of the selected ticket ("Create Tours"). The "Tours" tab shows the tours and their stops as a tree; the top-level tours can be reordered ("Move Tour Up/Down"), a tour can be activated, and double clicking a stop (or "Show Stop Code") opens the file and selects the changed code. The relevant (non-irrelevant) changes of the active tour are projected onto the editor: the changed line ranges get a highlighted background, a gutter icon and a mark in the scrollbar, so you can see which relevant code locations were changed; the stops of inactive tours are shown more faintly. You can step through the stops ("Previous Stop" / "Next Stop" / "Next Unchecked"), mark stops as checked (and hide checked/irrelevant ones), show a stop's before/after diff in IntelliJ's diff viewer ("Show Stop Diff"), and the tree's context menu offers "Show code", "Show diff", "Open containing folder", "Mark/Unmark as checked" (stops) and "Activate / Move tour" (tours).
- **Review remark markers** are shown in the editor gutters: "Show Remark Markers" parses the current review remarks and renders them, "Add Remark at Cursor" creates a new remark at the caret position (with a remark type) and merges it into the review remarks, and "Clear Markers" removes all markers again. The "Remarks" tab lists all remarks as a tree; its context menu lets you jump to a remark's code, mark it as fixed / won't fix / unclear (optionally with a comment), reopen it, add a comment or delete it — the changes are written back into the review remarks.
- A **change summary** is available in the "Summary" tab: it groups the changes by file, shows the added/removed line counts and lists the changed types and methods of the Java files.

Configure the connection under "Settings | Tools | Code Review Tool (CoRT)": YouTrack URL, permanent token (stored in the IDE's password safe), the name of the text custom field for review remarks, the state names of your workflow and the search queries for the ticket filters.

The review remark markers and the tour ordering UI have been ported to IntelliJ (see above); the markers are transient editor highlighters instead of persisted workspace markers. The cognitive support is wired up when creating the tours: the change classification strategies (the basic irrelevance filters, including the Java-source-parsing import and package-declaration filters) let you mark the detected categories as irrelevant via a dialog, the tour restructuring strategy offers an alternative tour structure to choose from, and the stop ordering algorithm groups and sorts the stops within each tour using the default relation matchers (same file, source folder, method calls/overrides, content similarity, ...). The "Tours" tab shows the classification on each stop and can hide the irrelevant ones. The clustering runs inside a cancelable background task. A lightweight change summary (changed files with line counts and, for Java files, the changed types/methods determined via JavaParser) is shown in the "Summary" tab; the refactoring-detection and delta-doc summary techniques of the Eclipse UI (which depend on Eclipse JDT and external libraries) are not reproduced. Telemetry is not wired up in the IntelliJ plugin yet. The new YouTrack connector (`de.setsoftware.reviewtool.ticketconnectors.youtrack`) is platform-independent and also provides an `IConfigurator` for the XML configuration mechanism (element `youtrackTicketStore`), so it can be used from the Eclipse side as well.

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
