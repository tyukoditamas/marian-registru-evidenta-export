package org.app.controller;

import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import org.app.model.RegistruEvidentaDto;
import org.app.service.PdfFolderService;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {

    // ---- UI (from the new FXML I sent) ----
    @FXML private TextField startIndexField;   // default "1" via TextFormatter in initialize()
    @FXML private TextField folderField;       // non-editable; filled by Browse…
    @FXML private Button    browseButton;
    @FXML private Button    generateButton;
    @FXML private TextArea  logArea;
    @FXML private ProgressIndicator spinner;
    @FXML private Label     statusLabel;

    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "excel-generator");
        t.setDaemon(true);
        return t;
    });

    @FXML
    public void initialize() {
        // ---- enforce positive integers in startIndexField without TextFormatter ----
        startIndexField.setText("1"); // default

        // allow only digits while typing (strip any non-digits)
        startIndexField.textProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) return;
            if (!newV.matches("\\d*")) {
                startIndexField.setText(newV.replaceAll("\\D", ""));
            }
        });

        // on focus lost, normalize empty/zero to "1"
        startIndexField.focusedProperty().addListener((obs, was, isNow) -> {
            if (!isNow) {
                String t = startIndexField.getText();
                if (t == null || t.isBlank() || t.equals("0")) {
                    startIndexField.setText("1");
                }
            }
        });

        startIndexField.setTooltip(new Tooltip("Enter the starting index (≥ 1) for the generated rows."));

        // ---- enable Generate only when both inputs present/valid ----
        BooleanBinding invalidStart = startIndexField.textProperty().isEmpty()
                .or(startIndexField.textProperty().isEqualTo("0"));
        BooleanBinding noFolder = folderField.textProperty().isEmpty();
        generateButton.disableProperty().bind(invalidStart.or(noFolder));

        spinner.setVisible(false);
        statusLabel.setText("Idle");
    }


    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select PDF Folder");
        File dir = chooser.showDialog(getWindow());
        if (dir != null) {
            folderField.setText(dir.getAbsolutePath());
            appendLog("Selected folder: " + dir.getAbsolutePath());
        }
    }

    @FXML
    private void onGenerate() {
        Integer start = parsePositiveIntOrNull(startIndexField.getText());
        if (start == null || start < 1) {
            alert("Please enter a valid start index (≥ 1).");
            return;
        }

        String folder = folderField.getText();
        if (folder == null || folder.isBlank()) {
            alert("Please choose a PDF folder.");
            return;
        }
        File dir = new File(folder);
        if (!dir.exists() || !dir.isDirectory()) {
            alert("The selected path is not a valid folder.");
            return;
        }

        setBusy(true, "Generating…");
        appendLog("Starting with Start Nr. Crt. = " + start);

        Task<List<RegistruEvidentaDto>> task = new Task<>() {
            @Override
            protected List<RegistruEvidentaDto> call() throws Exception {
                PdfFolderService svc = new PdfFolderService(line ->
                        Platform.runLater(() -> appendLog(line))
                );
                return svc.processFolder(dir);
            }

            @Override
            protected void succeeded() {
                List<RegistruEvidentaDto> rows = getValue();
                File xlsx = new File(dir, "registru_evidenta_marfuri_generat.xlsx");
                try {
                    int appended = org.app.controller.ExcelWriter.appendOrCreate(xlsx, rows, start);
                    appendLog("✅ Wrote " + appended + " row(s) to: " + xlsx.getName());
                    statusLabel.setText("Completed");
                } catch (Exception ex) {
                    appendLog("❌ Excel write failed: " + ex.getMessage());
                    statusLabel.setText("Failed");
                } finally {
                    setBusy(false, statusLabel.getText());
                }
            }

            @Override
            protected void failed() {
                appendLog("❌ Fatal: " + getException().getMessage());
                setBusy(false, "Failed");
            }
        };

        // ---- rebind UI every run (unbind first to avoid exceptions) ----
        spinner.visibleProperty().unbind();
        spinner.visibleProperty().bind(task.runningProperty());

        browseButton.disableProperty().unbind();
        browseButton.disableProperty().bind(task.runningProperty());

        generateButton.disableProperty().unbind();
        BooleanBinding invalidStart = startIndexField.textProperty().isEmpty()
                .or(startIndexField.textProperty().isEqualTo("0"));
        BooleanBinding noFolder = folderField.textProperty().isEmpty();
        generateButton.disableProperty().bind(
                invalidStart.or(noFolder).or(task.runningProperty())
        );

        startIndexField.disableProperty().unbind();
        startIndexField.disableProperty().bind(task.runningProperty());

        ioPool.submit(task);
    }


    @FXML
    private void onClearLog() {
        logArea.clear();
        statusLabel.setText("Idle");
    }

    // ---- Helpers ----
    private void setBusy(boolean busy, String status) {
        statusLabel.setText(status);
    }

    private void appendLog(String s) {
        if (s == null) return;
        if (Platform.isFxApplicationThread()) {
            logArea.appendText(s + System.lineSeparator());
        } else {
            Platform.runLater(() -> logArea.appendText(s + System.lineSeparator()));
        }
    }

    private void alert(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private Window getWindow() {
        return (startIndexField != null && startIndexField.getScene() != null)
                ? startIndexField.getScene().getWindow()
                : (browseButton != null ? browseButton.getScene().getWindow() : null);
    }

    static Integer parsePositiveIntOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            int v = Integer.parseInt(text.trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
