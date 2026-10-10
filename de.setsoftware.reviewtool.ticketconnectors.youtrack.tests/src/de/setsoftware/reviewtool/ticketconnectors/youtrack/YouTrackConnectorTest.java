package de.setsoftware.reviewtool.ticketconnectors.youtrack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.setsoftware.reviewtool.base.ReviewtoolException;
import de.setsoftware.reviewtool.model.TicketInfo;

/**
 * Tests for {@link YouTrackConnector}, against a local stub of the YouTrack REST API.
 */
public class YouTrackConnectorTest {

    private HttpServer server;
    private String activitiesJson = "[]";
    private String issuesJson = "[]";
    private int userStatus = 200;

    @Before
    public void setUp() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/api/issues/TIC-1/activities", (exchange) -> {
            respond(exchange, 200, this.activitiesJson);
        });
        this.server.createContext("/api/issues", (exchange) -> {
            respond(exchange, 200, this.issuesJson);
        });
        this.server.createContext("/api/users/me", (exchange) -> {
            respond(exchange, this.userStatus, this.userStatus == 200 ? "{\"login\":\"marius\"}" : "{}");
        });
        this.server.start();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        final byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @After
    public void tearDown() {
        this.server.stop(0);
    }

    private YouTrackConnector createConnector() {
        final YouTrackConnector connector = new YouTrackConnector(
                "http://127.0.0.1:" + this.server.getAddress().getPort() + "/",
                "token", "Review remarks", "State", "Subsystem",
                "In Review", "In Progress", "Ready for Review", "Reopened", "Done", null);
        connector.addFilter("Review", "State: {Ready for Review}", true);
        connector.addFilter("Fixing", "State: Reopened", false);
        return connector;
    }

    private static String stateChange(long timestamp, String author, String from, String to) {
        return "{\"timestamp\":" + timestamp + ",\"author\":{\"login\":\"" + author + "\"},"
                + "\"field\":{\"presentation\":\"State\"},"
                + "\"removed\":[{\"name\":\"" + from + "\"}],\"added\":[{\"name\":\"" + to + "\"}]}";
    }

    private static TicketInfo ticket() {
        return new TicketInfo("TIC-1", "summary", "Ready for Review", "", "Core", null,
                Collections.<String>emptySet(), new Date(1000));
    }

    @Test
    public void testHistoryOfReviewedTicket() {
        this.activitiesJson = "["
                + stateChange(2000, "dev", "In Progress", "Ready for Review") + ","
                + stateChange(3000, "alice", "Ready for Review", "In Review") + ","
                + "{\"timestamp\":3500,\"author\":{\"login\":\"bob\"},\"field\":{\"presentation\":\"Subsystem\"},"
                + "\"removed\":[{\"name\":\"A\"}],\"added\":[{\"name\":\"B\"}]},"
                + stateChange(4000, "alice", "In Review", "Reopened") + ","
                + stateChange(5000, "dev", "Reopened", "In Progress") + ","
                + stateChange(6000, "dev", "In Progress", "Ready for Review") + ","
                + stateChange(7000, "bob", "Ready for Review", "In Review") + ","
                + stateChange(8000, "bob", "In Review", "Reopened") + ","
                + stateChange(9000, "dev", "Reopened", "Ready for Review")
                + "]";

        final TicketInfo result = this.createConnector().addHistory(ticket());

        assertEquals(new LinkedHashSet<>(Arrays.asList("ALICE", "BOB")), result.getReviewers());
        assertEquals("Reopened", result.getPreviousState());
        assertEquals(new Date(9000), result.getWaitingSince());
        // the basic information is kept
        assertEquals("TIC-1", result.getId());
        assertEquals("Ready for Review", result.getState());
        assertEquals("Core", result.getComponent());
    }

    @Test
    public void testHistoryWithoutStateChanges() {
        this.activitiesJson = "[]";

        final TicketInfo result = this.createConnector().addHistory(ticket());

        assertEquals(Collections.emptySet(), result.getReviewers());
        assertEquals("", result.getPreviousState());
        assertEquals(new Date(1000), result.getWaitingSince());
    }

    @Test
    public void testConnectionReport() {
        this.issuesJson = "[{\"idReadable\":\"TIC-1\",\"summary\":\"s\",\"customFields\":["
                + "{\"name\":\"State\",\"value\":{\"name\":\"Ready for Review\"}},"
                + "{\"name\":\"Review remarks\",\"value\":null}]}]";

        final String report = this.createConnector().testConnection();

        assertTrue(report, report.contains("Connected as marius."));
        assertTrue(report, report.contains("Query for review (State: {Ready for Review}): 1 ticket(s)."));
        // the configured component field does not exist
        assertTrue(report, report.contains("Warning: the tickets have no field 'Subsystem'"));
        assertTrue(report, !report.contains("no field 'State'"));
    }

    @Test
    public void testConnectionWithoutTickets() {
        final String report = this.createConnector().testConnection();
        assertTrue(report, report.contains("the queries do not find any tickets"));
    }

    @Test
    public void testConnectionWithWrongToken() {
        this.userStatus = 401;
        try {
            this.createConnector().testConnection();
            fail("a rejected token must be reported");
        } catch (final ReviewtoolException e) {
            // expected
        }
    }

}
