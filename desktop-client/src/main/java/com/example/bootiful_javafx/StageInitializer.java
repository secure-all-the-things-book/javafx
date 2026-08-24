package com.example.bootiful_javafx;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;

@Component
class StageInitializer {

	private final SystemBrowserOAuth2Login login;

	private final RestClient http;

	private final String registrationId;

	private final String api;

	private final CountDownLatch signedIn = new CountDownLatch(1);

	private Label greeting, status;

	private TextArea output;

	private Button signIn, call;

	static final Resource FXML = new ClassPathResource("/fxml/ui.fxml");

	StageInitializer(SystemBrowserOAuth2Login login, //
			RestClient http, //
			@Value("${bootiful.oauth2.registration-id}") String registrationId, //
			@Value("${bootiful.api-uri}") String api) {
		this.login = login;
		this.http = http;
		this.registrationId = registrationId;
		this.api = api;
	}

	@EventListener
	void on(StageReadyEvent event) throws Exception {
		var loader = new FXMLLoader();
		var root = (Parent) null;
		try (var fxmlInputStream = FXML.getInputStream()) {
			root = loader.load(fxmlInputStream);
		}
		var scene = new Scene(root);
		this.greeting = (Label) scene.lookup("#greeting");
		this.status = (Label) scene.lookup("#status");
		this.output = (TextArea) scene.lookup("#output");
		this.signIn = (Button) scene.lookup("#signIn");
		this.call = (Button) scene.lookup("#call");

		this.signIn.setOnAction(e -> {
			this.status.setText("finish signing in over in your browser...");
			Threads.offTheFxThread(() -> {
				this.login.start(this.registrationId);
			});
		});

		this.call.setOnAction(e -> {
			this.call.setDisable(true);
			this.status.setText("calling " + this.api + "...");
			Threads.offTheFxThread(() -> {
				var body = this.http //
					.get() //
					.uri(this.api) //
					.attributes(clientRegistrationId(this.registrationId))//
					.retrieve()//
					.body(String.class);
				Threads.onTheFxThread(() -> {
					this.status.setText("200 from " + this.api);
					this.output.setText(body);
					this.call.setDisable(false);
				});
			});
		});

		var stage = event.stage();
		stage.setTitle("JavaFX + Spring Boot + GraalVM");
		stage.setScene(scene);
		stage.setOnHidden(_ -> System.exit(0));
		stage.setOnShown(_ -> IO.println("stage shown"));
		stage.show();
	}

	@EventListener
	void on(UserSignedInEvent event) {
		Threads.onTheFxThread(() -> {
			this.greeting.setText("Hello, " + event.name() + ".");
			this.status
				.setText("signed in via '%s'".formatted(event.authentication().getAuthorizedClientRegistrationId()));
			this.output.setText(claims(event.user().getClaims()));
			this.call.setDisable(false);
			this.signedIn.countDown();
		});
	}

	private static String claims(Map<String, Object> claims) {
		return claims.entrySet()
			.stream()
			.map(claim -> "%s: %s".formatted(claim.getKey(), claim.getValue()))
			.sorted()
			.reduce((a, b) -> a + System.lineSeparator() + b)
			.orElse("");
	}

}
