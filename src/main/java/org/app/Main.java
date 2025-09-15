package org.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

public class Main extends Application {
    @Override
    public void start(Stage primaryStage) throws Exception {
        Parent root = FXMLLoader.load(
                Objects.requireNonNull(getClass().getResource("/org/app/view/MainView.fxml"))
        );
        primaryStage.setTitle("Generare Registru Evidenta Export");
        Scene scene = new Scene(root, 700, 500);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/org/app/style.css")).toExternalForm()
        );
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
