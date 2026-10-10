package de.setsoftware.reviewtool.ticketconnectors.youtrack;

import static org.junit.Assert.assertEquals;

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

import com.sun.net.httpserver.HttpServer;

import de.setsoftware.reviewtool.model.TicketInfo;

/**
 * Tests for {@link YouTrackConnector}, against a local stub of the YouTrack REST API.
 */
public class YouTrackConnectorTest {

    private HttpServer server;
    private String activitiesJson = "[]";

    @Before
    public void setUp() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/api/issues/TIC-1/activities", (exchange) -> {
            final byte[] body = this.activitiesJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        this.server.start();
    }

    @After
    public void tearDown() {
        this.server.stop(0);
    }

    private YouTrackConnector createConnector() {
        return new YouTrackConnector(
                "http://127.0.0.1:" + this.server.getAddress().getPort() + "/",
                "token", "Review remarks", "State", "Subsystem",
                "In Review", "In Progress", "Ready for Review", "Reopened", "Done", null);
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

}
