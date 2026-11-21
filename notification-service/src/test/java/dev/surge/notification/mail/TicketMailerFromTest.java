package dev.surge.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.surge.notification.ticket.TicketData;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

/** S10: the sender is configurable (MAIL_FROM), so a real provider can send from a verified domain. */
class TicketMailerFromTest {

    @Test
    void theTicketIsSentFromTheConfiguredAddress() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        TicketData ticket = new TicketData(7L, "u-1", 1L, "Show", "Venue",
                ZonedDateTime.of(2027, 1, 18, 19, 30, 0, 0, ZoneId.of("Asia/Kolkata")), List.of("A1"),
                new BigDecimal("1500.00"));

        new TicketMailer(mail, "https://surge.example.com", "Surge <tickets@tickets.example.com>")
                .send("buyer@example.com", ticket, new byte[] {'%', 'P', 'D', 'F'});

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        InternetAddress from = (InternetAddress) sent.getValue().getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("tickets@tickets.example.com");
        assertThat(from.getPersonal()).isEqualTo("Surge");
    }
}
