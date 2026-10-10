package de.setsoftware.reviewtool.intellij;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.changesources.git.GitChangeSource;
import de.setsoftware.reviewtool.changesources.git.GitChangesourceConfigurator;
import de.setsoftware.reviewtool.changesources.git.GitCommitInfo;
import de.setsoftware.reviewtool.config.IReviewConfigurable;
import de.setsoftware.reviewtool.irrelevancestrategies.basicfilters.BinaryFileFilter;
import de.setsoftware.reviewtool.irrelevancestrategies.basicfilters.FileDeletionFilter;
import de.setsoftware.reviewtool.irrelevancestrategies.basicfilters.ImportChangeFilter;
import de.setsoftware.reviewtool.irrelevancestrategies.basicfilters.PackageDeclarationFilter;
import de.setsoftware.reviewtool.irrelevancestrategies.basicfilters.WhitespaceChangeFilter;
import de.setsoftware.reviewtool.ordering.StopOrdering;
import de.setsoftware.reviewtool.tourrestructuring.onestop.OneStopPerPartOfFileRestructuring;
import de.setsoftware.reviewtool.intellij.ReviewToolSettings.SettingsState;
import de.setsoftware.reviewtool.model.TicketLinkSettings;
import de.setsoftware.reviewtool.model.api.BackgroundJobExecutor;
import de.setsoftware.reviewtool.model.api.ChangeSourceException;
import de.setsoftware.reviewtool.model.api.IChangeData;
import de.setsoftware.reviewtool.model.api.IChangeSource;
import de.setsoftware.reviewtool.model.api.IChangeSourceUi;
import de.setsoftware.reviewtool.model.changestructure.IChangeClassifier;
import de.setsoftware.reviewtool.model.changestructure.IStopOrdering;
import de.setsoftware.reviewtool.model.changestructure.ITourRestructuring;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ICreateToursUi;
import de.setsoftware.reviewtool.model.changestructure.ToursInReview.ReviewRoundInfo;
import de.setsoftware.reviewtool.ticketconnectors.youtrack.YouTrackConnector;

/**
 * Central project service that wires the platform-independent CoRT core
 * (YouTrack ticket connector and JGit based change source) into IntelliJ.
 */
@Service(Service.Level.PROJECT)
public final class ReviewToolService {

    public static final String FILTER_REVIEW = "Review";
    public static final String FILTER_FIXING = "Fixing";

    static {
        Logger.setLogger(new IntellijLogger());
        BackgroundJobExecutor.setInstance(new IntellijBackgroundJobExecutor());
    }

    private final Project project;
    private IChangeSource changeSource;
    private String changeSourceConfig;
    private ReviewToolPanel reviewPanel;

    public ReviewToolService(Project project) {
        this.project = project;
    }

    public static ReviewToolService getInstance(Project project) {
        return project.getService(ReviewToolService.class);
    }

    /**
     * Registers the tool window's panel so that actions (e.g. the editor context-menu action to add
     * a review remark) can reach it.
     */
    public void setReviewPanel(ReviewToolPanel reviewPanel) {
        this.reviewPanel = reviewPanel;
    }

    public ReviewToolPanel getReviewPanel() {
        return this.reviewPanel;
    }

    private SettingsState getSettings() {
        return ReviewToolSettings.getInstance(this.project).getState();
    }

    /**
     * Creates a fresh ticket connector according to the current settings.
     */
    public YouTrackConnector createTicketConnector() {
        final SettingsState s = this.getSettings();
        if (s.youtrackUrl.isEmpty()) {
            throw new ReviewtoolException(
                    "The YouTrack URL is not configured."
                    + " Please configure it under Settings | Tools | Code Review Tool (CoRT).");
        }
        final YouTrackConnector connector = new YouTrackConnector(
                s.youtrackUrl,
                ReviewToolSettings.getInstance(this.project).getYoutrackToken(),
                s.reviewFieldName,
                s.stateFieldName,
                s.componentFieldName,
                s.reviewStateName,
                s.implementationStateName,
                s.readyForReviewStateName,
                s.rejectedStateName,
                s.doneStateName,
                this.createLinkSettings(s));
        connector.addFilter(FILTER_REVIEW, s.reviewFilterQuery, true);
        connector.addFilter(FILTER_FIXING, s.fixingFilterQuery, false);
        return connector;
    }

    private TicketLinkSettings createLinkSettings(SettingsState s) {
        final String baseUrl = s.youtrackUrl.endsWith("/")
                ? s.youtrackUrl.substring(0, s.youtrackUrl.length() - 1)
                : s.youtrackUrl;
        final String pattern = s.ticketLinkPattern.isEmpty()
                ? baseUrl + "/issue/%s"
                : s.ticketLinkPattern;
        return new TicketLinkSettings(pattern, "Open in YouTrack");
    }

    /**
     * Returns the Git change source, creating it if necessary. The change source is
     * re-created when the relevant settings have changed in the meantime.
     */
    public synchronized IChangeSource getChangeSource() {
        final SettingsState s = this.getSettings();
        final String config = s.logMessagePattern + " " + s.maxTextDiffFileSizeThreshold;
        if (this.changeSource == null || !config.equals(this.changeSourceConfig)) {
            this.changeSource = this.createChangeSource(s);
            this.changeSourceConfig = config;
            this.registerProjectRoots(this.changeSource);
        }
        return this.changeSource;
    }

    /**
     * Invalidates cached objects after the settings changed.
     */
    public synchronized void settingsChanged() {
        this.changeSource = null;
        this.changeSourceConfig = null;
    }

    private IChangeSource createChangeSource(SettingsState s) {
        final Element configElement;
        try {
            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            configElement = doc.createElement("gitChangeSource");
        } catch (final ParserConfigurationException e) {
            throw new ReviewtoolException(e);
        }
        configElement.setAttribute("pattern", s.logMessagePattern);
        configElement.setAttribute("maxTextDiffFileSizeThreshold",
                Long.toString(s.maxTextDiffFileSizeThreshold));

        final File cacheDir = new File(
                new File(PathManager.getSystemPath(), "cort"), this.project.getLocationHash());
        cacheDir.mkdirs();

        final IChangeSource[] holder = new IChangeSource[1];
        new GitChangesourceConfigurator().configure(configElement, new IReviewConfigurable() {
            @Override
            public void configureWith(Object strategy) {
                holder[0] = (IChangeSource) strategy;
            }

            @Override
            public File getStateDirectory() {
                return cacheDir;
            }
        });
        return holder[0];
    }

    private void registerProjectRoots(IChangeSource source) {
        final VirtualFile[] roots = ReadAction.compute(
                () -> ProjectRootManager.getInstance(this.project).getContentRoots());
        for (final VirtualFile root : roots) {
            if (!root.isInLocalFileSystem()) {
                continue;
            }
            try {
                source.addProject(new File(root.getPath()));
            } catch (final ChangeSourceException e) {
                Logger.warn("could not register project root " + root.getPath(), e);
            }
        }
        final String basePath = this.project.getBasePath();
        if (roots.length == 0 && basePath != null) {
            try {
                source.addProject(new File(basePath));
            } catch (final ChangeSourceException e) {
                Logger.warn("could not register project base path " + basePath, e);
            }
        }
    }

    /**
     * Determines the changes for the given ticket from the Git history.
     */
    public IChangeData getRepositoryChanges(String ticketKey, IChangeSourceUi ui)
            throws ChangeSourceException {
        return this.getChangeSource().getRepositoryChanges(ticketKey, ui);
    }

    /**
     * Analyzes the local (not yet committed) changes in the working copies, so that the review stops
     * can be traced to the current line numbers.
     */
    public synchronized void analyzeLocalChanges() throws ChangeSourceException {
        this.getChangeSource().analyzeLocalChanges(null);
    }

    /**
     * Returns the most recent Git commits so that the user can pick some for a ticket-less review.
     */
    public List<GitCommitInfo> getRecentCommits(int max, IChangeSourceUi ui) throws ChangeSourceException {
        return ((GitChangeSource) this.getChangeSource()).getRecentCommits(max, ui);
    }

    /**
     * Determines the changes for the explicitly selected commits (without any ticket system).
     */
    public IChangeData getChangesForCommits(Set<String> revisionIds, IChangeSourceUi ui)
            throws ChangeSourceException {
        return ((GitChangeSource) this.getChangeSource()).getChangesForCommits(revisionIds, ui);
    }

    /**
     * Builds the review tours for the given changes using the cognitive support: the change
     * classification strategies (the basic irrelevance filters) let the user mark detected categories
     * as irrelevant, the tour restructuring strategy offers an alternative tour structure (the user
     * picks one) and the stop ordering algorithm groups and sorts the stops within each tour.
     */
    public ToursInReview createTours(IChangeData changes, IChangeSourceUi ui) {
        return this.createTours(changes, ui, new IntellijCreateToursUi(this.project));
    }

    /**
     * Builds the review tours like {@link #createTours(IChangeData, IChangeSourceUi)}, but with the
     * given UI for the user decisions during the tour creation.
     */
    ToursInReview createTours(IChangeData changes, IChangeSourceUi ui, ICreateToursUi createToursUi) {
        return ToursInReview.create(
                ui,
                this.createClassificationStrategies(),
                Arrays.<ITourRestructuring>asList(new OneStopPerPartOfFileRestructuring()),
                this.createStopOrdering(),
                createToursUi,
                changes,
                Collections.<ReviewRoundInfo>emptyList());
    }

    /**
     * The stop ordering algorithm of the cognitive support. It groups and sorts the stops using the
     * relation matchers configured in the settings (by default the same ones the Eclipse UI uses when
     * nothing has been configured). This is the compute intensive clustering; it is run inside a cancelable
     * background task and falls back to a faster mode for very large change sets.
     */
    private IStopOrdering createStopOrdering() {
        return new StopOrdering(StopOrderingSettings.createMatchers(this.getSettings().stopOrdering));
    }

    /**
     * The change classification strategies used while creating the tours. These mirror the basic
     * irrelevance filters of the cognitive support; the filters that parse Java sources
     * ({@link ImportChangeFilter}, {@link PackageDeclarationFilter}) are the compute intensive ones.
     * Each strategy needs a unique number for its classification.
     */
    private List<IChangeClassifier> createClassificationStrategies() {
        return Arrays.<IChangeClassifier>asList(
                new ImportChangeFilter(1),
                new PackageDeclarationFilter(2),
                new WhitespaceChangeFilter(3),
                new FileDeletionFilter(4),
                new BinaryFileFilter(5));
    }

}
