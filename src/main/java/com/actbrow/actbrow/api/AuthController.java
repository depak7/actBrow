package com.actbrow.actbrow.api;

import java.security.SecureRandom;
import java.util.Base64;
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

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final UserRepository userRepository;
	private final TenantRepository tenantRepository;

	public AuthController(UserRepository userRepository, TenantRepository tenantRepository) {
		this.userRepository = userRepository;
		this.tenantRepository = tenantRepository;
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
		String googleId = body.get("googleId");
		String email = body.get("email");
		String fullName = body.get("fullName");
		String pictureUrl = body.get("pictureUrl");

		if (googleId == null || email == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "googleId and email are required"));
		}

		// Find or create user
		UserEntity user = userRepository.findByGoogleId(googleId)
			.orElseGet(() -> {
				UserEntity newUser = new UserEntity();
				newUser.setGoogleId(googleId);
				newUser.setEmail(email);
				newUser.setFullName(fullName);
				newUser.setProfilePictureUrl(pictureUrl);
				return userRepository.save(newUser);
			});

		// Find or create tenant with API key
		TenantEntity tenant = tenantRepository.findByUserId(user.getId())
			.orElseGet(() -> {
				TenantEntity newTenant = new TenantEntity();
				newTenant.setUserId(user.getId());
				newTenant.setKey(email.split("@")[0].toLowerCase().replaceAll("[^a-z0-9]", "-"));
				newTenant.setName(fullName != null ? fullName : email.split("@")[0]);
				newTenant.setApiKey(generateApiKey());
				newTenant.setEnabled(true);
				return tenantRepository.save(newTenant);
			});

		return ResponseEntity.ok(Map.of(
			"success", true,
			"apiKey", tenant.getApiKey(),
			"tenantId", tenant.getId(),
			"tenantKey", tenant.getKey(),
			"user", Map.of(
				"id", user.getId(),
				"email", user.getEmail(),
				"fullName", user.getFullName(),
				"pictureUrl", user.getProfilePictureUrl()
			)
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
