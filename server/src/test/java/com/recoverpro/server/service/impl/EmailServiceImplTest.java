package com.recoverpro.server.service.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceImplTest {

    @Mock private JavaMailSender mailSender;

    private EmailServiceImpl service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new EmailServiceImpl(mailSender, meterRegistry);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@example.com");
        ReflectionTestUtils.setField(service, "contactRecipient", "sales@example.com");
    }

    @Test
    void sendPasswordResetOtp_sendsRealEmailNotJustALogLine() {
        service.sendPasswordResetOtp("user@example.com", "123456", 10);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("user@example.com");
        assertThat(sent.getText()).contains("123456");

        // SYSTEM 12 TASK 12.2: email_send_events_total appears in the registry after a real send.
        assertThat(meterRegistry.get("email_send_events_total").tag("outcome", "sent")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void sendContactEnquiry_sendsToConfiguredRecipient() {
        service.sendContactEnquiry("Jane", "Acme", "jane@acme.com", "Interested in a demo");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("sales@example.com");
    }

    @Test
    void sendContactEnquiry_stripsCrLfFromAttackerControlledFieldsBeforeSubject() {
        service.sendContactEnquiry("Jane\r\nBcc: attacker@evil.com", "Acme", "jane@acme.com", "hi");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        String subject = captor.getValue().getSubject();
        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void sendContactEnquiry_recipientNotConfigured_doesNotSendOrThrow() {
        ReflectionTestUtils.setField(service, "contactRecipient", "");

        service.sendContactEnquiry("Jane", "Acme", "jane@acme.com", "Interested in a demo");

        verify(mailSender, never()).send((SimpleMailMessage) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void send_mailNotConfigured_doesNotSendOrThrow() {
        ReflectionTestUtils.setField(service, "fromAddress", "");

        service.sendAccountLockedAlert("user@example.com");

        verify(mailSender, never()).send((SimpleMailMessage) org.mockito.ArgumentMatchers.any());
    }
}
