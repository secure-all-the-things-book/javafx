package com.example.bootiful_javafx;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.support.OAuth2RestClientHttpServiceGroupConfigurer;
import org.springframework.web.service.registry.ImportHttpServices;

@ImportHttpServices(MessageClient.class)
@Configuration
class MessageClientConfiguration {

	// <.>
	@Bean
	OAuth2RestClientHttpServiceGroupConfigurer oauth2RestClientConfigurer(OAuth2AuthorizedClientManager manager) {
		return OAuth2RestClientHttpServiceGroupConfigurer.from(manager);
	}

}
