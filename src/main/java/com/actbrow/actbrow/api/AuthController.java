package com.actbrow.actbrow.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.model.UserEntity;
import com.actbrow.actbrow.repository.UserRepository;
import com.actbrow.actbrow.service.GoogleIdTokenVerifier;
import com.actbrow.actbrow.service.SignupNotificationService;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private static final Logger log = LoggerFactory.getLogger(AuthController.class);

	private final UserRepository userRepository;
	private final GoogleIdTokenVerifier googleIdTokenVerifier;
	private final SignupNotificationService signupNotificationService;
	private final SecureRandom secureRandom = new SecureRandom();

	public AuthController(UserRepository userRepository, GoogleIdTokenVerifier googleIdTokenVerifier,
			SignupNotificationService signupNotificationService) {
		this.userRepository = userRepository;
		this.googleIdTokenVerifier = googleIdTokenVerifier;
		this.signupNotificationService = signupNotificationService;
	}

	@PostMapping("/google")
	public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body) {
		String idToken = body == null ? null : body.get("idToken");
		if (idToken == null || idToken.isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Valid Google sign-in (idToken) is required"));
		}

		String googleId;
		String email;
		String fullName;
		String pictureUrl;
		try {
			Map<String, Object> claims = googleIdTokenVerifier.verifyAndDecode(idToken);
			if (claims == null) {
				return ResponseEntity.badRequest().body(Map.of("error", "Invalid Google token"));
			}
			Object sub = claims.get("sub");
			Object em = claims.get("email");
			googleId = sub != null ? String.valueOf(sub) : null;
			email = em != null ? String.valueOf(em) : null;
			Object name = claims.get("name");
			fullName = name != null ? String.valueOf(name) : null;
			Object pic = claims.get("picture");
			pictureUrl = pic != null ? String.valueOf(pic) : null;
			Object emailVerified = claims.get("email_verified");
			if (emailVerified != null && !Boolean.parseBoolean(String.valueOf(emailVerified))
				&& !"true".equalsIgnoreCase(String.valueOf(emailVerified))) {
				return ResponseEntity.badRequest().body(Map.of("error", "Google email is not verified"));
			}
		}
		catch (Exception exception) {
			log.warn("Google ID token verification failed", exception);
			return ResponseEntity.badRequest().body(Map.of("error", "Google sign-in failed"));
		}

		if (googleId == null || googleId.isBlank() || email == null || email.isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Valid Google sign-in (idToken) is required"));
		}

		final String resolvedGoogleId = googleId;
		final String resolvedEmail = email;
		final String resolvedFullName = fullName;
		final String resolvedPictureUrl = pictureUrl;

		UserEntity user = userRepository.findByGoogleId(resolvedGoogleId)
			.orElseGet(() -> {
				UserEntity newUser = new UserEntity();
				newUser.setGoogleId(resolvedGoogleId);
				newUser.setEmail(resolvedEmail);
				newUser.setFullName(resolvedFullName);
				newUser.setProfilePictureUrl(resolvedPictureUrl);
				newUser.setApiKey(generateApiKey());
				UserEntity saved = userRepository.save(newUser);
				signupNotificationService.notifyNewSignup(saved);
				return saved;
			});
		if (user.getApiKey() == null || user.getApiKey().isBlank()) {
			user.setApiKey(generateApiKey());
			user = userRepository.save(user);
		}

		Map<String, Object> userJson = new LinkedHashMap<>();
		userJson.put("id", user.getId());
		userJson.put("email", user.getEmail());
		userJson.put("fullName", user.getFullName());
		userJson.put("pictureUrl", user.getProfilePictureUrl());
		return ResponseEntity.ok(Map.of(
			"success", true,
			"user", userJson,
			"apiKey", user.getApiKey()
		));
	}

	@GetMapping("/me")
	public ResponseEntity<?> getCurrentUser(
		@RequestHeader(value = "X-Actbrow-Auth-Type", required = false) String authType,
		@RequestHeader(value = "X-User-Id", required = false) String userId) {
		if (!"account".equals(authType) || userId == null || userId.isBlank()) {
			return ResponseEntity.ok(Map.of("authenticated", false));
		}
		return userRepository.findById(userId)
			.<ResponseEntity<?>>map(user -> {
				Map<String, Object> userJson = new LinkedHashMap<>();
				userJson.put("id", user.getId());
				userJson.put("email", user.getEmail());
				userJson.put("fullName", user.getFullName());
				userJson.put("pictureUrl", user.getProfilePictureUrl());
				return ResponseEntity.ok(Map.of("authenticated", true, "user", userJson));
			})
			.orElseGet(() -> ResponseEntity.ok(Map.of("authenticated", false)));
	}

	private String generateApiKey() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
