package com.actbrow.actbrow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.actbrow.actbrow.config.GoogleOAuthProperties;

class GoogleIdTokenVerifierTests {

	@Test
	void refusesSignInWhenNoClientIdIsConfigured() {
		// With no client id there is nothing to check the audience against; accepting would let a
		// token minted for any other app log in here.
		for (String blank : new String[] {null, "", "  "}) {
			GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier(new GoogleOAuthProperties(blank));
			assertThatThrownBy(() -> verifier.verifyAndDecode("any-token"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GOOGLE_OAUTH_CLIENT_ID");
		}
	}

	@Test
	void audienceMustMatchTheConfiguredClient() {
		assertThat(GoogleIdTokenVerifier.audienceMatches("ours", "ours")).isTrue();
		assertThat(GoogleIdTokenVerifier.audienceMatches(List.of("other", "ours"), "ours")).isTrue();
		assertThat(GoogleIdTokenVerifier.audienceMatches("theirs", "ours")).isFalse();
		assertThat(GoogleIdTokenVerifier.audienceMatches(List.of("theirs"), "ours")).isFalse();
	}

	@Test
	void aMissingOrMalformedAudienceIsAMismatch() {
		assertThat(GoogleIdTokenVerifier.audienceMatches(null, "ours")).isFalse();
		assertThat(GoogleIdTokenVerifier.audienceMatches(42, "ours")).isFalse();
	}
}
