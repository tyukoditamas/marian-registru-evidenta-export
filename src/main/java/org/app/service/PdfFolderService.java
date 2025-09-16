// src/main/java/org/app/service/PdfFolderService.java
package org.app.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.app.helper.NativeExtractor;
import org.app.model.RegistruEvidentaDto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class PdfFolderService {

    private static final Log log = LogFactory.getLog(PdfFolderService.class);

    private final ObjectMapper mapper;
    private final Consumer<String> logger;

    public PdfFolderService(Consumer<String> logger) {
        this.logger = logger;
        this.mapper = new ObjectMapper()
                // tolerate extra fields like "file" or "error" from the extractor
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Runs the extractor on a folder and returns parsed rows. */
    public List<RegistruEvidentaDto> processFolder(File folder) throws Exception {
        if (folder == null || !folder.isDirectory()) {
            throw new IllegalArgumentException("Not a folder: " + (folder == null ? "null" : folder));
        }

        // 1) Run the extractor (stderr kept separate!)
        ExecResult r = runExtractor(folder);

        // 2) Log any warnings/errors from stderr (don’t break JSON parsing)
        if (!r.stderr.isBlank()) {
            for (String line : r.stderr.split("\\R")) {
                if (!line.isBlank()) logger.accept(line);
            }
        }

        if (r.exitCode != 0) {
            // If Python returned a non-zero exit, prefer showing stderr; fallback to stdout
            throw new RuntimeException("Extractor failed:\n" +
                    (r.stderr.isBlank() ? r.stdout : r.stderr));
        }

        // 3) Keep only the JSON payload (trim anything before '[' and after ']')
        String json = r.stdout;
        int s = json.indexOf('[');
        int e = json.lastIndexOf(']');
        if (s < 0 || e < 0 || e < s) {
            throw new RuntimeException("Extractor output is not a JSON array:\n" + json);
        }
        json = json.substring(s, e + 1);

        // 4) Parse JSON
        JsonNode root = mapper.readTree(json);
        if (!(root instanceof ArrayNode)) {
            throw new RuntimeException("Unexpected extractor output (not a JSON array).");
        }
        ArrayNode arr = (ArrayNode) root;

        // 5) Map to DTOs
        List<RegistruEvidentaDto> rows = new ArrayList<>(arr.size());
        for (JsonNode node : arr) {
            RegistruEvidentaDto dto = mapper.treeToValue(node, RegistruEvidentaDto.class);
            rows.add(dto);
        }

        // 6) Optional: sanity check keys exist (matches your extractor’s output)
        validateStructure(arr);

        return rows;
    }

    // ------------------------------- helpers -------------------------------

    private ExecResult runExtractor(File folder) throws Exception {
        Path extractor = NativeExtractor.unpackExtractor();

        ProcessBuilder pb = new ProcessBuilder(
                extractor.toAbsolutePath().toString(),
                folder.getAbsolutePath()
        );

        // Keep stderr separate so warnings don't corrupt JSON
        pb.redirectErrorStream(false);

        // Make Python output UTF-8 and stay quiet
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONWARNINGS", "ignore");

        Process proc = pb.start();

        // Collect stdout (JSON) and stderr (warnings) concurrently
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        Thread tOut = new Thread(() -> pipe(proc.getInputStream(), outBuf), "extractor-stdout");
        Thread tErr = new Thread(() -> pipe(proc.getErrorStream(), errBuf), "extractor-stderr");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        int exit = proc.waitFor();
        tOut.join();
        tErr.join();

        String stdout = outBuf.toString(StandardCharsets.UTF_8);
        String stderr = errBuf.toString(StandardCharsets.UTF_8);

        return new ExecResult(stdout, stderr, exit);
    }

    private static void pipe(InputStream in, ByteArrayOutputStream out) {
        try (InputStream is = in) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) out.write(buf, 0, r);
        } catch (Exception e) {
            // swallow; we’ll surface problems via exit code / content later
        }
    }

    private void validateStructure(ArrayNode arr) {
        // Keys expected from the Python extractor (adjust only if you change the extractor)
        List<String> expected = List.of(
                "dataDeclaratie",
                "nrMrn",
                "identificare",
                "numeExportator",
                "buc",
                "greutate",
                "descriereaMarfurilor",
                "file"
        );

        if (arr.isEmpty()) return;

        JsonNode first = arr.get(0);
        for (String key : expected) {
            if (!first.has(key)) {
                logger.accept("⚠️ Extractor JSON missing key '" + key + "' in first element.");
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // Simple natural comparator for strings with numbers (e.g., file names)
    private static int naturalCompare(String a, String b) {
        if (Objects.equals(a, b)) return 0;
        if (a == null) return 1;
        if (b == null) return -1;

        int ia = 0, ib = 0, na = a.length(), nb = b.length();
        while (ia < na && ib < nb) {
            char ca = Character.toLowerCase(a.charAt(ia));
            char cb = Character.toLowerCase(b.charAt(ib));

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ja = ia; while (ja < na && Character.isDigit(a.charAt(ja))) ja++;
                int jb = ib; while (jb < nb && Character.isDigit(b.charAt(jb))) jb++;

                String da = a.substring(ia, ja);
                String db = b.substring(ib, jb);

                // strip leading zeros
                String da2 = da.replaceFirst("^0+(?!$)", "");
                String db2 = db.replaceFirst("^0+(?!$)", "");

                int cmp = Integer.compare(da2.length(), db2.length());
                if (cmp == 0) cmp = da2.compareTo(db2);
                if (cmp != 0) return cmp;

                ia = ja; ib = jb; // numbers equal → continue
                continue;
            }

            if (ca != cb) return ca - cb;
            ia++; ib++;
        }
        return (na - ia) - (nb - ib);
    }

    // result holder
    private record ExecResult(String stdout, String stderr, int exitCode) {}
}
