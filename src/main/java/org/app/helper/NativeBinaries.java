package org.app.helper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

public final class NativeBinaries {
    private NativeBinaries() {}

    public static Path unpackPdftotext() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        boolean isWindows = os.contains("win");
        String base = isWindows ? "/native/windows" : "/native/macos";
        String exe = isWindows ? "pdftotext.exe" : "pdftotext";

        // target dir under user cache (stable across runs)
        Path dir = Paths.get(System.getProperty("user.home"), ".registru-evidenta", "bin");
        Files.createDirectories(dir);

        // copy the binary
        Path bin = dir.resolve(exe);
        copyResource(base + "/" + exe, bin);

        // windows: copy side DLLs if present in resources
        if (isWindows) {
            for (String dll : List.of("libgcc_s_seh-1.dll", "libstdc++-6.dll", "libwinpthread-1.dll")) {
                try {
                    copyResource(base + "/" + dll, dir.resolve(dll));
                } catch (NoSuchFileException ignore) {
                    // ok if you used xpdf-tools .exe that needs no extra dlls
                }
            }
        } else {
            // mac/linux: ensure executable bit
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(bin);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(bin, perms);
            } catch (UnsupportedOperationException ignored) {
                // FS not POSIX, best-effort
                bin.toFile().setExecutable(true);
            }
        }

        return bin;
    }

    private static void copyResource(String resourcePath, Path dest) throws IOException {
        try (InputStream in = NativeBinaries.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new NoSuchFileException("Resource not found: " + resourcePath);
            // only overwrite if different size (cheap dedup)
            boolean write = true;
            if (Files.exists(dest)) {
                long existing = Files.size(dest);
                long incoming = in.available();
                write = (incoming <= 0) || (existing != incoming);
            }
            if (write) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
