package com.actbrow.actbrow.api;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.actbrow.actbrow.model.TenantEntity;
import com.actbrow.actbrow.model.UserEntity;
import com.actbrow.actbrow.repository.TenantRepository;
import com.actbrow.actbrow.repository.UserRepository;
import com.actbrow.actbrow.service.GoogleIdTokenVerifier;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final UserRepository userRepository;
	private final TenantRepository tenantRepository;
	private final GoogleIdTokenVerifier googleIdTokenVerifier;

	public AuthController(UserRepository userRepository, TenantRepository tenantRepository,
		GoogleIdTokenVerifier googleIdTokenVerifier) {
		this.userRepository = userRepository;
		this.tenantRepository = tenantRepository;
		this.googleIdTokenVerifier = googleIdTokenVerifier;
	}

	@PostMapping("/create-tenant")
	public ResponseEntity<?> createTenant(@RequestBody Map<String, String> body) {
		String name = body.get("name");
		String key = body.get("key");

		if (name == null || key == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "name and key are required"));
		}

		// Create a temporary user (since we don't have Google login)
		String tempEmail = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@local";
		UserEntity user = new UserEntity();
		user.setId(UUID.randomUUID().toString());
		user.setEmail(tempEmail);
		user.setFullName(name);
		user.setGoogleId("local-" + UUID.randomUUID().toString());
		user = userRepository.save(user);

		// Create tenant with API key
		TenantEntity tenant = new TenantEntity();
		tenant.setUserId(user.getId());
		tenant.setKey(key);
		tenant.setName(name);
		tenant.setApiKey(generateApiKey());
		tenant.setEnabled(true);
		tenant = tenantRepository.save(tenant);

		return ResponseEntity.ok(Map.of(
			"success", true,
			"apiKey", tenant.getApiKey(),
			"tenantId", tenant.getId(),
			"tenantKey", tenant.getKey(),
			"user", Map.of(
				"id", user.getId(),
				"email", user.getEmail(),
				"fullName", user.getFullName()
			)
		));
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

		// Find or create user
		UserEntity user = userRepository.findByGoogleId(resolvedGoogleId)
			.orElseGet(() -> {
				UserEntity newUser = new UserEntity();
				newUser.setGoogleId(resolvedGoogleId);
				newUser.setEmail(resolvedEmail);
				newUser.setFullName(resolvedFullName);
				newUser.setProfilePictureUrl(resolvedPictureUrl);
				return userRepository.save(newUser);
			});

		// Find or create tenant with API key
		TenantEntity tenant = tenantRepository.findByUserId(user.getId())
			.orElseGet(() -> {
				TenantEntity newTenant = new TenantEntity();
				newTenant.setUserId(user.getId());
				newTenant.setKey(resolvedEmail.split("@")[0].toLowerCase().replaceAll("[^a-z0-9]", "-"));
				newTenant.setName(resolvedFullName != null ? resolvedFullName : resolvedEmail.split("@")[0]);
				newTenant.setApiKey(generateApiKey());
				newTenant.setEnabled(true);
				return tenantRepository.save(newTenant);
			});

		Map<String, Object> userJson = new LinkedHashMap<>();
		userJson.put("id", user.getId());
		userJson.put("email", user.getEmail());
		userJson.put("fullName", user.getFullName());
		userJson.put("pictureUrl", user.getProfilePictureUrl());
		return ResponseEntity.ok(Map.of(
			"success", true,
			"apiKey", tenant.getApiKey(),
			"tenantId", tenant.getId(),
			"tenantKey", tenant.getKey(),
			"user", userJson
		));
	}

	@GetMapping("/me")
	public ResponseEntity<?> getCurrentUser() {
		// This endpoint is not protected - frontend should handle auth state
		return ResponseEntity.ok(Map.of("authenticated", false));
	}

	private String generateApiKey() {
		SecureRandom random = new SecureRandom();
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return "ak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
