package de.setsoftware.reviewtool.ticketconnectors.youtrack;

import java.util.Collections;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import de.setsoftware.reviewtool.config.IConfigurator;
import de.setsoftware.reviewtool.config.IReviewConfigurable;
import de.setsoftware.reviewtool.model.TicketLinkSettings;

/**
 * Configurator for the YouTrack ticket connector, for the element "youtrackTicketStore".
 */
public class YouTrackConnectorConfigurator implements IConfigurator {

    @Override
    public Set<String> getRelevantElementNames() {
        return Collections.singleton("youtrackTicketStore");
    }

    @Override
    public void configure(Element xml, IReviewConfigurable configurable) {
        final String url = xml.getAttribute("url");
        final String linkPattern = xml.getAttribute("ticketLinkPattern");
        final String linkText = xml.getAttribute("ticketLinkText");
        final YouTrackConnector connector = new YouTrackConnector(
                url,
                xml.getAttribute("token"),
                xml.getAttribute("reviewRemarkField"),
                xml.getAttribute("stateField"),
                xml.getAttribute("componentField"),
                xml.getAttribute("reviewState"),
                xml.getAttribute("implementationState"),
                xml.getAttribute("readyForReviewState"),
                xml.getAttribute("rejectedState"),
                xml.getAttribute("doneState"),
                linkPattern.isEmpty()
                        ? new TicketLinkSettings(url + "/issue/%s", "Open in YouTrack")
                        : new TicketLinkSettings(linkPattern, linkText.isEmpty() ? "Open in YouTrack" : linkText));

        final NodeList children = xml.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node child = children.item(i);
            if (!(child instanceof Element)) {
                continue;
            }
            final Element e = (Element) child;
            if (e.getNodeName().equals("filter")) {
                connector.addFilter(
                        e.getAttribute("name"),
                        e.getAttribute("query"),
                        Boolean.parseBoolean(e.getAttribute("forReview")));
            }
        }
        configurable.configureWith(connector);
    }

}
