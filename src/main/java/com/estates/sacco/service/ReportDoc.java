package com.estates.sacco.service;

import java.util.List;

/**
 * A finished report as plain text cells, so the web page and the PDF render exactly the same figures.
 * The totals shown in the heading are in {@code totals}.
 */
public record ReportDoc(String title, String branchLine, String subjectLine, String periodLine,
                        List<Kv> totals, List<Section> sections, String note) {

    public record Kv(String label, String value) {}

    public record Col(String name, boolean numeric) {}

    /** {@code totalRow} is null when the section has no total line. */
    public record Section(String heading, List<Col> columns, List<List<String>> rows, List<String> totalRow) {}
}
