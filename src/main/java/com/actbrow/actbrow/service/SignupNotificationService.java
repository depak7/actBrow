package com.actbrow.actbrow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.actbrow.actbrow.config.NotificationProperties;
import com.actbrow.actbrow.model.UserEntity;
import com.actbrow.actbrow.model.WaitlistEntry;

/**
 * Sends a "new user signed up" email to the configured admin recipient. Sends are async and
 * fail-safe: any error is logged and swallowed so signup is never blocked or broken by mail.
 */
@Service
public class SignupNotificationService {

	private static final Logger log = LoggerFactory.getLogger(SignupNotificationService.class);

	private final ObjectProvider<JavaMailSender> mailSenderProvider;
	private final NotificationProperties properties;

	public SignupNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider,
			NotificationProperties properties) {
		this.mailSenderProvider = mailSenderProvider;
		this.properties = properties;
	}

	@Async
	public void notifyNewSignup(UserEntity user) {
		String body = "A new user just signed up for actbrow.\n\n"
			+ "Email:      " + user.getEmail() + "\n"
			+ "Full name:  " + orNone(user.getFullName()) + "\n"
			+ "User ID:    " + user.getId() + "\n"
			+ "Signed up:  " + user.getCreatedAt() + "\n";
		send("New actbrow signup: " + user.getEmail(), body, user.getEmail());
	}

	@Async
	public void notifyNewWaitlist(WaitlistEntry entry) {
		String body = "A new user just joined the actbrow waitlist.\n\n"
			+ "Email:     " + entry.getEmail() + "\n"
			+ "Name:      " + orNone(entry.getName()) + "\n"
			+ "Company:   " + orNone(entry.getCompany()) + "\n"
			+ "Use case:  " + orNone(entry.getUseCase()) + "\n"
			+ "Joined:    " + entry.getCreatedAt() + "\n";
		send("New actbrow waitlist signup: " + entry.getEmail(), body, entry.getEmail());
	}

	/** Shared, fail-safe send. No-ops (with a log line) when disabled or unconfigured. */
	private void send(String subject, String body, String contextEmail) {
		if (!properties.signupEnabled()) {
			log.debug("Signup notification disabled; skipping email for {}", contextEmail);
			return;
		}
		String recipient = properties.signupRecipient();
		if (recipient == null || recipient.isBlank()) {
			log.warn("Signup notification enabled but actbrow.notifications.signup-recipient is blank; skipping");
			return;
		}
		JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
		if (mailSender == null) {
			log.warn("Signup notification enabled but no JavaMailSender is configured; skipping");
			return;
		}

		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setTo(recipient);
			if (properties.from() != null && !properties.from().isBlank()) {
				message.setFrom(properties.from());
			}
			message.setSubject(subject);
			message.setText(body);
			mailSender.send(message);
			log.info("Sent notification for {} to {}", contextEmail, recipient);
		}
		catch (Exception exception) {
			log.error("Failed to send notification for {}", contextEmail, exception);
		}
	}

	private static String orNone(String value) {
		return value != null && !value.isBlank() ? value : "(none)";
	}
}
