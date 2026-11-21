package dev.surge.auth.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

/** S10: the sender is configurable (MAIL_FROM), so a real provider can send from a verified domain. */
class VerificationMailerFromTest {

    @Test
    void theCodeIsSentFromTheConfiguredAddress() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        new VerificationMailer(mail, "Surge <no-reply@tickets.example.com>").send("buyer@example.com", "123456");

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail).send(sent.capture());
        InternetAddress from = (InternetAddress) sent.getValue().getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@tickets.example.com");
        assertThat(from.getPersonal()).isEqualTo("Surge");
    }
}
