package dev.surge.notification.api;

import dev.surge.notification.error.TicketNotFoundException;
import dev.surge.notification.ticket.Ticket;
import dev.surge.notification.ticket.TicketRepository;
import dev.surge.notification.ticket.TicketStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TicketController {

    private final TicketRepository tickets;
    private final TicketStore store;

    public TicketController(TicketRepository tickets, TicketStore store) {
        this.tickets = tickets;
        this.store = store;
    }

    /** The owner's ticket. Someone else's ticket and a missing one look the same (404). */
    @GetMapping("/api/notifications/tickets/{orderId}.pdf")
    public ResponseEntity<byte[]> download(@RequestHeader(name = "X-User-Id", required = false) String userId,
                                           @PathVariable long orderId) {
        RequestIdentity.requireUser(userId);
        Ticket ticket = tickets.findById(orderId).filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new TicketNotFoundException(orderId));
        byte[] pdf = store.get(orderId).orElseThrow(() -> new TicketNotFoundException(orderId));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("surge-ticket-" + ticket.getOrderId() + ".pdf").build().toString())
                .body(pdf);
    }
}
