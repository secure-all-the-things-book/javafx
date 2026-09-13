package com.example.bootiful_javafx;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.StringUtils;

record UserSignedInEvent(OAuth2AuthenticationToken authentication) {

	String name() {
		var user = (OidcUser) this.authentication.getPrincipal();
		return StringUtils.hasText(user.getPreferredUsername()) ? user.getPreferredUsername() : user.getName();
	}

}
