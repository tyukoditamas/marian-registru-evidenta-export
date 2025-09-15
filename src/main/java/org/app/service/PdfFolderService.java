// src/main/java/org/app/service/PdfFolderService.java
package org.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import org.app.helper.NativeExtractor;
import org.app.model.RegistruEvidentaDto;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class PdfFolderService {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Consumer<String> logger;

    public PdfFolderService(Consumer<String> logger) {
        this.logger = logger;
    }

    public List<RegistruEvidentaDto> processFolder(File folder) throws Exception {
        Path extractor = NativeExtractor.unpackExtractor();
        logger.accept("Extractor path: " + extractor.toAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(
                extractor.toAbsolutePath().toString(),
                folder.getAbsolutePath()
        );
        pb.redirectErrorStream(false);

        // Keep stdout/stderr in UTF-8
        Map<String,String> env = pb.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");

        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            String oldPath = env.getOrDefault("PATH", "");
            String brewPaths = "/usr/local/bin:/opt/homebrew/bin";
            if (!oldPath.contains("/usr/local/bin") || !oldPath.contains("/opt/homebrew/bin")) {
                env.put("PATH", brewPaths + (oldPath.isEmpty() ? "" : ":" + oldPath));
            }
        }

        Process p = pb.start();
        String stdout = readAll(p.getInputStream());
        try {
            java.nio.file.Path dbg = java.nio.file.Paths.get(System.getProperty("user.home"),
                    ".registru-evidenta", "last-extractor.json");
            java.nio.file.Files.createDirectories(dbg.getParent());
            java.nio.file.Files.writeString(dbg, stdout, java.nio.charset.StandardCharsets.UTF_8);
            logger.accept("Saved extractor JSON to: " + dbg);
        } catch (Exception ignore) {}
        String stderr = readAll(p.getErrorStream());
        int code = p.waitFor();

        if (code != 0) {
            throw new RuntimeException("Extractor exited with code " + code +
                    (stderr.isBlank() ? "" : (": " + firstLines(stderr, 12))));
        }
        if (!looksLikeJson(stdout)) {
            throw new RuntimeException("Extractor did not return JSON." +
                    (stderr.isBlank() ? (" Output was: " + firstLines(stdout, 12))
                            : (" Error: " + firstLines(stderr, 12))));
        }

        // Parse as tree so we can detect {"error": "..."} rows
        var mapper = new ObjectMapper().disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(stdout);
        if (!root.isArray()) throw new RuntimeException("Unexpected extractor output (not a JSON array).");

        List<RegistruEvidentaDto> ok = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode node : root) {
            String fileName = node.path("file").asText("<unknown>");
            if (node.hasNonNull("error")) {
                logger.accept("❌ " + fileName + " → " + node.get("error").asText());
                continue;
            }
            // basic sanity: ensure at least one expected field is present
            boolean hasData =
                    node.hasNonNull("dataDeclaratie") ||
                            node.hasNonNull("nrMrn") ||
                            node.hasNonNull("identificare") ||
                            node.hasNonNull("numeExportator") ||
                            node.hasNonNull("buc") ||
                            node.hasNonNull("greutate") ||
                            node.hasNonNull("descriereaMarfurilor");

            if (!hasData) {
                logger.accept("❌ Wrong structure: " + fileName);
                continue;
            }

            RegistruEvidentaDto dto = mapper.treeToValue(node, RegistruEvidentaDto.class);
            logger.accept("✅ Parsed successfully: " + fileName);
            ok.add(dto);
        }
        for (var dto : ok) {
            logger.accept("Parsed: MRN=" + dto.getNrMrn()
                    + " | Exportator=" + dto.getNumeExportator()
                    + " | Buc=" + dto.getBuc()
                    + " | Greutate=" + dto.getGreutate());
        }

        logger.accept("Total PDFs parsed: " + ok.size());
        return ok;
    }


    private static String readAll(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return !t.isEmpty() && (t.charAt(0) == '[' || t.charAt(0) == '{');
    }

    private static String firstLines(String s, int maxLines) {
        String[] lines = s.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            if (i > 0) sb.append(System.lineSeparator());
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
