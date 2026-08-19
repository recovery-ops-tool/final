package com.recoverpro.server.service.impl;

import com.recoverpro.server.service.EmailService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final MeterRegistry meterRegistry;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${app.mail.contact-recipient:}")
    private String contactRecipient;

    @Value("${app.alerts.ops-recipient:}")
    private String opsAlertRecipient;

    @Override
    public void sendPasswordResetOtp(String email, String otp, int expiryMinutes) {
        send(email, "Your password reset code",
                "Your password reset code is " + otp + ". It expires in " + expiryMinutes + " minutes. "
                        + "If you did not request this, you can ignore this email.");
    }

    @Override
    public void sendAccountLockedAlert(String email) {
        send(email, "Your account has been locked",
                "Your account was locked due to repeated failed login attempts. "
                        + "Contact your administrator if this wasn't you.");
    }

    @Override
    public void sendWelcomeEmail(String email, String firstName, String otp, int expiryMinutes) {
        send(email, "Welcome to RecoverPro",
                "Hi " + firstName + ", your account has been created. "
                        + "Your temporary sign-in code is " + otp + ", valid for " + expiryMinutes + " minutes.");
    }

    @Override
    public void sendContactEnquiry(String name, String company, String email, String message) {
        if (contactRecipient == null || contactRecipient.isBlank()) {
            log.warn("Contact enquiry from={} company={} email={} could not be delivered: "
                    + "app.mail.contact-recipient is not configured.", name, company, email);
            return;
        }
        // name/company/email/message are all attacker-controlled (public contact form) - strip
        // CR/LF before they reach a header-adjacent field to prevent email header injection.
        String safeName = sanitizeHeaderValue(name);
        String safeCompany = sanitizeHeaderValue(company);
        String safeReplyTo = sanitizeHeaderValue(email);

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setSubject("New contact enquiry from " + safeName);
        mail.setText("Name: " + safeName + "\nCompany: " + safeCompany + "\nReply-to: " + safeReplyTo
                + "\n\n" + message);
        if (isValidEmailAddress(email)) {
            mail.setReplyTo(email);
        }
        send(contactRecipient, mail);
    }

    @Override
    public void sendOpsAlert(String subject, String body) {
        String recipient = (opsAlertRecipient != null && !opsAlertRecipient.isBlank())
                ? opsAlertRecipient : contactRecipient;
        if (recipient == null || recipient.isBlank()) {
            log.warn("Ops alert not delivered (subject='{}'): neither app.alerts.ops-recipient nor "
                    + "app.mail.contact-recipient is configured.", subject);
            return;
        }
        send(recipient, "[RecoverPro ops] " + subject, body);
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setSubject(subject);
        message.setText(body);
        send(to, message);
    }

    private void send(String to, SimpleMailMessage message) {
        if (fromAddress == null || fromAddress.isBlank()) {
            log.warn("Email not sent (subject='{}', to={}): spring.mail.username is not configured.",
                    message.getSubject(), to);
            emailSendCounter("skipped_unconfigured").increment();
            return;
        }
        try {
            message.setFrom(fromAddress);
            message.setTo(to);
            mailSender.send(message);
            log.info("Email sent: subject='{}', to={}", message.getSubject(), to);
            emailSendCounter("sent").increment();
        } catch (Exception e) {
            log.error("Failed to send email: subject='{}', to={}", message.getSubject(), to, e);
            emailSendCounter("failed").increment();
        }
    }

    /** SYSTEM 12 TASK 12.2: email_send_events_total{outcome} -- every email this app sends funnels
     *  through this one method, so this is the single choke point for the metric. */
    private io.micrometer.core.instrument.Counter emailSendCounter(String outcome) {
        return io.micrometer.core.instrument.Counter.builder("email_send_events_total")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static String sanitizeHeaderValue(String raw) {
        if (raw == null) return "";
        String stripped = raw.replaceAll("[\\r\\n]+", " ").trim();
        return stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
    }

    private static boolean isValidEmailAddress(String email) {
        return email != null && email.matches("^[^\\s@\\r\\n]+@[^\\s@\\r\\n]+\\.[^\\s@\\r\\n]+$");
    }
}
