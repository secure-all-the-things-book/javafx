package com.example.bootiful_javafx;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.springframework.core.env.Environment;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class StageInitializer {

	static final String CLIENT_REGISTRATION_ID = "javafx";

	private final Resource fxml = new ClassPathResource("/fxml/ui.fxml");

	private final SystemBrowserOAuth2Login login;

	private final MessageClient messageClient;

	private final Environment environment;

	private Label greeting;

	private TextArea output;

	private Button signIn;

	private Button call;

	StageInitializer(SystemBrowserOAuth2Login login, MessageClient messageClient, Environment environment) {
		this.login = login;
		this.messageClient = messageClient;
		this.environment = environment;
	}

	@EventListener
	void on(StageReadyEvent event) throws Exception {
		var loader = new FXMLLoader();
		var root = (Parent) null;
		try (var fxmlInputStream = this.fxml.getInputStream()) {
			root = loader.load(fxmlInputStream);
		}
		var scene = new Scene(root);

		// like document.getElementById, but for the scene graph
		this.greeting = (Label) scene.lookup("#greeting");
		this.output = (TextArea) scene.lookup("#output");
		this.signIn = (Button) scene.lookup("#signIn");
		this.call = (Button) scene.lookup("#call");

		// tag::wiring[]
		this.signIn
			.setOnAction(_ -> Threads.offTheFxThread(() -> this.login.start(CLIENT_REGISTRATION_ID), this::fail));

		this.call.setOnAction(_ -> Threads.offTheFxThread(() -> {
			var message = this.messageClient.message();
			Threads.onTheFxThread(() -> this.output.setText(message.message()));
		}, this::fail));
		// end::wiring[]

		var stage = event.stage();
		stage.setTitle(this.environment.getProperty("app.title"));
		stage.setScene(scene);
		stage.setOnHidden(_ -> System.exit(0));
		stage.show();
	}

	// tag::signedin[]
	@EventListener
	void on(UserSignedInEvent event) {
		Threads.onTheFxThread(() -> {
			this.greeting.setText("Hello, " + event.name() + ".");
			this.output.setText(claims(event.user().getClaims()));
			this.call.setDisable(false);
		});
	}
	// end::signedin[]

	private void fail(Throwable throwable) {
		this.output.setText(throwable.getMessage());
	}

	private static String claims(Map<String, Object> claims) {
		var claimsString = new StringBuilder();
		var template = "%s: %s" + System.lineSeparator();
		for (var entry : claims.entrySet())
			claimsString.append(template.formatted(entry.getKey(), entry.getValue()));
		return claimsString.toString();
	}

}
