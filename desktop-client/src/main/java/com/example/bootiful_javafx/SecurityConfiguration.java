package com.example.bootiful_javafx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.support.OAuth2RestClientHttpServiceGroupConfigurer;
import org.springframework.web.client.RestClient;

/**
 * Everything Spring Security needs to know about a single-tenant, browser-driven,
 * public-client desktop application.
 */
@Configuration
class SecurityConfiguration {

	/**
	 * The manager Spring Security asks for a token. There is no
	 * {@code HttpServletRequest} to hang an authorized client off of here, so this is the
	 * *service*-backed manager, and the providers are: refresh the token if we can,
	 * otherwise send the user to the system browser.
	 */
	@Bean
	OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService authorizedClients, SystemBrowserOAuth2AuthorizedClientProvider browser) {
		var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
		manager.setAuthorizedClientProvider(
				OAuth2AuthorizedClientProviderBuilder.builder().refreshToken().provider(browser).build());
		return manager;
	}

	/**
	 * For hand-rolled calls: an interceptor that attaches the {@code Authorization}
	 * header to any request carrying a client-registration-id attribute.
	 */
	@Bean
	RestClient restClient(RestClient.Builder builder, OAuth2AuthorizedClientManager authorizedClientManager) {
		return builder.requestInterceptor(new OAuth2ClientHttpRequestInterceptor(authorizedClientManager)).build();
	}

	/**
	 * For the declarative {@code @GetExchange} clients: this is what makes
	 * {@code @ClientRegistrationId} mean anything. Define it once per JVM - no more, no
	 * less.
	 */
	@Bean
	OAuth2RestClientHttpServiceGroupConfigurer oauth2RestClientConfigurer(
			OAuth2AuthorizedClientManager authorizedClientManager) {
		return OAuth2RestClientHttpServiceGroupConfigurer.from(authorizedClientManager);
	}

}
