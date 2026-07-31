package com.actbrow.actbrow.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts integration secrets at rest. When no key is configured, values are stored as plaintext
 * with a {@code plain:} prefix so deployments can migrate later.
 */
@Component
public class SecretCryptoService {

	private static final String PREFIX_ENC = "enc:v1:";
	private static final String PREFIX_PLAIN = "plain:";
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_BITS = 128;

	private final SecretKey key;
	private final SecureRandom secureRandom = new SecureRandom();

	public SecretCryptoService(@Value("${actbrow.secrets.encryption-key:}") String encryptionKey) {
		if (encryptionKey == null || encryptionKey.isBlank()) {
			this.key = null;
		}
		else {
			byte[] raw = encryptionKey.getBytes(StandardCharsets.UTF_8);
			byte[] normalized = new byte[32];
			System.arraycopy(raw, 0, normalized, 0, Math.min(raw.length, 32));
			this.key = new SecretKeySpec(normalized, "AES");
		}
	}

	public String seal(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		if (key == null) {
			return PREFIX_PLAIN + plaintext;
		}
		try {
			byte[] iv = new byte[GCM_IV_LENGTH];
			secureRandom.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
			buffer.put(iv);
			buffer.put(ciphertext);
			return PREFIX_ENC + Base64.getEncoder().encodeToString(buffer.array());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to encrypt secret", ex);
		}
	}

	public String open(String sealed) {
		if (sealed == null) {
			return null;
		}
		if (sealed.startsWith(PREFIX_PLAIN)) {
			return sealed.substring(PREFIX_PLAIN.length());
		}
		if (!sealed.startsWith(PREFIX_ENC)) {
			// Legacy plaintext rows from before encryption.
			return sealed;
		}
		if (key == null) {
			throw new IllegalStateException("Encrypted secret present but actbrow.secrets.encryption-key is not set");
		}
		try {
			byte[] decoded = Base64.getDecoder().decode(sealed.substring(PREFIX_ENC.length()));
			ByteBuffer buffer = ByteBuffer.wrap(decoded);
			byte[] iv = new byte[GCM_IV_LENGTH];
			buffer.get(iv);
			byte[] ciphertext = new byte[buffer.remaining()];
			buffer.get(ciphertext);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to decrypt secret", ex);
		}
	}
}
