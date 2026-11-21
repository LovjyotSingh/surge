package dev.surge.auth.verify;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Emails the 6-digit verification code (plain text and HTML, the code in large type). The copy says the code expires
 * in 15 minutes and tells the reader where to look when Surge's emails go missing: Spam, including the Gmail app's
 * ☰ → Spam. Neither the address nor the code is ever logged.
 */
@Component
public class VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailer.class);

    static final String SUBJECT = "Your Surge verification code";

    private final JavaMailSender mail;
    private final String from;

    /** @param from {@code surge.mail.from} (env MAIL_FROM), e.g. {@code Surge <no-reply@example.com>} */
    public VerificationMailer(JavaMailSender mail, @Value("${surge.mail.from:no-reply@surge.local}") String from) {
        this.mail = mail;
        this.from = from;
    }

    /** Sends the code; throws a {@link MailException} when the mail server does not take it. */
    public void send(String to, String displayName, String code) {
        try {
            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(SUBJECT);
            helper.setText(text(displayName, code), html(displayName, code));
            mail.send(message);
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not build the verification email", e);
        }
    }

    /** {@link #send}, but a failure is only logged (signup must not fail because the mail server is down). */
    public boolean trySend(String to, String displayName, String code) {
        try {
            send(to, displayName, code);
            return true;
        } catch (MailException | IllegalArgumentException e) {
            log.warn("Could not send a verification email: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    static String text(String displayName, String code) {
        return """
                Hi %s,

                Your Surge verification code is:

                    %s

                Enter it on the Verify email page. It expires in 15 minutes and works once.
                If you did not sign up for Surge, you can ignore this email.

                Can't find our emails later (your tickets, for example)? Check your Spam or Junk folder.
                In the Gmail app, tap ☰ (the menu) → Spam, open the email and tap "Not spam".

                Surge
                """.formatted(oneLine(displayName), code);
    }

    static String html(String displayName, String code) {
        return """
                <!doctype html>
                <html><body style="font-family:Arial,Helvetica,sans-serif;color:#111">
                <p>Hi %s,</p>
                <p>Your Surge verification code is:</p>
                <p style="font-size:36px;font-weight:700;letter-spacing:8px;font-family:Consolas,Menlo,monospace;margin:16px 0">%s</p>
                <p>Enter it on the <strong>Verify email</strong> page. It <strong>expires in 15 minutes</strong> and works once.
                If you did not sign up for Surge, you can ignore this email.</p>
                <p style="color:#555">Can't find our emails later (your tickets, for example)? Check your <strong>Spam</strong>
                or Junk folder. In the Gmail app, tap <strong>☰</strong> (the menu) → <strong>Spam</strong>, open the email
                and tap <strong>Not spam</strong>.</p>
                <p>Surge</p>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(oneLine(displayName)), code);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
