package de.setsoftware.reviewtool.ticketconnectors.youtrack;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.ParseException;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.model.EndTransition;
import de.setsoftware.reviewtool.model.EndTransition.Type;
import de.setsoftware.reviewtool.model.ITicketConnector;
import de.setsoftware.reviewtool.model.ITicketData;
import de.setsoftware.reviewtool.model.TicketInfo;
import de.setsoftware.reviewtool.model.TicketLinkSettings;

/**
 * Ticket connector that uses the YouTrack REST API (api/issues, api/issues/.../activities).
 * Review remarks are persisted in a text custom field of the YouTrack issue, state changes
 * are performed by setting the state custom field (workflow rules in YouTrack may veto them).
 * Authentication uses a permanent token (HTTP bearer authentication).
 */
public class YouTrackConnector implements ITicketConnector {

    private static final String ISSUE_FIELDS =
            "idReadable,summary,updated,parent(issues(idReadable,summary)),customFields(name,value(name,text))";

    /**
     * Wrapper for the JSON data of a YouTrack issue.
     */
    private final class YouTrackTicket implements ITicketData {

        private final JsonObject issue;
        private JsonArray activities;

        public YouTrackTicket(JsonObject issue) {
            this.issue = issue;
        }

        private JsonArray getActivities() {
            if (this.activities == null) {
                this.activities = YouTrackConnector.this.loadStateActivities(this.getId());
            }
            return this.activities;
        }

        @Override
        public String getReviewData() {
            final JsonObject field = YouTrackConnector.this.findCustomField(
                    this.issue, YouTrackConnector.this.reviewFieldName);
            if (field == null) {
                return "";
            }
            final JsonValue value = field.get("value");
            if (value == null || value.isNull() || !value.isObject()) {
                return "";
            }
            final JsonValue text = value.asObject().get("text");
            return text == null || text.isNull() ? "" : text.asString();
        }

        @Override
        public String getReviewerForRound(int number) {
            int count = 0;
            for (final JsonValue v : this.getActivities()) {
                if (YouTrackConnector.this.isTransitionToReview(v)) {
                    count++;
                    if (count == number) {
                        return YouTrackConnector.this.getAuthor(v).toUpperCase();
                    }
                }
            }
            return YouTrackConnector.this.determineCurrentUser().toUpperCase();
        }

        @Override
        public Date getEndTimeForRound(int number) {
            int count = 0;
            for (final JsonValue v : this.getActivities()) {
                if (YouTrackConnector.this.isTransitionToReview(v)) {
                    count++;
                    if (count == number) {
                        return new Date(v.asObject().get("timestamp").asLong());
                    }
                }
            }
            return new Date();
        }

        @Override
        public int getCurrentRound() {
            int count = 0;
            for (final JsonValue v : this.getActivities()) {
                if (YouTrackConnector.this.isTransitionToReview(v)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public TicketInfo getTicketInfo() {
            return YouTrackConnector.this.mapTicket(this.issue, this.determineReviewers());
        }

        private Set<String> determineReviewers() {
            final Set<String> reviewers = new LinkedHashSet<>();
            for (final JsonValue v : this.getActivities()) {
                if (YouTrackConnector.this.isTransitionToReview(v)) {
                    final String reviewer = YouTrackConnector.this.getAuthor(v);
                    if (!reviewer.isEmpty()) {
                        reviewers.add(reviewer.toUpperCase());
                    }
                }
            }
            return reviewers;
        }

        @Override
        public String getId() {
            return this.issue.get("idReadable").asString();
        }

    }

    private final String url;
    private final String token;
    private final String reviewFieldName;
    private final String stateFieldName;
    private final String componentFieldName;
    private final String reviewStateName;
    private final String implementationStateName;
    private final String readyForReviewStateName;
    private final String rejectedStateName;
    private final String doneStateName;
    private final Map<String, String> filtersForReview;
    private final Map<String, String> filtersForFixing;
    private final TicketLinkSettings linkSettings;
    private String currentUser;

    public YouTrackConnector(
            String url,
            String token,
            String reviewFieldName,
            String stateFieldName,
            String componentFieldName,
            String reviewState,
            String implementationState,
            String readyForReviewState,
            String rejectedState,
            String doneState,
            TicketLinkSettings linkSettings) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
        this.reviewFieldName = reviewFieldName;
        this.stateFieldName = stateFieldName.isEmpty() ? "State" : stateFieldName;
        this.componentFieldName = componentFieldName.isEmpty() ? "Subsystem" : componentFieldName;
        this.reviewStateName = reviewState;
        this.implementationStateName = implementationState;
        this.readyForReviewStateName = readyForReviewState;
        this.rejectedStateName = rejectedState;
        this.doneStateName = doneState;
        this.filtersForReview = new LinkedHashMap<>();
        this.filtersForFixing = new LinkedHashMap<>();
        this.linkSettings = linkSettings;
    }

    /**
     * Adds a filter to the set of known filters.
     * @param name The filter's name.
     * @param query The YouTrack search query for the filter.
     * @param forReview true iff it is a filter for tickets to review, false iff it is for fixing.
     */
    public void addFilter(String name, String query, boolean forReview) {
        if (forReview) {
            this.filtersForReview.put(name, query);
        } else {
            this.filtersForFixing.put(name, query);
        }
    }

    @Override
    public Set<String> getFilterNamesForReview() {
        return this.filtersForReview.keySet();
    }

    @Override
    public Set<String> getFilterNamesForFixing() {
        return this.filtersForFixing.keySet();
    }

    @Override
    public List<TicketInfo> getTicketsForFilter(String filterName) {
        if (this.filtersForReview.containsKey(filterName)) {
            return this.queryTickets(this.filtersForReview.get(filterName));
        } else {
            return this.queryTickets(this.filtersForFixing.get(filterName));
        }
    }

    private List<TicketInfo> queryTickets(String query) {
        final String searchUrl = String.format(
                "%s/api/issues?$top=200&query=%s&fields=%s",
                this.url,
                this.urlEncode(query),
                ISSUE_FIELDS);
        final JsonArray issues = this.performGet(searchUrl).asArray();
        final List<TicketInfo> ret = new ArrayList<>();
        for (final JsonValue issue : issues) {
            ret.add(this.mapTicket(issue.asObject(), new LinkedHashSet<String>()));
        }
        return ret;
    }

    private TicketInfo mapTicket(JsonObject issue, Set<String> reviewers) {
        return new TicketInfo(
                issue.get("idReadable").asString(),
                this.asStringOrEmpty(issue.get("summary")),
                this.getCustomFieldValueName(issue, this.stateFieldName),
                "",
                this.getCustomFieldValueName(issue, this.componentFieldName),
                this.getParentSummary(issue),
                reviewers,
                this.getUpdated(issue));
    }

    private String getParentSummary(JsonObject issue) {
        final JsonValue parent = issue.get("parent");
        if (parent == null || parent.isNull() || !parent.isObject()) {
            return null;
        }
        final JsonValue issues = parent.asObject().get("issues");
        if (issues == null || !issues.isArray() || issues.asArray().isEmpty()) {
            return null;
        }
        return this.asStringOrEmpty(issues.asArray().get(0).asObject().get("summary"));
    }

    private Date getUpdated(JsonObject issue) {
        final JsonValue updated = issue.get("updated");
        if (updated == null || updated.isNull()) {
            return new Date(0);
        }
        return new Date(updated.asLong());
    }

    private String asStringOrEmpty(JsonValue value) {
        return value == null || value.isNull() ? "" : value.asString();
    }

    /**
     * Returns the custom field with the given name, or null if the issue does not have it.
     */
    private JsonObject findCustomField(JsonObject issue, String fieldName) {
        final JsonValue fields = issue.get("customFields");
        if (fields == null || !fields.isArray()) {
            return null;
        }
        for (final JsonValue field : fields.asArray()) {
            final JsonObject fieldObject = field.asObject();
            final JsonValue name = fieldObject.get("name");
            if (name != null && !name.isNull() && name.asString().equals(fieldName)) {
                return fieldObject;
            }
        }
        return null;
    }

    private String getCustomFieldValueName(JsonObject issue, String fieldName) {
        final JsonObject field = this.findCustomField(issue, fieldName);
        if (field == null) {
            return "";
        }
        final JsonValue value = field.get("value");
        if (value == null || value.isNull() || !value.isObject()) {
            return "";
        }
        final JsonValue name = value.asObject().get("name");
        return name == null || name.isNull() ? "" : name.asString();
    }

    @Override
    public ITicketData loadTicket(String ticketKey) {
        final String getUrl = String.format(
                "%s/api/issues/%s?fields=%s", this.url, ticketKey, ISSUE_FIELDS);
        final JsonValue result;
        try {
            result = this.performGet(getUrl);
        } catch (final ReviewtoolException e) {
            Logger.debug("could not load ticket " + ticketKey + ": " + e);
            return null;
        }
        if (!result.isObject() || result.asObject().get("idReadable") == null) {
            return null;
        }
        return new YouTrackTicket(result.asObject());
    }

    @Override
    public void saveReviewData(String ticketKey, String newData) {
        final JsonObject value = new JsonObject();
        value.add("text", newData);
        final JsonObject field = new JsonObject();
        field.add("name", this.reviewFieldName);
        field.add("$type", "TextIssueCustomField");
        field.add("value", value);
        final JsonArray fields = new JsonArray();
        fields.add(field);
        final JsonObject command = new JsonObject();
        command.add("customFields", fields);
        this.performPost(String.format("%s/api/issues/%s", this.url, ticketKey), command);
    }

    @Override
    public void startReviewing(String ticketKey) {
        this.performStateTransitionIfPossible(ticketKey, this.reviewStateName);
    }

    @Override
    public void startFixing(String ticketKey) {
        this.performStateTransitionIfPossible(ticketKey, this.implementationStateName);
    }

    @Override
    public void changeStateToReadyForReview(String ticketKey) {
        this.performStateTransitionIfPossible(ticketKey, this.readyForReviewStateName);
    }

    @Override
    public List<EndTransition> getPossibleTransitionsForReviewEnd(String ticketKey) {
        final List<EndTransition> ret = new ArrayList<>();
        ret.add(new EndTransition(this.doneStateName, this.doneStateName, Type.OK));
        ret.add(new EndTransition(this.rejectedStateName, this.rejectedStateName, Type.REJECTION));
        return ret;
    }

    @Override
    public void changeStateAtReviewEnd(String ticketKey, EndTransition transition) {
        this.setState(ticketKey, transition.getInternalName());
    }

    private void performStateTransitionIfPossible(String ticketKey, String targetStateName) {
        final ITicketData ticket = this.loadTicket(ticketKey);
        if (ticket == null) {
            Logger.info("Ticket " + ticketKey + " could not be loaded, state not changed.");
            return;
        }
        final String currentState = this.getCustomFieldValueName(
                ((YouTrackTicket) ticket).issue, this.stateFieldName);
        if (currentState.equals(targetStateName)) {
            Logger.debug("Did not transition, already in state " + targetStateName);
            return;
        }
        try {
            this.setState(ticketKey, targetStateName);
        } catch (final ReviewtoolException e) {
            //YouTrack workflow rules can veto state changes; mimic the behavior of the
            //  other connectors and silently stay in the old state in this case
            Logger.info("Could not transition " + ticketKey + " to " + targetStateName + ": " + e);
        }
    }

    private void setState(String ticketKey, String stateName) {
        final JsonObject value = new JsonObject();
        value.add("name", stateName);
        final JsonObject field = new JsonObject();
        field.add("name", this.stateFieldName);
        field.add("$type", "StateIssueCustomField");
        field.add("value", value);
        final JsonArray fields = new JsonArray();
        fields.add(field);
        final JsonObject command = new JsonObject();
        command.add("customFields", fields);
        this.performPost(String.format("%s/api/issues/%s", this.url, ticketKey), command);
    }

    private JsonArray loadStateActivities(String ticketKey) {
        final String getUrl = String.format(
                "%s/api/issues/%s/activities?categories=CustomFieldCategory"
                        + "&fields=timestamp,author(login),field(presentation),added(name),removed(name)",
                this.url,
                ticketKey);
        return this.performGet(getUrl).asArray();
    }

    private boolean isTransitionToReview(JsonValue activity) {
        final JsonObject a = activity.asObject();
        final JsonValue field = a.get("field");
        if (field == null || field.isNull() || !field.isObject()) {
            return false;
        }
        final JsonValue presentation = field.asObject().get("presentation");
        if (presentation == null || presentation.isNull()
                || !presentation.asString().equals(this.stateFieldName)) {
            return false;
        }
        final JsonValue added = a.get("added");
        if (added == null || !added.isArray()) {
            return false;
        }
        for (final JsonValue v : added.asArray()) {
            final JsonValue name = v.asObject().get("name");
            if (name != null && !name.isNull() && name.asString().equals(this.reviewStateName)) {
                return true;
            }
        }
        return false;
    }

    private String getAuthor(JsonValue activity) {
        final JsonValue author = activity.asObject().get("author");
        if (author == null || author.isNull() || !author.isObject()) {
            return "";
        }
        final JsonValue login = author.asObject().get("login");
        return login == null || login.isNull() ? "" : login.asString();
    }

    private String determineCurrentUser() {
        if (this.currentUser == null) {
            try {
                final JsonValue me = this.performGet(this.url + "/api/users/me?fields=login");
                this.currentUser = this.asStringOrEmpty(me.asObject().get("login"));
            } catch (final ReviewtoolException e) {
                Logger.debug("could not determine current user: " + e);
                this.currentUser = "";
            }
        }
        return this.currentUser;
    }

    @Override
    public TicketLinkSettings getLinkSettings() {
        return this.linkSettings;
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            throw new ReviewtoolException(e);
        }
    }

    /**
     * Performs an HTTP GET request and returns the resulting JSON data.
     */
    public JsonValue performGet(String getUrl) {
        final StringBuilder b = new StringBuilder();
        try {
            this.communicate(getUrl, "GET", null, (InputStream input) -> {
                try {
                    final InputStreamReader reader = new InputStreamReader(input, "UTF-8");
                    int ch;
                    while ((ch = reader.read()) >= 0) {
                        b.append((char) ch);
                    }
                } catch (final IOException e) {
                    throw new ReviewtoolException(e);
                }
            });
        } catch (final IOException e) {
            throw new ReviewtoolException(e);
        }
        final String data = b.toString();
        try {
            return Json.parse(data);
        } catch (final ParseException e) {
            throw new ReviewtoolException("exception parsing: " + data, e);
        }
    }

    private void performPost(String postUrl, JsonObject json) {
        try {
            this.communicate(postUrl, "POST", json.toString(), (InputStream s) -> { });
        } catch (final IOException e) {
            throw new ReviewtoolException(e);
        }
    }

    /**
     * Sends and receives data.
     */
    private void communicate(String requestUrl, String method, String data,
            Consumer<InputStream> resultConsumer) throws IOException {
        Logger.debug("communicate to YouTrack: " + method + " " + this.trimArgs(requestUrl));
        final HttpURLConnection c = (HttpURLConnection) new URL(requestUrl).openConnection();
        c.setRequestMethod(method);
        c.addRequestProperty("Content-Type", "application/json");
        c.addRequestProperty("Accept", "application/json");
        c.addRequestProperty("Authorization", "Bearer " + this.token);
        c.setDoOutput(data != null);
        c.connect();
        if (data != null) {
            try (OutputStream outputStream = c.getOutputStream()) {
                outputStream.write(data.getBytes("UTF-8"));
            }
        }
        try {
            final InputStream s = c.getInputStream();
            resultConsumer.accept(s);
            s.close();
        } catch (final IOException e) {
            throw new IOException(e.getMessage() + "; server said: " + this.readErrorStream(c), e);
        } finally {
            c.disconnect();
        }
    }

    private String readErrorStream(HttpURLConnection c) {
        try (InputStream s = c.getErrorStream()) {
            if (s == null) {
                return "";
            }
            final StringBuilder b = new StringBuilder();
            int r;
            while ((r = s.read()) >= 0) {
                b.append((char) r);
            }
            return b.toString();
        } catch (final IOException e) {
            return "(could not read error stream: " + e + ")";
        }
    }

    private String trimArgs(String requestUrl) {
        final int end = requestUrl.indexOf('?');
        return end < 0 ? requestUrl : requestUrl.substring(0, end);
    }

}
