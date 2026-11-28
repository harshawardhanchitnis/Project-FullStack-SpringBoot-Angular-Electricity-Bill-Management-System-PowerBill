package com.powerbill.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class IdentityMail {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityMail.class);
    private final JavaMailSender sender;
    private final String from;
    private final String frontendUrl;

    IdentityMail(JavaMailSender sender, @Value("${gridiq.auth.mail-from}") String from,
            @Value("${gridiq.auth.frontend-url}") String frontendUrl) {
        this.sender = sender;
        this.from = from;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Async
    @TransactionalEventListener
    void deliver(Mail event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.recipient());
        message.setSubject(event.subject());
        message.setText(event.text() + "\n\n" + frontendUrl + event.path() + "#token=" + event.token());
        try {
            sender.send(message);
        } catch (MailException error) {
            LOG.warn("Could not deliver an identity email ({})", error.getClass().getSimpleName());
        }
    }

    record Mail(String recipient, String subject, String text, String path, String token) {}
}
