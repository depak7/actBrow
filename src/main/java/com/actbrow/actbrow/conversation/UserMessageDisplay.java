package com.actbrow.actbrow.conversation;

/**
 * User-visible vs stored user message: {@link com.actbrow.actbrow.service.RunService} appends a PAGE_CONTEXT
 * block for the model; API clients should not show that appendix.
 */
public final class UserMessageDisplay {

	/** Start of the appendix appended to stored USER messages when page context is included. */
	public static final String PAGE_CONTEXT_APPENDIX_START = "\n\n--- PAGE_CONTEXT (browser snapshot when the user sent this message. ";

	private UserMessageDisplay() {
	}

	public static String stripStoredAppendix(String storedUserContent) {
		if (storedUserContent == null || storedUserContent.isEmpty()) {
			return storedUserContent;
		}
		int idx = storedUserContent.indexOf(PAGE_CONTEXT_APPENDIX_START);
		if (idx < 0) {
			return storedUserContent;
		}
		return storedUserContent.substring(0, idx).stripTrailing();
	}
}
