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
		if (!properties.signupEnabled()) {
			log.debug("Signup notification disabled; skipping email for {}", user.getEmail());
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
			message.setSubject("New actbrow signup: " + user.getEmail());
			message.setText(buildBody(user));
			mailSender.send(message);
			log.info("Sent signup notification for {} to {}", user.getEmail(), recipient);
		}
		catch (Exception exception) {
			log.error("Failed to send signup notification for {}", user.getEmail(), exception);
		}
	}

	private String buildBody(UserEntity user) {
		return "A new user just signed up for actbrow.\n\n"
			+ "Email:      " + user.getEmail() + "\n"
			+ "Full name:  " + (user.getFullName() != null ? user.getFullName() : "(none)") + "\n"
			+ "User ID:    " + user.getId() + "\n"
			+ "Signed up:  " + user.getCreatedAt() + "\n";
	}
}
