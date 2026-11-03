package com.estates.sacco.service;

import com.estates.sacco.service.ReportDoc.Col;
import com.estates.sacco.service.ReportDoc.Kv;
import com.estates.sacco.service.ReportDoc.Section;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Any report as an Excel workbook (.xlsx), from the same {@link ReportDoc} the screen and the PDF are drawn from, so all three show
 * the same figures. Amounts become numbers Excel can add up ("(1,234.50)" is -1234.50); headings and total lines are bold.
 */
@Service
public class XlsxReportService {
    private static final Pattern AMOUNT = Pattern.compile("^\\(?-?[\\d,]+(\\.\\d+)?\\)?$");

    public byte[] render(ReportDoc doc) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName(doc.title()));
            CellStyle title = style(wb, true, (short) 14, false, false);
            CellStyle bold = style(wb, true, (short) 11, false, false);
            CellStyle head = style(wb, true, (short) 11, true, false);
            CellStyle headRight = style(wb, true, (short) 11, true, false);
            headRight.setAlignment(HorizontalAlignment.RIGHT);
            CellStyle money = style(wb, false, (short) 11, false, true);
            CellStyle moneyBold = style(wb, true, (short) 11, false, true);
            int width = doc.sections().stream().mapToInt(s -> s.columns().size()).max().orElse(2);

            int r = 0;
            text(sheet.createRow(r++), 0, doc.title(), title);
            for (String line : new String[] {doc.branchLine(), doc.subjectLine(), doc.periodLine()}) {
                if (line != null && !line.isBlank()) text(sheet.createRow(r++), 0, line, null);
            }
            if (doc.totals() != null && !doc.totals().isEmpty()) {
                r++;
                for (Kv kv : doc.totals()) {
                    Row row = sheet.createRow(r++);
                    text(row, 0, kv.label(), bold);
                    value(row, 1, kv.value(), true, money, null);
                }
            }
            for (Section s : doc.sections()) {
                r++;
                if (s.heading() != null && !s.heading().isBlank()) text(sheet.createRow(r++), 0, s.heading(), bold);
                Row h = sheet.createRow(r++);
                List<Col> cols = s.columns();
                for (int c = 0; c < cols.size(); c++) {
                    text(h, c, cols.get(c).name(), cols.get(c).numeric() ? headRight : head);
                }
                for (List<String> cells : s.rows()) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < cells.size() && c < cols.size(); c++) value(row, c, cells.get(c), cols.get(c).numeric(), money, null);
                }
                if (s.totalRow() != null) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < s.totalRow().size() && c < cols.size(); c++) {
                        value(row, c, s.totalRow().get(c), cols.get(c).numeric(), moneyBold, bold);
                    }
                }
            }
            if (doc.note() != null && !doc.note().isBlank()) {
                r++;
                text(sheet.createRow(r), 0, doc.note(), null);
            }
            // widths from the longest text in each column (Excel's own auto-size needs fonts the server may not have)
            int[] chars = new int[Math.max(width, 2)];
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;   // the title spans the columns
                for (Cell cell : row) {
                    if (cell.getColumnIndex() >= chars.length) continue;
                    int n = cell.getCellType() == CellType.NUMERIC ? String.format("%,.2f", cell.getNumericCellValue()).length() + 2 : cell.getStringCellValue().length();
                    chars[cell.getColumnIndex()] = Math.max(chars[cell.getColumnIndex()], n);
                }
            }
            for (int c = 0; c < chars.length; c++) sheet.setColumnWidth(c, Math.min(Math.max(chars[c], 8) + 2, 60) * 256);
            if (width > 1) sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, width - 1));
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A cell: a number for an amount in a numeric column, otherwise the text as shown. */
    private static void value(Row row, int c, String v, boolean numeric, CellStyle money, CellStyle textStyle) {
        if (v == null || v.isBlank()) return;
        String t = v.trim();
        if (numeric && AMOUNT.matcher(t).matches()) {
            boolean negative = t.startsWith("(") || t.startsWith("-");
            BigDecimal n = new BigDecimal(t.replaceAll("[(),\\-]", ""));
            Cell cell = row.createCell(c);
            cell.setCellValue((negative ? n.negate() : n).doubleValue());
            cell.setCellStyle(money);
        } else {
            text(row, c, t, textStyle);
        }
    }

    private static void text(Row row, int c, String v, CellStyle style) {
        Cell cell = row.createCell(c);
        cell.setCellValue(v);
        if (style != null) cell.setCellStyle(style);
    }

    private static CellStyle style(Workbook wb, boolean bold, short size, boolean header, boolean amount) {
        Font f = wb.createFont();
        f.setBold(bold);
        f.setFontHeightInPoints(size);
        CellStyle s = wb.createCellStyle();
        s.setFont(f);
        if (header) {
            s.setBorderBottom(BorderStyle.THIN);
            s.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (amount) {
            s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00;(#,##0.00)"));
            s.setAlignment(HorizontalAlignment.RIGHT);
        }
        return s;
    }

    /** Excel sheet names: at most 31 characters, none of : \ / ? * [ ]. */
    private static String sheetName(String title) {
        String s = title == null || title.isBlank() ? "Report" : title.replaceAll("[:\\\\/?*\\[\\]]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
