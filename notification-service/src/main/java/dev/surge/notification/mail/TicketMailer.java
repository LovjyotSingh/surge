package dev.surge.notification.mail;

import dev.surge.notification.ticket.TicketData;
import dev.surge.notification.ticket.TicketPdfRenderer;
import jakarta.mail.internet.MimeMessage;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends the ticket mail: a plain-text summary with the download link and the PDF attached. */
@Component
public class TicketMailer {

    static final String FROM = "tickets@surge.local";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", Locale.ENGLISH);

    private final JavaMailSender mail;
    private final String publicBaseUrl;

    public TicketMailer(JavaMailSender mail,
                        @Value("${surge.notification.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.mail = mail;
        this.publicBaseUrl = publicBaseUrl;
    }

    public void send(String to, TicketData ticket, byte[] pdf) {
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(FROM);
            helper.setTo(to);
            helper.setSubject("Your Surge tickets — " + oneLine(ticket.eventTitle()));
            helper.setText(body(ticket), false);
            helper.addAttachment("surge-ticket-" + ticket.orderId() + ".pdf", new ByteArrayResource(pdf),
                    "application/pdf");
            mail.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new org.springframework.mail.MailPreparationException(e);
        }
    }

    String body(TicketData t) {
        return """
                Your tickets are confirmed.

                Event: %s
                Venue: %s
                When:  %s
                Seats: %s
                Total: %s

                Your ticket is attached. You can also download it any time (sign in first):
                %s/tickets/%d

                See you there,
                Surge
                """.formatted(oneLine(t.eventTitle()), oneLine(t.venueName()), WHEN.format(t.startsAt()),
                String.join(", ", t.seats()), TicketPdfRenderer.money(t.totalInr()), publicBaseUrl, t.orderId());
    }

    /** A title with a line break would split the subject header; mail headers must stay on one line. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\r\n]+", " ").trim();
    }
}
