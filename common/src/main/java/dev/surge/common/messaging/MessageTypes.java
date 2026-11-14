package dev.surge.common.messaging;

/** Broker names and the {@code type} header values shared by producers and consumers. */
public final class MessageTypes {

    public static final String ORDERS_TOPIC = "orders.v1";
    public static final String CATALOG_TOPIC = "catalog.v1";
    public static final String TICKETS_EXCHANGE = "surge.tickets";
    public static final String TICKET_REQUESTED_KEY = "ticket.requested";

    public static final String ORDERS_DESTINATION = "kafka:" + ORDERS_TOPIC;
    public static final String CATALOG_DESTINATION = "kafka:" + CATALOG_TOPIC;
    public static final String TICKET_REQUESTED_DESTINATION = "rabbit:" + TICKETS_EXCHANGE + "/" + TICKET_REQUESTED_KEY;

    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String SALES_RESET = "SalesReset";
    public static final String EVENT_SCHEDULED = "EventScheduled";
    public static final String TICKET_REQUESTED = "TicketRequested";

    private MessageTypes() {
    }
}
