package com.example.bootiful_javafx;

import org.springframework.security.oauth2.client.annotation.ClientRegistrationId;
import org.springframework.web.service.annotation.GetExchange;

import static com.example.bootiful_javafx.StageInitializer.CLIENT_REGISTRATION_ID;

/**
 * An HTTP interface client for the resource server. There is no token in sight:
 * {@code @ClientRegistrationId} tells Spring Security which registration to use, and it
 * sources - and refreshes - the token on every call.
 */
@ClientRegistrationId(CLIENT_REGISTRATION_ID)
interface MessageClient {

	@GetExchange("${bootiful.service-uri}")
	Message message();

}
