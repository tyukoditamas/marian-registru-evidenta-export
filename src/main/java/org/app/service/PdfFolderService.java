package org.app.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.app.helper.NativeBinaries;
import org.app.helper.NativeExtractor;
import org.app.model.RegistruEvidentaDto;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public class PdfFolderService {
    private static final Log log = LogFactory.getLog(PdfFolderService.class);
    private final ObjectMapper mapper;
    private final Consumer<String> logger;

    public PdfFolderService(Consumer<String> logger) {
        this.logger = logger;
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public List<RegistruEvidentaDto> processFolder(File folder) throws Exception {
        Path pdftotext = NativeBinaries.unpackPdftotext();


        Path extractor = NativeExtractor.unpackExtractor();
        ProcessBuilder pb = new ProcessBuilder(
                extractor.toAbsolutePath().toString(),
                folder.getAbsolutePath()
        );

        Map<String, String> env = pb.environment();
        env.put("PDFTOTEXT_BIN", pdftotext.toAbsolutePath().toString());

        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            String dir = pdftotext.getParent().toString();
            String oldPath = env.getOrDefault("PATH", "");
            env.put("PATH", dir + java.io.File.pathSeparator + oldPath);
        }

        pb.redirectErrorStream(false); // ← IMPORTANT: don't mix stderr into stdout
        Process proc = pb.start();

        // 2) collect stdout and stderr
        String stdout = readAll(proc.getInputStream());
        String stderr = readAll(proc.getErrorStream());
        int exit = proc.waitFor();

        if (exit != 0) {
            String msg = "Extractor exited with code " + exit;
            if (!stderr.isBlank()) msg += ": " + firstLines(stderr, 12);
            throw new RuntimeException(msg);
        }
        if (!looksLikeJson(stdout)) {
            String msg = "Extractor did not return JSON.";
            if (!stderr.isBlank()) msg += " Error: " + firstLines(stderr, 12);
            else msg += " Output was: " + firstLines(stdout, 12);
            throw new RuntimeException(msg);
        }

        // 3) parse JSON array
        JsonNode root = mapper.readTree(stdout);
        if (!(root instanceof ArrayNode)) {
            throw new RuntimeException("Unexpected extractor output (not a JSON array).");
        }

        // fields we expect to see (any of these is OK)
        List<String> expected = List.of(
                "dataDeclaratie",
                "nrMrn",
                "identificare",
                "numeExportator",
                "buc",
                "greutate",
                "descriereaMarfurilor"
        );

        List<Map.Entry<String, RegistruEvidentaDto>> rows = new ArrayList<>();

        ArrayNode arr = (ArrayNode) root;
        for (JsonNode n : arr) {
            String fileName = n.path("file").asText("<unknown>");

            if (n.has("error")) {
                logger.accept("❌ Failed to parse: " + fileName + " → " + n.get("error").asText());
                continue;
            }

            boolean hasData = expected.stream().anyMatch(f ->
                    n.hasNonNull(f) && !n.get(f).asText().isBlank()
            );
            if (!hasData) {
                logger.accept("❌ Wrong structure: " + fileName);
                continue;
            }

            RegistruEvidentaDto dto = mapper.treeToValue(n, RegistruEvidentaDto.class);
            rows.add(new AbstractMap.SimpleEntry<>(fileName, dto));
        }

        // sort by filename naturally
        rows.sort((a, b) -> naturalCompareIgnoreCase(a.getKey(), b.getKey()));

        List<RegistruEvidentaDto> good = new ArrayList<>(rows.size());
        for (var e : rows) {
            logger.accept("✅ Parsed successfully: " + e.getKey());
            good.add(e.getValue());
        }
        logger.accept("Total PDFs parsed: " + good.size());
        good.forEach(entry -> logger.accept("Found: " + entry));
        return good;
    }

    // ----- helpers -----

    private static String readAll(InputStream is) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
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

    private static int naturalCompareIgnoreCase(String a, String b) {
        int ia = 0, ib = 0, na = a.length(), nb = b.length();
        while (ia < na && ib < nb) {
            char ca = Character.toLowerCase(a.charAt(ia));
            char cb = Character.toLowerCase(b.charAt(ib));
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia; while (ja < na && Character.isDigit(a.charAt(ja))) ja++;
                int jb = ib; while (jb < nb && Character.isDigit(b.charAt(jb))) jb++;
                String da = a.substring(ia, ja).replaceFirst("^0+(?!$)", "");
                String db = b.substring(ib, jb).replaceFirst("^0+(?!$)", "");
                int cmp = Integer.compare(da.length(), db.length());
                if (cmp == 0) cmp = da.compareTo(db);
                if (cmp != 0) return cmp;
                ia = ja; ib = jb;
                continue;
            }
            if (ca != cb) return ca - cb;
            ia++; ib++;
        }
        return (na - ia) - (nb - ib);
    }
}
