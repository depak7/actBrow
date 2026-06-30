package com.actbrow.actbrow.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.model.UserEntity;
import com.actbrow.actbrow.repository.UserRepository;
import com.actbrow.actbrow.service.GoogleIdTokenVerifier;
import com.actbrow.actbrow.service.SignupNotificationService;

@RestController
@RequestMapping("/auth")
public class AuthController {

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
		String idToken = body.get("idToken");
		String googleId = body.get("googleId");
		String email = body.get("email");
		String fullName = body.get("fullName");
		String pictureUrl = body.get("pictureUrl");

		if (idToken != null && !idToken.isBlank()) {
			try {
				Map<String, Object> claims = googleIdTokenVerifier.verifyAndDecode(idToken);
				if (claims == null) {
					return ResponseEntity.badRequest().body(Map.of("error", "Invalid Google token"));
				}
				Object sub = claims.get("sub");
				Object em = claims.get("email");
				googleId = sub != null ? String.valueOf(sub) : null;
				email = em != null ? String.valueOf(em) : null;
				if (fullName == null || fullName.isBlank()) {
					Object name = claims.get("name");
					if (name != null) {
						fullName = String.valueOf(name);
					}
				}
				if (pictureUrl == null || pictureUrl.isBlank()) {
					Object pic = claims.get("picture");
					if (pic != null) {
						pictureUrl = String.valueOf(pic);
					}
				}
			}
			catch (Exception exception) {
				String msg = exception.getMessage() != null ? exception.getMessage() : "Google sign-in failed";
				return ResponseEntity.badRequest().body(Map.of("error", msg));
			}
		}

		if (googleId == null || googleId.isBlank() || email == null || email.isBlank()) {
			return ResponseEntity.badRequest().body(
				Map.of("error", "Valid Google sign-in (idToken) or googleId and email are required"));
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
	public ResponseEntity<?> getCurrentUser() {
		// Dashboard auth is currently unauthenticated. Frontend manages session state via the
		// userId returned from /auth/google. Wire proper session/JWT before multi-customer deploy.
		return ResponseEntity.ok(Map.of("authenticated", false));
	}

	private String generateApiKey() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
