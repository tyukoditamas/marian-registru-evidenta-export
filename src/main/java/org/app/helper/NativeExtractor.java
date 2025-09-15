// src/main/java/org/app/helper/NativeExtractor.java
package org.app.helper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NativeExtractor {
    /**
     * Unpacks the correct native extractor for this OS to a temp file,
     * makes it executable, and returns its Path.
     */
    public static Path unpackExtractor() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String resourcePath;
        String suffix;

        if (os.contains("win")) {
            resourcePath = "/native/windows/extract.exe";
            suffix       = ".exe";
        } else if (os.contains("mac")) {
            resourcePath = "/native/macos/extract";
            suffix       = "";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }

        try (InputStream in = NativeExtractor.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new FileNotFoundException("Resource not found on classpath: " + resourcePath);

            Path tmp = Files.createTempFile("pdf-extractor-", suffix);
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);

            tmp.toFile().setExecutable(true, true);
            tmp.toFile().deleteOnExit();
            return tmp;
        }
    }
}
