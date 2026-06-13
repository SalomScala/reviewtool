package de.setsoftware.reviewtool.changesources.git;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.errors.GitAPIException;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.model.api.BackgroundJobExecutor;
import de.setsoftware.reviewtool.model.api.ChangeSourceException;
import de.setsoftware.reviewtool.model.api.IChange;
import de.setsoftware.reviewtool.model.api.IChangeData;
import de.setsoftware.reviewtool.model.api.IChangeSourceUi;
import de.setsoftware.reviewtool.model.api.ICommit;
import de.setsoftware.reviewtool.model.api.ICortProgressMonitor;
import de.setsoftware.reviewtool.model.api.IFileHistoryNode;
import de.setsoftware.reviewtool.model.api.IRevisionedFile;
import de.setsoftware.reviewtool.model.changestructure.AbstractChangeSource;
import de.setsoftware.reviewtool.model.changestructure.ChangestructureFactory;

/**
 * A change source that loads the changes from Git.
 */
public class GitChangeSource extends AbstractChangeSource {
    
    private File cacheDir;

    /**
     * Constructor.
     */
    GitChangeSource(
            final String logMessagePattern,
            final long maxTextDiffThreshold,
            File cacheDir) {
        super(logMessagePattern, maxTextDiffThreshold);
        this.cacheDir = cacheDir;
    }

    @Override
    public IChangeData getRepositoryChanges(final String key, final IChangeSourceUi ui)
        throws ChangeSourceException {

        try {
            ui.subTask("Determining relevant commits...");
            final Map<GitRevision, String> revisions = this.determineRelevantRevisions(key, ui);
            final List<GitRevision> selectedRevisions = this.checkBranches(revisions, ui);
            ui.subTask("Analyzing commits...");
            final List<ICommit> commits = this.convertRepoRevisionsToChanges(selectedRevisions, ui);
            return ChangestructureFactory.createChangeData(commits);
        } catch (final IOException | GitAPIException e) {
            throw new ChangeSourceException(this, e);
        }
    }

    /**
     * Returns the most recent commits of the working copies (at most {@code max} per working copy),
     * so that the user can pick individual commits for a ticket-less review.
     */
    public List<GitCommitInfo> getRecentCommits(final int max, final IChangeSourceUi ui)
        throws ChangeSourceException {
        try {
            ui.subTask("Loading recent commits...");
            final List<GitRevision> revisions = GitWorkingCopyManager.getInstance().listRecentRevisions(max, ui);
            final List<GitCommitInfo> ret = new ArrayList<>();
            for (final GitRevision r : revisions) {
                ret.add(new GitCommitInfo(r.getRevisionString(), r.getAuthor(), r.getDate(), r.getMessage()));
            }
            return ret;
        } catch (final IOException e) {
            throw new ChangeSourceException(this, e);
        }
    }

    /**
     * Returns the changes for the explicitly selected commits (identified by their full hash),
     * without involving any ticket system.
     */
    public IChangeData getChangesForCommits(final Set<String> revisionIds, final IChangeSourceUi ui)
        throws ChangeSourceException {
        try {
            ui.subTask("Determining selected commits...");
            final Map<GitRevision, String> revisions = this.determineRevisionsByIds(revisionIds, ui);
            ui.subTask("Analyzing commits...");
            final List<ICommit> commits =
                    this.convertRepoRevisionsToChanges(new ArrayList<>(revisions.keySet()), ui);
            return ChangestructureFactory.createChangeData(commits);
        } catch (final IOException | GitAPIException e) {
            throw new ChangeSourceException(this, e);
        }
    }

    private Map<GitRevision, String> determineRevisionsByIds(
            final Set<String> revisionIds,
            final IChangeSourceUi ui) throws GitAPIException, IOException {

        final HistoryFiller historyFiller = new HistoryFiller();
        final Predicate<GitRevision> handler = (final GitRevision logEntry) -> {
            historyFiller.register(logEntry);
            return revisionIds.contains(logEntry.getRevisionString());
        };

        final Map<GitRevision, String> matchingEntries =
                GitWorkingCopyManager.getInstance().traverseEntries(handler, ui);
        historyFiller.populate(matchingEntries.keySet(), ui);
        return matchingEntries;
    }

    private List<GitRevision> checkBranches(Map<GitRevision, String> revisions, IChangeSourceUi ui) {
        final List<GitRevision> ret = new ArrayList<>();
        final List<GitRevision> nonHeadRevisions = new ArrayList<>();
        final Set<String> refs = new LinkedHashSet<>();
        for (final Entry<GitRevision, String> e : revisions.entrySet()) {
            if (e.getValue().equals("HEAD")) {
                ret.add(e.getKey());
            } else {
                nonHeadRevisions.add(e.getKey());
                refs.add(e.getValue());
            }
        }
        if (!nonHeadRevisions.isEmpty()) {
            final Boolean answer = ui.handleLocalWorkingIncomplete(
                    "The current HEAD does not contain all commits for the ticket (other refs: " + refs
                    + "). Restrict review to current HEAD?");
            if (answer == null) {
                throw BackgroundJobExecutor.createOperationCanceledException();
            } else if (!answer) {
                ret.addAll(nonHeadRevisions);
            }
        }
        return ret;
    }

    private Map<GitRevision, String> determineRelevantRevisions(
            final String key,
            final IChangeSourceUi ui) throws GitAPIException, IOException {

        final Pattern pattern = this.createPatternForKey(key);
        final HistoryFiller historyFiller = new HistoryFiller();
        final Predicate<GitRevision> handler = (final GitRevision logEntry) -> {
            historyFiller.register(logEntry);
            final String message = logEntry.getMessage();
            return message != null && pattern.matcher(message).matches();
        };

        final Map<GitRevision, String> matchingEntries =
                GitWorkingCopyManager.getInstance().traverseEntries(handler, ui);
        historyFiller.populate(matchingEntries.keySet(), ui);
        return matchingEntries;
    }

    private List<ICommit> convertRepoRevisionsToChanges(
            final List<GitRevision> revisions,
            final ICortProgressMonitor ui) throws IOException {
        final List<ICommit> ret = new ArrayList<>();
        for (final GitRevision e : revisions) {
            if (ui.isCanceled()) {
                throw BackgroundJobExecutor.createOperationCanceledException();
            }
            this.convertToCommitIfPossible(e, ret, ui);
        }
        return ret;
    }

    private void convertToCommitIfPossible(
            final GitRevision e,
            final Collection<? super ICommit> result,
            final ICortProgressMonitor ui) throws IOException {
        final List<? extends IChange> changes = this.determineChangesInCommit(e, ui);
        if (!changes.isEmpty()) {
            result.add(ChangestructureFactory.createCommit(
                    e.getWorkingCopy(),
                    e.toPrettyString(),
                    changes,
                    e.toRevision(),
                    e.getDate()));
        }
    }

    private List<? extends IChange> determineChangesInCommit(
            final GitRevision e,
            final ICortProgressMonitor ui) throws IOException {

        final List<IChange> ret = new ArrayList<>();
        final Set<String> changedPaths = e.getChangedPaths();
        final List<String> sortedPaths = new ArrayList<>(changedPaths);
        Collections.sort(sortedPaths);
        for (final String path : sortedPaths) {
            if (ui.isCanceled()) {
                throw BackgroundJobExecutor.createOperationCanceledException();
            }

            final IRevisionedFile fileInfo = ChangestructureFactory.createFileInRevision(path, e.toRevision());
            final IFileHistoryNode node = e.getWorkingCopy().getFileHistoryGraph().getNodeFor(fileInfo);
            if (node != null) {
                try {
                    ret.addAll(this.determineChangesInFile(e.getWorkingCopy(), node));
                } catch (final Exception ex) {
                    Logger.error("An error occurred while computing changes for " + fileInfo.toString(), ex);
                }
            } else {
                Logger.debug("history node is null for " + fileInfo);
            }
        }
        return ret;
    }

    @Override
    public void analyzeLocalChanges(List<File> relevantPaths) throws ChangeSourceException {
        try {
            GitWorkingCopyManager.getInstance().collectWorkingCopyChanges(relevantPaths);
        } catch (final IOException | GitAPIException e) {
            throw new ChangeSourceException(this, e);
        }
    }

    @Override
    public File determineWorkingCopyRoot(final File projectRoot) throws ChangeSourceException {
        File dir = projectRoot;
        do {
            if (this.containsDotGit(dir)) {
                return dir;
            }
            dir = dir.getParentFile();
        } while (dir != null);
        return null;
    }

    private boolean containsDotGit(File dir) {
        final File dotGit = new File(dir, ".git");
        return dotGit.isDirectory();
    }

    @Override
    protected void workingCopyAdded(File wcRoot) {
        GitWorkingCopyManager.getInstance().getWorkingCopy(wcRoot, cacheDir);
    }

    @Override
    protected void workingCopyRemoved(File wcRoot) {
        GitWorkingCopyManager.getInstance().removeWorkingCopy(wcRoot);
    }

    @Override
    public void clearCaches() {
        for (final GitWorkingCopy wc : GitWorkingCopyManager.getInstance().getWorkingCopies()) {
            wc.clearCache();
        }
    }

}
