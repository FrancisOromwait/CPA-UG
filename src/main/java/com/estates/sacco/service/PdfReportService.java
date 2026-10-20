package com.estates.sacco.service;

import com.estates.sacco.service.ReportDoc.Col;
import com.estates.sacco.service.ReportDoc.Kv;
import com.estates.sacco.service.ReportDoc.Section;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.estates.sacco.web.ApiException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders a ReportDoc as an A4 PDF. */
@Service
public class PdfReportService {
    private static final Color BRAND = Color.decode("#1d4ed8");
    private static final Color LIGHT = Color.decode("#eaf1ff");
    private static final Color LINE = Color.decode("#d4e0f7");

    private final DeploymentSettings settings;

    public PdfReportService(DeploymentSettings settings) {
        this.settings = settings;
    }

    public byte[] render(ReportDoc doc, String generatedBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // the member statement has a column for every kind of account, so it is printed wide with a line round every cell
        boolean grid = "Member Statement".equals(doc.title());
        Document d = new Document(grid ? PageSize.A4.rotate() : PageSize.A4, 36, 36, 40, 50);
        try {
            PdfWriter.getInstance(d, out);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH));
            HeaderFooter footer = new HeaderFooter(new Phrase("Generated " + stamp + " by " + generatedBy + "   |   Page ",
                    font(Font.NORMAL, 8, Color.GRAY)), true);
            footer.setBorder(Rectangle.NO_BORDER);
            footer.setAlignment(Element.ALIGN_CENTER);
            d.setFooter(footer);
            d.open();

            d.add(new Paragraph(settings.getAppName(), font(Font.BOLD, 9, Color.GRAY)));
            Paragraph title = new Paragraph(doc.title(), font(Font.BOLD, 18, BRAND));
            title.setSpacingBefore(2);
            d.add(title);
            d.add(new Paragraph(doc.branchLine(), font(Font.BOLD, 12, Color.BLACK)));
            if (doc.subjectLine() != null) d.add(new Paragraph(doc.subjectLine(), font(Font.NORMAL, 11, Color.BLACK)));
            d.add(new Paragraph(doc.periodLine(), font(Font.NORMAL, 10, Color.DARK_GRAY)));

            if (!doc.totals().isEmpty()) d.add(totalsBox(doc.totals()));
            if (doc.note() != null) {
                Paragraph n = new Paragraph(doc.note(), font(Font.ITALIC, 9, Color.DARK_GRAY));
                n.setSpacingBefore(8);
                d.add(n);
            }
            for (Section s : doc.sections()) addSection(d, s, grid);
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not create the PDF");
        } finally {
            if (d.isOpen()) d.close();
        }
        return out.toByteArray();
    }

    /** The heading totals, one box per figure. */
    private PdfPTable totalsBox(List<Kv> totals) throws DocumentException {
        PdfPTable t = new PdfPTable(Math.min(totals.size(), 4));
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);
        for (Kv kv : totals) {
            PdfPCell c = new PdfPCell();
            c.setBorderColor(LINE);
            c.setBackgroundColor(LIGHT);
            c.setPadding(7);
            c.addElement(new Paragraph(kv.label(), font(Font.NORMAL, 8, Color.DARK_GRAY)));
            c.addElement(new Paragraph(kv.value(), font(Font.BOLD, 12, Color.BLACK)));
            t.addCell(c);
        }
        // pad the last row so every box keeps its width
        int cols = Math.min(totals.size(), 4);
        for (int i = 0; i < (cols - totals.size() % cols) % cols; i++) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            t.addCell(empty);
        }
        return t;
    }

    private void addSection(Document d, Section s, boolean grid) throws DocumentException {
        Paragraph h = new Paragraph(s.heading(), font(Font.BOLD, 11, BRAND));
        h.setSpacingBefore(14);
        h.setSpacingAfter(4);
        d.add(h);
        if (s.rows().isEmpty() && s.totalRow() == null) {
            d.add(new Paragraph("No entries.", font(Font.ITALIC, 9, Color.GRAY)));
            return;
        }
        List<Col> cols = s.columns();
        PdfPTable t = new PdfPTable(cols.size());
        t.setWidthPercentage(100);
        float[] widths = new float[cols.size()];
        for (int i = 0; i < cols.size(); i++) widths[i] = width(cols.get(i));
        t.setWidths(widths);
        t.setHeaderRows(1);
        for (Col c : cols) t.addCell(boxed(cell(c.name(), font(Font.BOLD, 9, Color.DARK_GRAY), c.numeric(), LIGHT), grid));
        for (List<String> row : s.rows()) {
            for (int i = 0; i < cols.size(); i++) t.addCell(boxed(cell(row.get(i), font(Font.NORMAL, 9, Color.BLACK), cols.get(i).numeric(), null), grid));
        }
        if (s.rows().isEmpty()) {
            PdfPCell none = boxed(cell("No entries", font(Font.ITALIC, 9, Color.GRAY), false, null), grid);
            none.setColspan(cols.size());
            t.addCell(none);
        }
        if (s.totalRow() != null) {
            for (int i = 0; i < cols.size(); i++) {
                PdfPCell c = boxed(cell(s.totalRow().get(i), font(Font.BOLD, 9, Color.BLACK), cols.get(i).numeric(), LIGHT), grid);
                c.setBorderWidthTop(1f);
                t.addCell(c);
            }
        }
        d.add(t);
    }

    private static float width(Col c) {
        if (c.numeric()) return 1.5f;
        return switch (c.name()) {
            case "Account", "Description" -> 3.6f;
            case "Code" -> 0.9f;
            case "No" -> 0.6f;
            case "Date" -> 1.4f;
            default -> 1.5f;
        };
    }

    /** A line round the whole cell (a bordered table); otherwise the cell keeps just its line underneath. */
    private static PdfPCell boxed(PdfPCell c, boolean grid) {
        if (grid) {
            // each side is set on its own, because the plain cell has switched its left, right and top off
            c.setBorderWidthLeft(0.6f);
            c.setBorderWidthRight(0.6f);
            c.setBorderWidthTop(0.6f);
            c.setBorderWidthBottom(0.6f);
            c.setBorderColor(Color.decode("#9bb2e6"));
        }
        return c;
    }

    private static PdfPCell cell(String text, Font f, boolean right, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setPadding(4);
        c.setBorderColor(LINE);
        c.setBorderWidthLeft(0);
        c.setBorderWidthRight(0);
        c.setBorderWidthTop(0);
        c.setHorizontalAlignment(right ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        if (bg != null) c.setBackgroundColor(bg);
        return c;
    }

    private static Font font(int style, float size, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }
}
