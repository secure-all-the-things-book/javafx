package com.example.bootiful_javafx;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.service.registry.ImportHttpServices;

@ImportHttpServices({ UserinfoClient.class, MessageClient.class })
@SpringBootApplication(exclude = { ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
		UserDetailsServiceAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class })
public class DesktopApplication {

	public static void main(String[] args) {
		SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_GLOBAL);
		var applicationContext = new SpringApplicationBuilder()//
			.sources(DesktopApplication.class)//
			.headless(false)//
			.run(args);
		Platform.startup(() -> applicationContext.publishEvent(new StageReadyEvent(new Stage())));
	}

}
