package com.actbrow.actbrow.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.actbrow.actbrow.api.dto.KnowledgeDocumentRequest;
import com.actbrow.actbrow.api.dto.KnowledgeDocumentResponse;
import com.actbrow.actbrow.model.KnowledgeDocumentEntity;
import com.actbrow.actbrow.repository.KnowledgeDocumentRepository;

@Service
public class KnowledgeService {

	private static final Set<String> STOP_WORDS = Set.of(
		"a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "i", "if", "in", "is",
		"it", "me", "my", "of", "on", "or", "please", "the", "to", "we", "what", "when", "where", "with",
		"you", "your");

	private final KnowledgeDocumentRepository knowledgeDocumentRepository;

	public KnowledgeService(KnowledgeDocumentRepository knowledgeDocumentRepository) {
		this.knowledgeDocumentRepository = knowledgeDocumentRepository;
	}

	public List<KnowledgeDocumentResponse> listByAssistant(String assistantId) {
		return knowledgeDocumentRepository.findAllByAssistantIdOrderByUpdatedAtDesc(assistantId).stream()
			.map(this::toResponse)
			.toList();
	}

	public KnowledgeDocumentResponse create(String assistantId, KnowledgeDocumentRequest request) {
		KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
		entity.setAssistantId(assistantId);
		entity.setTitle(request.title().trim());
		entity.setContent(request.content().trim());
		entity.setSource(trimToNull(request.source()));
		entity.setEnabled(request.enabled());
		return toResponse(knowledgeDocumentRepository.save(entity));
	}

	public void delete(String assistantId, String knowledgeId) {
		KnowledgeDocumentEntity entity = knowledgeDocumentRepository.findByAssistantIdAndId(assistantId, knowledgeId)
			.orElseThrow(() -> new IllegalArgumentException("Knowledge document not found"));
		knowledgeDocumentRepository.delete(entity);
	}

	public List<KnowledgeDocumentResponse> findRelevant(String assistantId, String query, int limit) {
		List<KnowledgeDocumentEntity> docs = knowledgeDocumentRepository
			.findAllByAssistantIdAndEnabledTrueOrderByUpdatedAtDesc(assistantId);
		if (docs.isEmpty()) {
			return List.of();
		}
		Set<String> queryTerms = extractTerms(query);
		return docs.stream()
			.map(doc -> new ScoredKnowledgeDocument(doc, score(doc, query, queryTerms)))
			.filter(scored -> !queryTerms.isEmpty() ? scored.score() > 0 : true)
			.sorted(Comparator
				.comparingInt(ScoredKnowledgeDocument::score).reversed()
				.thenComparing(scored -> scored.document().getUpdatedAt(), Comparator.reverseOrder()))
			.limit(limit)
			.map(scored -> toResponse(scored.document()))
			.toList();
	}

	private int score(KnowledgeDocumentEntity doc, String query, Set<String> queryTerms) {
		String haystack = searchableText(doc);
		if (query == null || query.isBlank()) {
			return 1;
		}
		int score = 0;
		String normalizedQuery = normalize(query);
		if (!normalizedQuery.isBlank() && haystack.contains(normalizedQuery)) {
			score += 8;
		}
		Set<String> docTerms = extractTerms(haystack);
		for (String term : queryTerms) {
			if (docTerms.contains(term)) {
				score += term.length() > 5 ? 3 : 2;
			}
		}
		return score;
	}

	private String searchableText(KnowledgeDocumentEntity doc) {
		return normalize(StreamText.join(doc.getTitle(), doc.getSource(), doc.getContent()));
	}

	private Set<String> extractTerms(String text) {
		if (text == null || text.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(normalize(text).split("\\s+"))
			.filter(token -> token.length() >= 3)
			.filter(token -> !STOP_WORDS.contains(token))
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private String normalize(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private KnowledgeDocumentResponse toResponse(KnowledgeDocumentEntity entity) {
		return new KnowledgeDocumentResponse(
			entity.getId(),
			entity.getAssistantId(),
			entity.getTitle(),
			entity.getContent(),
			entity.getSource(),
			entity.isEnabled(),
			entity.getCreatedAt(),
			entity.getUpdatedAt());
	}

	private record ScoredKnowledgeDocument(KnowledgeDocumentEntity document, int score) {
	}

	private static final class StreamText {

		private StreamText() {
		}

		private static String join(String... parts) {
			return Arrays.stream(parts)
				.filter(part -> part != null && !part.isBlank())
				.collect(Collectors.joining(" "));
		}
	}
}
