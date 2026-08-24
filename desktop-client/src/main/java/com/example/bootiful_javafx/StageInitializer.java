package com.example.bootiful_javafx;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class StageInitializer {

	private final SystemBrowserOAuth2Login login;

	private Label greeting;

	private TextArea output;

	private Button signIn, call;

	private final UserinfoClient userinfoClient;

	static final Resource FXML = new ClassPathResource("/fxml/ui.fxml");

	static final String CLIENT_REGISTRATION_ID = "javafx";

	StageInitializer(SystemBrowserOAuth2Login login, //
			UserinfoClient userinfoClient //
	) {
		this.login = login;
		this.userinfoClient = userinfoClient;
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
		this.output = (TextArea) scene.lookup("#output");
		this.signIn = (Button) scene.lookup("#signIn");
		this.call = (Button) scene.lookup("#call");
		this.signIn.setOnAction(e -> {
			Threads.offTheFxThread(() -> this.login.start(CLIENT_REGISTRATION_ID));
		});
		this.call.setOnAction(e -> {
			this.call.setDisable(true);
			Threads.offTheFxThread(() -> {
				var body = this.userinfoClient.get();
				Threads.onTheFxThread(() -> {
					this.output.setText(body);
					this.call.setDisable(false);
				});
			});
		});

		var stage = event.stage();
		stage.setTitle("JavaFX + Spring Boot + GraalVM");
		stage.setScene(scene);
		stage.setOnHidden(_ -> System.exit(0));
		stage.show();
	}

	@EventListener
	void on(UserSignedInEvent event) {
		Threads.onTheFxThread(() -> {
			this.greeting.setText("Hello, " + event.name() + ".");
			this.output.setText(claims(event.user().getClaims()));
			this.call.setDisable(false);
		});
	}

	private String claims(Map<String, Object> claims) {
		var claimsString = new StringBuilder();
		var template = "%s: %s" + System.lineSeparator();
		for (var entry : claims.entrySet())
			claimsString.append(template.formatted(entry.getKey(), entry.getValue()));
		return claimsString.toString();
	}

}