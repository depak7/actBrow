package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.actbrow.actbrow.config.NotificationProperties;
import com.actbrow.actbrow.model.UserEntity;

class SignupNotificationServiceTests {

	private static UserEntity newUser() {
		UserEntity user = new UserEntity();
		user.setId("user-123");
		user.setEmail("alice@example.com");
		user.setFullName("Alice Example");
		user.setGoogleId("g-1");
		user.setApiKey("ak_test");
		return user;
	}

	@SuppressWarnings("unchecked")
	private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
		ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(sender);
		return provider;
	}

	@Test
	void sendsEmailWithExpectedFieldsWhenEnabled() {
		JavaMailSender sender = mock(JavaMailSender.class);
		var props = new NotificationProperties(true, "admin@example.com", "noreply@example.com");
		var service = new SignupNotificationService(providerOf(sender), props);

		service.notifyNewSignup(newUser());

		ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(sender).send(captor.capture());
		SimpleMailMessage sent = captor.getValue();

		assertThat(sent.getTo()).containsExactly("admin@example.com");
		assertThat(sent.getFrom()).isEqualTo("noreply@example.com");
		assertThat(sent.getSubject()).isEqualTo("New actbrow signup: alice@example.com");
		assertThat(sent.getText()).contains("alice@example.com").contains("Alice Example").contains("user-123");
	}

	@Test
	void doesNotSendWhenDisabled() {
		JavaMailSender sender = mock(JavaMailSender.class);
		var props = new NotificationProperties(false, "admin@example.com", "noreply@example.com");
		var service = new SignupNotificationService(providerOf(sender), props);

		service.notifyNewSignup(newUser());

		verify(sender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	void doesNotSendWhenRecipientBlank() {
		JavaMailSender sender = mock(JavaMailSender.class);
		var props = new NotificationProperties(true, "  ", "noreply@example.com");
		var service = new SignupNotificationService(providerOf(sender), props);

		service.notifyNewSignup(newUser());

		verify(sender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	void swallowsExceptionFromMailSender() {
		JavaMailSender sender = mock(JavaMailSender.class);
		org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
			.when(sender).send(any(SimpleMailMessage.class));
		var props = new NotificationProperties(true, "admin@example.com", "noreply@example.com");
		var service = new SignupNotificationService(providerOf(sender), props);

		// must not throw — signup must never break because mail failed
		service.notifyNewSignup(newUser());

		verify(sender).send(any(SimpleMailMessage.class));
	}

	@Test
	void noSenderAvailableIsNoOp() {
		var props = new NotificationProperties(true, "admin@example.com", "noreply@example.com");
		var service = new SignupNotificationService(providerOf(null), props);

		// must not throw when no JavaMailSender bean exists
		service.notifyNewSignup(newUser());
	}

	/**
	 * Real end-to-end send through Gmail SMTP. Credentials are read from the environment so no
	 * secrets live in the repo; the test self-skips when they are absent. Tagged so it does not
	 * run on every build (it actually delivers an email). Run with:
	 *
	 * MAIL_USERNAME=you@gmail.com MAIL_PASSWORD=app-pw SIGNUP_NOTIFY_RECIPIENT=to@example.com \
	 *   mvnw test -Dtest=SignupNotificationServiceTests#realSendThroughGmail
	 */
	@Test
	@Tag("integration")
	void realSendThroughGmail() {
		String username = System.getenv("MAIL_USERNAME");
		String password = System.getenv("MAIL_PASSWORD");
		String recipient = System.getenv("SIGNUP_NOTIFY_RECIPIENT");
		assumeThat(username).as("MAIL_USERNAME not set — skipping live send").isNotBlank();
		assumeThat(password).as("MAIL_PASSWORD not set — skipping live send").isNotBlank();
		assumeThat(recipient).as("SIGNUP_NOTIFY_RECIPIENT not set — skipping live send").isNotBlank();

		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost("smtp.gmail.com");
		sender.setPort(587);
		sender.setUsername(username);
		sender.setPassword(password);
		var p = sender.getJavaMailProperties();
		p.put("mail.smtp.auth", "true");
		p.put("mail.smtp.starttls.enable", "true");

		SimpleMailMessage message = new SimpleMailMessage();
		message.setTo(recipient);
		message.setFrom(username);
		message.setSubject("New actbrow signup: integration-test@example.com (integration test)");
		message.setText("This is an automated integration-test email confirming SMTP works.");
		sender.send(message);
	}
}
