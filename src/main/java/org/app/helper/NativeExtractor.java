// src/main/java/org/app/helper/NativeExtractor.java
package org.app.helper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class NativeExtractor {
    /**
     * Unpacks the correct native extractor for this OS to a temp file,
     * makes it executable, and returns its Path.
     */
    public static Path unpackExtractor(java.util.function.Consumer<String> log) throws IOException {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Path dir = java.nio.file.Files.createTempDirectory("pdf-extractor-");

        String extractorRes = win ? "/native/windows/extract.exe" : "/native/macos/extract";
        Path extractor = dir.resolve(win ? "extract.exe" : "extract");
        copyRes(extractorRes, extractor);

        // put pdftotext right next to extractor
        Path pdftotext = dir.resolve(win ? "pdftotext.exe" : "pdftotext");
        copyRes(win ? "/native/windows/pdftotext.exe" : "/native/macos/pdftotext", pdftotext);
        try { pdftotext.toFile().setExecutable(true); } catch (Exception ignore) {}
        try { extractor.toFile().setExecutable(true); } catch (Exception ignore) {}

        if (log != null) log.accept("Extractor path: " + extractor);
        return extractor;
    }

    private static void copyRes(String res, Path dest) throws IOException {
        try (InputStream in = NativeExtractor.class.getResourceAsStream(res)) {
            if (in == null) throw new FileNotFoundException("Missing resource: " + res);
            java.nio.file.Files.createDirectories(dest.getParent());
            java.nio.file.Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
