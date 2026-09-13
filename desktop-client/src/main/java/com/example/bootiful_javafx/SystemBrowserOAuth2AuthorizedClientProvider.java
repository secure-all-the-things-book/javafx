package com.example.bootiful_javafx;

import org.springframework.security.oauth2.client.OAuth2AuthorizationContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Component
class SystemBrowserOAuth2AuthorizedClientProvider implements OAuth2AuthorizedClientProvider {

	private static final Duration TIMEOUT = Duration.ofMinutes(2);

	private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	private final SystemBrowserOAuth2Login browser;

	SystemBrowserOAuth2AuthorizedClientProvider(SystemBrowserOAuth2Login browser) {
		this.browser = browser;
	}

	private static boolean expired(OAuth2AccessToken token) {
		var expiresAt = token.getExpiresAt();
		return expiresAt != null && Instant.now().isAfter(expiresAt.minus(CLOCK_SKEW));
	}

	@Override
	public OAuth2AuthorizedClient authorize(OAuth2AuthorizationContext context) {
		var current = context.getAuthorizedClient();
		if (current != null && !expired(current.getAccessToken())) {
			return null; // <.>
		}
		try {
			// <.>
			return this.browser.start(context.getClientRegistration().getRegistrationId())
				.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
				.join();
		}
		catch (CompletionException ex) {
			throw new OAuth2AuthorizationException(new OAuth2Error("browser_login_failed"), ex.getCause());
		}
	}

}
