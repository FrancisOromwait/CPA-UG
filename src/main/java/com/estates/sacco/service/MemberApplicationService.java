package com.estates.sacco.service;

import com.estates.sacco.domain.Member;
import com.estates.sacco.domain.MemberDetails;
import com.estates.sacco.domain.MemberRelative;
import com.estates.sacco.repo.MemberDetailsRepository;
import com.estates.sacco.repo.MemberRelativeRepository;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The CPA SACCO membership application form: its details are kept with the member ({@link MemberDetails}, {@link MemberRelative}),
 * edited on the member's page, and printed filled in, laid out as the paper form, for the applicant to sign.
 */
@Service
@RequiredArgsConstructor
public class MemberApplicationService {
    public static final int BENEFICIARIES = 3, CHILDREN = 6;
    private static final Color BRAND = Color.decode("#1d4ed8");
    private static final Color HEAD = Color.decode("#dbe7f7");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private final MemberDetailsRepository details;
    private final MemberRelativeRepository relatives;
    private final MemberPhotoService photos;

    public record Relative(String name, String relationship, String contact, Boolean alive) {}

    /** Everything on the form. Name, telephone, NIN and place of work are the member record's own and are shown, not saved, here. */
    public record Application(String memberNo, String fullName, String phone, String nationalId, String employer,
                              String fmNumber, String icpauId, String address, String email, LocalDate dateOfBirth, Integer age,
                              String passportNo, String tin, String maritalStatus, String spouseName, String spouseTel,
                              String designation, String nextOfKin, String remarks, LocalDate applicationDate,
                              String checkedBy, String checkedByDesignation, LocalDate checkedOn, String approvedBy, String approvedByDesignation,
                              List<Relative> beneficiaries, List<Relative> children, Relative mother, Relative father) {}

    @Transactional(readOnly = true)
    public Application get(Member m) {
        MemberDetails d = details.findByMemberId(m.getId()).orElseGet(MemberDetails::new);
        List<Relative> beneficiaries = new ArrayList<>(), children = new ArrayList<>();
        Relative mother = null, father = null;
        for (MemberRelative r : relatives.findByMemberIdOrderByKindAscPositionAsc(m.getId())) {
            Relative x = new Relative(r.getName(), r.getRelationship(), r.getContact(), r.getAlive());
            switch (r.getKind()) {
                case MemberRelative.BENEFICIARY -> beneficiaries.add(x);
                case MemberRelative.CHILD -> children.add(x);
                case MemberRelative.MOTHER -> mother = x;
                case MemberRelative.FATHER -> father = x;
                default -> { }
            }
        }
        Integer age = d.getDateOfBirth() == null ? null : Period.between(d.getDateOfBirth(), LocalDate.now()).getYears();
        return new Application(m.getMemberNo(), m.getFullName(), m.getPhone(), m.getNationalId(), m.getEmployer(),
                d.getFmNumber(), d.getIcpauId(), d.getAddress(), d.getEmail(), d.getDateOfBirth(), age,
                d.getPassportNo(), d.getTin(), d.getMaritalStatus(), d.getSpouseName(), d.getSpouseTel(),
                d.getDesignation(), d.getNextOfKin(), d.getRemarks(), d.getApplicationDate(),
                d.getCheckedBy(), d.getCheckedByDesignation(), d.getCheckedOn(), d.getApprovedBy(), d.getApprovedByDesignation(),
                beneficiaries, children, mother, father);
    }

    /** Saves the form's own details; the beneficiaries, children and parents are replaced by those sent (empty lines are left out). */
    @Transactional
    public void save(Member m, Application a) {
        MemberDetails d = details.findByMemberId(m.getId()).orElseGet(() -> {
            MemberDetails n = new MemberDetails();
            n.setMember(m);
            return n;
        });
        d.setFmNumber(clean(a.fmNumber()));
        d.setIcpauId(clean(a.icpauId()));
        d.setAddress(clean(a.address()));
        d.setEmail(clean(a.email()));
        d.setDateOfBirth(a.dateOfBirth());
        d.setPassportNo(clean(a.passportNo()));
        d.setTin(clean(a.tin()));
        String ms = clean(a.maritalStatus());
        d.setMaritalStatus(ms == null ? null : List.of("SINGLE", "MARRIED", "OTHER").contains(ms.toUpperCase()) ? ms.toUpperCase() : null);
        d.setSpouseName(clean(a.spouseName()));
        d.setSpouseTel(clean(a.spouseTel()));
        d.setDesignation(clean(a.designation()));
        d.setNextOfKin(clean(a.nextOfKin()));
        d.setRemarks(clean(a.remarks()));
        d.setApplicationDate(a.applicationDate());
        d.setCheckedBy(clean(a.checkedBy()));
        d.setCheckedByDesignation(clean(a.checkedByDesignation()));
        d.setCheckedOn(a.checkedOn());
        d.setApprovedBy(clean(a.approvedBy()));
        d.setApprovedByDesignation(clean(a.approvedByDesignation()));
        details.save(d);

        relatives.deleteByMemberId(m.getId());
        relatives.flush();
        keep(m, MemberRelative.BENEFICIARY, a.beneficiaries(), BENEFICIARIES);
        keep(m, MemberRelative.CHILD, a.children(), CHILDREN);
        keep(m, MemberRelative.MOTHER, a.mother() == null ? List.of() : List.of(a.mother()), 1);
        keep(m, MemberRelative.FATHER, a.father() == null ? List.of() : List.of(a.father()), 1);
    }

    /** The member's form details go with the member (a member is deleted only when it has no history). */
    @Transactional
    public void remove(Long memberId) {
        relatives.deleteByMemberId(memberId);
        details.deleteByMemberId(memberId);
    }

    private void keep(Member m, String kind, List<Relative> list, int max) {
        if (list == null) return;
        int position = 0;
        for (Relative x : list) {
            if (x == null || (clean(x.name()) == null && clean(x.relationship()) == null && clean(x.contact()) == null && x.alive() == null)) continue;
            if (position == max) break;
            MemberRelative r = new MemberRelative();
            r.setMember(m);
            r.setKind(kind);
            r.setPosition(++position);
            r.setName(clean(x.name()));
            r.setRelationship(clean(x.relationship()));
            r.setContact(clean(x.contact()));
            r.setAlive(x.alive());
            relatives.save(r);
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ the printed form
    /** The form filled in, as the paper form: page 1 the applicant, page 2 the people named, the signature and official use. */
    @Transactional(readOnly = true)
    public byte[] pdf(Member m) {
        Application a = get(m);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 36, 50);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter w, Document d) {
                    Phrase p = new Phrase("Page " + w.getPageNumber() + " of 2", font(Font.NORMAL, 9, Color.DARK_GRAY));
                    com.lowagie.text.pdf.ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_CENTER, p,
                            (d.left() + d.right()) / 2, d.bottom() - 25, 0);
                }
            });
            doc.open();

            // heading: the society's name in the middle, the passport photo (or its box) on the right
            PdfPTable head = new PdfPTable(new float[] {1.3f, 3f, 1.3f});
            head.setWidthPercentage(100);
            head.addCell(blank());
            PdfPCell name = blank();
            name.setHorizontalAlignment(Element.ALIGN_CENTER);
            name.setVerticalAlignment(Element.ALIGN_MIDDLE);
            name.addElement(centered("CPA SACCO", font(Font.BOLD, 22, BRAND)));
            name.addElement(centered("CPA COOPERATIVE SOCIETY LTD", font(Font.BOLD, 13, Color.BLACK)));
            head.addCell(name);
            head.addCell(photoCell(m));
            doc.add(head);

            PdfPTable title = new PdfPTable(new float[] {3f, 1f});
            title.setWidthPercentage(100);
            title.setSpacingBefore(10);
            PdfPCell t1 = blank();
            t1.addElement(new Paragraph("MEMBERSHIP APPLICATION FORM", font(Font.BOLD, 11, Color.BLACK)));
            title.addCell(t1);
            PdfPCell t2 = blank();
            Paragraph no = new Paragraph(nz(a.memberNo()), font(Font.BOLD, 11, Color.BLACK));
            no.setAlignment(Element.ALIGN_RIGHT);
            t2.addElement(no);
            title.addCell(t2);
            doc.add(title);

            PdfPTable form = new PdfPTable(new float[] {2.1f, 3f});
            form.setWidthPercentage(100);
            form.setSpacingBefore(4);
            form.addCell(header("ITEM"));
            form.addCell(header("DETAILS"));
            item(form, "Name:", a.fullName());
            item(form, "FM Number (Member of ICPAU):", a.fmNumber());
            item(form, "ICPAU ID (Not yet ICPAU Member):", a.icpauId());
            item(form, "Postal or Residential Address:", a.address());
            item(form, "Telephone (All Mobile Lines):", a.phone());
            item(form, "Preferred Email:", a.email());
            item(form, "Age:", a.age() == null ? null : String.valueOf(a.age()));
            item(form, "Date of Birth:", a.dateOfBirth() == null ? null : a.dateOfBirth().format(DMY));
            item(form, "NIN (National ID Copy is Required):", a.nationalId());
            item(form, "Passport Number:", a.passportNo());
            item(form, "TIN (Tax Identification Number):", a.tin());
            String ms = a.maritalStatus() == null ? "" : a.maritalStatus();
            item(form, "Marital status: (Tick)", "Single: " + tick("SINGLE".equals(ms)) + "     Married: " + tick("MARRIED".equals(ms))
                    + "     Other: " + tick("OTHER".equals(ms)));
            item(form, "Name and contact for spouse:", join(a.spouseName(), a.spouseTel() == null ? null : "Tel: " + a.spouseTel()));
            item(form, "Place of Work (Current Employer):", a.employer());
            item(form, "Designation (Job Position):", a.designation());
            item(form, "Next of Kin (Name & Tel:)", a.nextOfKin());
            PdfPCell remarks = box("Other Remarks (if any): " + nz(a.remarks()), font(Font.BOLD, 10, Color.BLACK));
            remarks.setColspan(2);
            remarks.setMinimumHeight(60);
            form.addCell(remarks);
            doc.add(form);

            // page 2: the people named
            doc.newPage();
            doc.add(people("Nominated beneficiary(ies) (In case you are gone)", "Relationship", a.beneficiaries(), BENEFICIARIES, false));
            doc.add(people("Biological Children", "Relationship", a.children(), CHILDREN, false));
            List<Relative> parents = new ArrayList<>();
            parents.add(a.mother() == null ? new Relative(null, "Mother", null, null) : a.mother());
            parents.add(a.father() == null ? new Relative(null, "Father", null, null) : a.father());
            doc.add(people("Biological Parents", "Relationship and Status (A/D)", parents, 2, true));

            // signature and date
            PdfPTable sign = new PdfPTable(new float[] {1f, 0.6f, 1f});
            sign.setWidthPercentage(100);
            sign.setSpacingBefore(40);
            sign.addCell(signLine("", "Signature"));
            sign.addCell(blank());
            sign.addCell(signLine(a.applicationDate() == null ? "" : a.applicationDate().format(DMY), "Date"));
            doc.add(sign);

            // official use
            Paragraph official = new Paragraph("OFFICIAL USE", font(Font.BOLD, 11, Color.BLACK));
            official.setSpacingBefore(30);
            official.setSpacingAfter(10);
            doc.add(official);
            PdfPTable use = new PdfPTable(2);
            use.setWidthPercentage(100);
            use.addCell(field("Checked by:", ""));
            use.addCell(field("Approved by:", ""));
            use.addCell(field("Name:", a.checkedBy()));
            use.addCell(field("Name:", a.approvedBy()));
            use.addCell(field("Designation:", a.checkedByDesignation()));
            use.addCell(field("Designation:", a.approvedByDesignation()));
            use.addCell(field("Date:", a.checkedOn() == null ? "" : a.checkedOn().format(DMY)));
            use.addCell(blank());
            doc.add(use);
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not make the application form", e);
        }
        return out.toByteArray();
    }

    /** A table of people: No, Name, the relationship column, Contact, with as many lines as the paper form. */
    private static PdfPTable people(String heading, String relationshipHeading, List<Relative> list, int lines, boolean parents) {
        // the parents' middle column holds "Mother: Alive [ ]  Deceased [ ]", so it is wider
        PdfPTable t = new PdfPTable(parents ? new float[] {0.4f, 2.4f, 2.9f, 1.7f} : new float[] {0.4f, 3f, 2f, 2f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(14);
        PdfPCell h = blank();
        h.setColspan(4);
        h.setPaddingBottom(5);
        h.addElement(new Paragraph(heading, font(Font.BOLD, 10, Color.BLACK)));
        t.addCell(h);
        t.addCell(header("No"));
        t.addCell(header("Name"));
        t.addCell(header(relationshipHeading));
        t.addCell(header("Contact"));
        for (int i = 0; i < lines; i++) {
            Relative r = i < list.size() ? list.get(i) : null;
            t.addCell(box((i + 1) + ".", font(Font.BOLD, 10, Color.BLACK)));
            t.addCell(box(r == null ? "" : nz(r.name()), font(Font.NORMAL, 10, Color.BLACK)));
            String rel;
            if (parents) {
                String who = i == 0 ? "Mother:" : "Father:";
                Boolean alive = r == null ? null : r.alive();
                rel = who + " Alive " + tick(Boolean.TRUE.equals(alive)) + "  Deceased " + tick(Boolean.FALSE.equals(alive));
            } else {
                rel = r == null ? "" : nz(r.relationship());
            }
            t.addCell(box(rel, font(Font.NORMAL, 10, Color.BLACK)));
            t.addCell(box(r == null ? "" : nz(r.contact()), font(Font.NORMAL, 10, Color.BLACK)));
        }
        return t;
    }

    private PdfPCell photoCell(Member m) {
        byte[] photo = photos.photoOf(m.getId()).orElse(null);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(110);
        c.setBorderWidth(0.8f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        if (photo != null) {
            try {
                Image img = Image.getInstance(photo);
                img.scaleToFit(100, 104);
                c.setImage(img);
                c.setPadding(3);
                return c;
            } catch (Exception e) {
                // an unreadable photo: the empty box, as on paper
            }
        }
        c.setPhrase(new Phrase("Affix\nPassport Photo", font(Font.BOLD, 11, BRAND)));
        return c;
    }

    private static void item(PdfPTable t, String label, String value) {
        PdfPCell l = box(label, font(Font.BOLD, 10, Color.BLACK));
        l.setMinimumHeight(24);
        t.addCell(l);
        t.addCell(box(nz(value), font(Font.NORMAL, 10, Color.BLACK)));
    }

    private static PdfPCell header(String text) {
        PdfPCell c = box(text, font(Font.BOLD, 10, Color.BLACK));
        c.setBackgroundColor(HEAD);
        return c;
    }

    private static PdfPCell box(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setPadding(5);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBorderWidth(0.8f);
        return c;
    }

    private static PdfPCell blank() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    /** What is written above a line, and the line's name under it (the signature and date lines). */
    private static PdfPCell signLine(String written, String label) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        Paragraph w = centered(written.isEmpty() ? " " : written, font(Font.NORMAL, 10, Color.BLACK));
        c.addElement(w);
        c.addElement(centered("______________________________", font(Font.NORMAL, 10, Color.BLACK)));
        c.addElement(centered(label, font(Font.BOLD, 10, Color.BLACK)));
        return c;
    }

    /** "Name: ______" with the value written on the line when there is one. */
    private static PdfPCell field(String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingBottom(12);
        Phrase p = new Phrase();
        p.add(new Chunk(label + " ", font(Font.NORMAL, 10, Color.BLACK)));
        String v = value == null || value.isBlank() ? "______________________________" : value;
        Chunk ch = new Chunk(v, font(Font.NORMAL, 10, Color.BLACK));
        if (value != null && !value.isBlank()) ch.setUnderline(0.5f, -2f);
        p.add(ch);
        c.setPhrase(p);
        return c;
    }

    private static Paragraph centered(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static String tick(boolean on) {
        return on ? "[X]" : "[  ]";
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "     " + b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Font font(int style, float size, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, color);
    }
}
