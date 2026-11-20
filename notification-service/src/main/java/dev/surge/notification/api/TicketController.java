package dev.surge.notification.api;

import dev.surge.common.security.CurrentIdentity;
import dev.surge.notification.error.TicketNotFoundException;
import dev.surge.notification.ticket.Ticket;
import dev.surge.notification.ticket.TicketRepository;
import dev.surge.notification.ticket.TicketStore;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TicketController {

    private final TicketRepository tickets;
    private final TicketStore store;

    public TicketController(TicketRepository tickets, TicketStore store) {
        this.tickets = tickets;
        this.store = store;
    }

    /**
     * The owner's ticket, for the signed-in user the verified identity token names (401 missing-user otherwise).
     * Someone else's ticket and a missing one look the same (404).
     *
     * <p>The order id binds as a number before the user check runs; that is safe because the identity chain already
     * admits only a signed-in gateway user to this path ({@code AccessRulesConfig}), so nobody else ever sees a 400.
     *
     * <p>SEC-21: a ticket carries personal data, so it is {@code Cache-Control: no-store} (no browser or proxy keeps
     * it after logout on a shared machine), {@code X-Content-Type-Options: nosniff}, and always a download
     * ({@code attachment}), never rendered inline.
     */
    @GetMapping("/api/notifications/tickets/{orderId}.pdf")
    public ResponseEntity<byte[]> download(@PathVariable long orderId) {
        String userId = CurrentIdentity.requireUser();
        Ticket ticket = tickets.findById(orderId).filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new TicketNotFoundException(orderId));
        byte[] pdf = store.get(orderId).orElseThrow(() -> new TicketNotFoundException(orderId));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("surge-ticket-" + ticket.getOrderId() + ".pdf").build().toString())
                .body(pdf);
    }
}
