package com.example.bootiful_javafx;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
class MainView {

    private final MessageClient messages;

    private final AsyncTaskExecutor executor;

    private Label greeting;

    private Button call;

    private TextArea output;

    MainView(MessageClient messages, AsyncTaskExecutor applicationTaskExecutor) {
        this.messages = messages;
        this.executor = applicationTaskExecutor;
    }

    @EventListener
    void on(StageReadyEvent event) {
        this.greeting = new Label("Nobody is signed in.");
        this.greeting.getStyleClass().add("greeting");

        this.call = new Button("Call the API");
        this.call.setDefaultButton(true);
        this.call.setOnAction(_ -> call());

        this.output = new TextArea();
        this.output.setEditable(false);

        var layout = new VBox(16, this.greeting, this.call, this.output);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(32));

        var scene = new Scene(layout, 560, 320);
        scene.getStylesheets().add("/styles.css");

        var stage = event.stage();
        stage.setTitle("Bootiful JavaFX");
        stage.setScene(scene);
        stage.setOnHidden(_ -> System.exit(0));
        stage.show();
    }

    private void call() {
        this.call.setDisable(true);
        this.output.setText("Calling http://localhost:8081/message ...");
        CompletableFuture.supplyAsync(this.messages::message, this.executor)
                .handleAsync(this::done, Platform::runLater);
    }

    private Void done(Message message, Throwable failure) {
        this.output.setText(
                failure == null ? message.message() : NestedExceptionUtils.getMostSpecificCause(failure).getMessage());
        this.call.setDisable(false);
        return null;
    }

    @EventListener
    void on(UserSignedInEvent event) {
        Platform.runLater(() -> this.greeting.setText("Hello, " + event.name() + "."));
    }

}
