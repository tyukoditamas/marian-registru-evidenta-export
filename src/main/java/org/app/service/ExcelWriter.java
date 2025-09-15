package org.app.controller;

import org.apache.poi.ss.util.RegionUtil;
import org.app.model.RegistruEvidentaDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.*;
import java.util.List;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public final class ExcelWriter {
    private ExcelWriter(){}

    private static final List<String> BOTTOM_HEADERS = List.of(
            "Nr. crt.",
            "Data",
            "Felul",
            "Numărul",
            "Data",
            "De unde provine",
            "Nr. identificare al mijlocului de transport sau numele navei, nr. aeronavei",
            "Numele exportatorului / expeditorului",
            "Felul",
            "Buc.",
            "Mărci și numere",
            "Greutate",
            "",
            "",
            ""
    );

    private static void addTopBanner(Sheet sheet, Workbook wb) {
        final int LAST_COL = MAX_CHARS.length - 1; // 14 with your current columns
        final int RIGHT_BLOCK_START = 13;          // "IEȘIRE EFECTIVĂ" covers 13..14

        // Make space for 2 rows at the top
        if (sheet.getLastRowNum() >= 0) {
            sheet.shiftRows(0, sheet.getLastRowNum(), 2, true, false);
        }

        // Title style
        Font titleFont = wb.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short)18);

        CellStyle titleStyle = wb.createCellStyle();
        titleStyle.setAlignment(HorizontalAlignment.CENTER);
        titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        titleStyle.setWrapText(true);
        titleStyle.setFont(titleFont);

        // Subheader style
        Font subFont = wb.createFont();
        subFont.setBold(true);
        subFont.setFontHeightInPoints((short)11);

        CellStyle subStyle = wb.createCellStyle();
        subStyle.setAlignment(HorizontalAlignment.CENTER);
        subStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        subStyle.setWrapText(true);
        subStyle.setFont(subFont);

        // Row 0: big title across all columns
        Row r0 = sheet.createRow(0);
        r0.setHeightInPoints(26);
        Cell c0 = r0.createCell(0, CellType.STRING);
        c0.setCellValue("REGISTRU DE EVIDENȚĂ A MĂRFURILOR LA IEȘIRE");
        c0.setCellStyle(titleStyle);

        CellRangeAddress top = new CellRangeAddress(0, 0, 0, LAST_COL);
        sheet.addMergedRegion(top);
        outlineMerged(sheet, top);

        // Row 1: left block "PREZENTATE LA IEȘIRE"
        Row r1 = sheet.createRow(1);
        r1.setHeightInPoints(18);

        Cell cLeft = r1.createCell(0, CellType.STRING);
        cLeft.setCellValue("PREZENTATE LA IEȘIRE");
        cLeft.setCellStyle(subStyle);

        CellRangeAddress left = new CellRangeAddress(1, 1, 0, RIGHT_BLOCK_START - 1); // 0..12
        sheet.addMergedRegion(left);
        outlineMerged(sheet, left);

        // Row 1: right block "IEȘIRE EFECTIVĂ"
        Cell cRight = r1.createCell(RIGHT_BLOCK_START, CellType.STRING);
        cRight.setCellValue("IEȘIRE EFECTIVĂ");
        cRight.setCellStyle(subStyle);

        CellRangeAddress right = new CellRangeAddress(1, 1, RIGHT_BLOCK_START, LAST_COL); // 13..14
        sheet.addMergedRegion(right);
        outlineMerged(sheet, right);
    }

    public static int appendOrCreate(File xlsxFile, List<RegistruEvidentaDto> rows, int startIndex) throws Exception {
        if (rows == null || rows.isEmpty()) return 0;

        final Path path = xlsxFile.toPath();
        final boolean exists = Files.exists(path);

        Workbook wb;
        Sheet sheet;
        if (!exists) {
            wb = new XSSFWorkbook();
            sheet = wb.createSheet("Registru");
            addTopBanner(sheet, wb);
            createHeader(sheet, wb, 2);
        } else {
            try (InputStream is = Files.newInputStream(path, StandardOpenOption.READ)) {
                wb = WorkbookFactory.create(is); // read-write in memory
            } catch (Exception e) {
                throw new IOException("Existing Excel is unreadable/corrupted: " + xlsxFile.getName(), e);
            }
            sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : wb.createSheet("Registru");
            validateHeaderOrThrow(sheet);
        }

        // one centered style for all data cells
        CellStyle dataStyle = createHandwritingDataStyle(wb);
        dataStyle.setAlignment(HorizontalAlignment.CENTER);
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        dataStyle.setWrapText(true);
        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);

        int nextRow = findNextEmptyDataRow(sheet);
        int idx = startIndex;

        for (RegistruEvidentaDto dto : rows) {
            Row r = sheet.getRow(nextRow);
            if (r == null) r = sheet.createRow(nextRow);
            int c = 0;

            // 1) Nr. crt.
            setNumber(r, c++, idx++, dataStyle);

            // 2) Data (from your DTO – adjust getter name to your model)
            setText(r, c++, safe(dto.getDataDeclaratie()), dataStyle);

            // 3..6) Documente însoțitoare
            setText(r, c++, "SAD", dataStyle);                         // Felul (or leave empty if you prefer)
            setText(r, c++, safe(dto.getNrMrn()), dataStyle);          // Numărul (MRN)
            setText(r, c++, safe(dto.getDataDeclaratie()), dataStyle); // Data document
            setText(r, c++, "", dataStyle);                            // De unde provine

            // 7) Transport identificare
            setText(r, c++, safe(dto.getIdentificare()), dataStyle);

            // 8) Exportator / Expeditor
            setText(r, c++, safe(dto.getNumeExportator()), dataStyle);

            // 9..11) Colete
            setText(r, c++, "", dataStyle);                            // Felul coletelor
            String buc = safe(dto.getBuc());
            if (isNumeric(buc)) setNumber(r, c++, Double.parseDouble(buc.replace(",", ".")), dataStyle);
            else setText(r, c++, buc, dataStyle);                      // Buc.
            setText(r, c++, "", dataStyle);                            // Mărci și numere

            // 12) Greutate
            String g = dto.getGreutate();
            if (isNumeric(g)) setNumber(r, c++, Double.parseDouble(g.replace(",", ".")), dataStyle);
            else setText(r, c++, safe(g), dataStyle);

            // 13) Felul mărfurilor
            String fel = dto.getDescriereaMarfurilor() != null ? dto.getDescriereaMarfurilor() : "";
            setText(r, c++, safe(fel), dataStyle);

            // 14) Mențiuni speciale (under IEȘIRE EFECTIVĂ)
            setText(r, c++, "", dataStyle);

            // 15) IEȘIRE EFECTIVĂ – Data
            setText(r, c++, safe(dto.getDataDeclaratie()), dataStyle);

            nextRow++;
        }

        // sizing & freeze
        applyColumnSizing(sheet);       // merged-aware autosize + min widths
        sheet.createFreezePane(0, 4);

        // safe write: temp then atomic replace
        Path tmp = tempSibling(path, ".tmp");
        try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            wb.write(os);
        } finally {
            try { wb.close(); } catch (Exception ignore) {}
        }
        try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }

        return rows.size();
    }


    // ---------- header creation & validation ----------

    private static void createHeader(Sheet sheet, Workbook wb, int rowOffset) {
        Font bold = wb.createFont();
        bold.setBold(true);

        CellStyle topStyle = wb.createCellStyle();
        topStyle.setWrapText(true);
        topStyle.setAlignment(HorizontalAlignment.CENTER);
        topStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        topStyle.setFont(bold);
        topStyle.setBorderTop(BorderStyle.THIN);
        topStyle.setBorderBottom(BorderStyle.THIN);
        topStyle.setBorderLeft(BorderStyle.THIN);
        topStyle.setBorderRight(BorderStyle.THIN);

        CellStyle bottomStyle = wb.createCellStyle();
        bottomStyle.cloneStyleFrom(topStyle);

        final int t = rowOffset;       // top header row
        final int b = rowOffset + 1;   // bottom/leaf header row

        Row top = sheet.createRow(t);
        Row bottom = sheet.createRow(b);
        top.setHeightInPoints(40);
        bottom.setHeightInPoints(28);

        // bottom (leaf) headers
        for (int i = 0; i < BOTTOM_HEADERS.size(); i++) {
            Cell c = bottom.createCell(i, CellType.STRING);
            c.setCellValue(BOTTOM_HEADERS.get(i));
            c.setCellStyle(bottomStyle);
        }

        // A: Nr. crt.
        setHeader(top, 0, "Nr. crt.", topStyle);
        CellRangeAddress rA = new CellRangeAddress(t, b, 0, 0);
        sheet.addMergedRegion(rA); outlineMerged(sheet, rA);

        // B: Data
        setHeader(top, 1, "Data", topStyle);
        CellRangeAddress rB = new CellRangeAddress(t, b, 1, 1);
        sheet.addMergedRegion(rB); outlineMerged(sheet, rB);

        // C..F: Documente însoțitoare
        setHeader(top, 2, "Documente însoțitoare", topStyle);
        CellRangeAddress rC = new CellRangeAddress(t, t, 2, 5);
        sheet.addMergedRegion(rC); outlineMerged(sheet, rC);

        // G: Transport identificare
        setHeader(top, 6, "Nr. identificare al mijlocului de transport sau numele navei, nr. aeronavei", topStyle);
        CellRangeAddress rG = new CellRangeAddress(t, b, 6, 6);
        sheet.addMergedRegion(rG); outlineMerged(sheet, rG);

        // H: Exportator/Expeditor
        setHeader(top, 7, "Numele exportatorului / expeditorului", topStyle);
        CellRangeAddress rH = new CellRangeAddress(t, b, 7, 7);
        sheet.addMergedRegion(rH); outlineMerged(sheet, rH);

        // I..K: Colete
        setHeader(top, 8, "Colete", topStyle);
        CellRangeAddress rI = new CellRangeAddress(t, t, 8, 10);
        sheet.addMergedRegion(rI); outlineMerged(sheet, rI);

        // L: Greutate
        setHeader(top, 11, "Greutate", topStyle);
        CellRangeAddress rL = new CellRangeAddress(t, b, 11, 11);
        sheet.addMergedRegion(rL); outlineMerged(sheet, rL);

        setHeader(top, 12, "Felul mărfurilor", topStyle);
        CellRangeAddress rM = new CellRangeAddress(t, b, 12, 12);
        sheet.addMergedRegion(rM);
        outlineMerged(sheet, rM);

        setHeader(top, 13, "Mențiuni speciale", topStyle);
        CellRangeAddress mS = new CellRangeAddress(t, b, 13, 13);
        sheet.addMergedRegion(mS);
        outlineMerged(sheet, mS);

        setHeader(top, 14, "Data", topStyle);
        CellRangeAddress d = new CellRangeAddress(t, b, 14, 14);
        sheet.addMergedRegion(d);
        outlineMerged(sheet, d);
    }

    private static final int[] MIN_CHARS = {
            0,   // 0 Nr. crt.
            12,  // 1 Data (make wider if you want)
            10,  // 2 Felul
            16,  // 3 Numărul
            12,  // 4 Data (doc)
            12,  // 5 De unde provine
            26,  // 6 Identificare transport
            28,  // 7 Exportator/Expeditor
            10,  // 8 Colete Felul
            10,  // 9 Buc.
            12,  // 10 Mărci și numere
            10,  // 11 Greutate
            28,  // 12 Felul mărfurilor (vertical header but data column—pick what looks good)
            16,  // 13 Mențiuni speciale
            12   // 14 Data (IEȘIRE EFECTIVĂ)
    };

    private static void applyColumnSizing(Sheet sheet) {
        // 1) autosize (merged-aware when XSSF)
        try {
            org.apache.poi.xssf.usermodel.XSSFSheet xs = (org.apache.poi.xssf.usermodel.XSSFSheet) sheet;
            for (int i = 0; i < MAX_CHARS.length; i++) {
                xs.autoSizeColumn(i, true);
            }
        } catch (ClassCastException ignore) {
            for (int i = 0; i < MAX_CHARS.length; i++) {
                try { sheet.autoSizeColumn(i); } catch (Exception ignored) {}
            }
        }

        // 2) clamp each column: width := min( MAX, max(current, MIN) )
        for (int i = 0; i < MAX_CHARS.length; i++) {
            int cur = sheet.getColumnWidth(i);
            int min = MIN_CHARS[i] > 0 ? MIN_CHARS[i] * 256 : cur;  // only enforce if > 0
            int max = MAX_CHARS[i] * 256;

            int target = cur;
            if (target < min) target = min;
            if (target > max) target = max;

            if (target != cur) {
                sheet.setColumnWidth(i, target);
            }
        }
    }
    private static final int[] MAX_CHARS = {
            8,   // Nr. crt.
            14,  // Data
            12,  // Doc. Felul
            20,  // Doc. Numărul
            14,  // Doc. Data
            14,  // Doc. De unde provine
            26,  // Nr. identificare al mijlocului...
            28,  // Numele exportatorului / expeditorului
            10,  // Colete - Felul
            10,   // Colete - Buc.
            14,  // Colete - Mărci și numere
            10,  // Greutate
            36,  // Felul mărfurilor
            18,  // Mențiuni speciale
            14   // IEȘIRE EFECTIVĂ - Data
    };

    // --- NEW: border helper for merged regions ---
    private static void outlineMerged(Sheet sheet, CellRangeAddress region) {
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
    }

    private static void validateHeaderOrThrow(Sheet sheet) {
        Integer bottomIdx = null;
        int scanMax = Math.min(10, sheet.getLastRowNum());

        // 1) Primary: the bottom header row must have "Felul" in column 2
        for (int r = 0; r <= scanMax; r++) {
            if ("Felul".equals(getCellString(sheet.getRow(r), 2))) {
                bottomIdx = r;
                break;
            }
        }

        // 2) Fallback: find the "Documente însoțitoare" group label and pick the next row
        if (bottomIdx == null) {
            for (int r = 0; r <= scanMax; r++) {
                if ("Documente însoțitoare".equals(getCellString(sheet.getRow(r), 2))) {
                    if (r + 1 <= sheet.getLastRowNum()) bottomIdx = r + 1;
                    break;
                }
            }
        }

        if (bottomIdx == null) {
            throw new IllegalStateException("Could not locate the bottom header row.");
        }

        // Compare against expected bottom headers (skip the 3 vertically-merged columns)
        for (int i = 0; i < BOTTOM_HEADERS.size(); i++) {
            if (i == 12 || i == 13 || i == 14) continue; // vertical-merged, intentionally blank on the leaf row
            String expected = BOTTOM_HEADERS.get(i);
            String actual = getCellString(sheet.getRow(bottomIdx), i);
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "Header mismatch at column " + (i + 1) +
                                ". Expected: '" + expected + "', Found: '" + actual + "'."
                );
            }
        }
    }


    private static String getCellString(Row row, int col) {
        if (row == null) return "";
        Cell c = row.getCell(col);
        return (c == null) ? "" : c.getCellType() == CellType.STRING ? c.getStringCellValue().trim() : c.toString().trim();
    }

    // ---------- helpers ----------

    private static boolean isRowEmpty(Row row) {
        if (row == null) return true;
        short last = row.getLastCellNum();
        if (last <= 0) return true;
        for (int i = 0; i < last; i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK && !cell.toString().isBlank())
                return false;
        }
        return true;
    }

    /** Data start at row index 2 (after the two header rows). */
    private static int findNextEmptyDataRow(Sheet sheet) {
        int firstDataRow = 4;
        int r = Math.max(firstDataRow, sheet.getLastRowNum() + 1);
        while (r > firstDataRow && isRowEmpty(sheet.getRow(r - 1))) r--;
        while (!isRowEmpty(sheet.getRow(r))) r++;
        return r;
    }

    private static void setHeader(Row top, int col, String text, CellStyle style) {
        Cell cell = top.getCell(col);
        if (cell == null) cell = top.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        try { Double.parseDouble(s.replace(",", ".")); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private static Path tempSibling(Path target, String ext) throws IOException {
        String base = target.getFileName().toString();
        Path dir = target.getParent();
        String tmpName = base + ext;
        Path tmp = dir.resolve(tmpName);
        // ensure the tmp path exists and is writable
        if (!Files.exists(tmp)) Files.createFile(tmp);
        return tmp;
    }

    private static void setText(Row r, int col, String value, CellStyle style) {
        Cell cell = r.getCell(col);
        if (cell == null) cell = r.createCell(col, CellType.STRING);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void setNumber(Row r, int col, double value, CellStyle style) {
        Cell cell = r.getCell(col);
        if (cell == null) cell = r.createCell(col, CellType.NUMERIC);
        cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    private static String pickHandwritingFont() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] prefs = os.contains("mac")
                ? new String[]{"Noteworthy","Marker Felt","Chalkboard SE"}
                : new String[]{"Segoe Script","Lucida Handwriting","Brush Script MT","Comic Sans MS"};

        try {
            String[] installed = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(installed));
            for (String f : prefs) if (set.contains(f)) return f;
        } catch (Throwable ignore) { /* headless env or AWT unavailable */ }

        return null; // fall back to default (Calibri etc.)
    }

    private static CellStyle createHandwritingDataStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);

        Font f = wb.createFont();
        String name = pickHandwritingFont();
        if (name != null) f.setFontName(name);   // use OS-default handwriting font if present
        f.setItalic(true);                       // subtle handwritten vibe even on fallback fonts
        f.setFontHeightInPoints((short)12);
        s.setFont(f);
        return s;
    }
}
