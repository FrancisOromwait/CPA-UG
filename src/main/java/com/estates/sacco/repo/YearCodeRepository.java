package com.estates.sacco.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads and writes the three year-code tables: FixedData (settings, one row), MonthData (the periods of a year) and
 * interval (the periods of every year generated so far). Each method is one SQL statement, so the repository only
 * talks to the database and makes no decisions; the rules are in YearCodeService.
 *
 * These tables have no entity classes (they were created by hand, with their own column names), so the SQL is written
 * out here instead of being generated from method names as in the Spring Data repositories.
 */
@Repository
@RequiredArgsConstructor
public class YearCodeRepository {
    private final JdbcTemplate jdbc;

    /** One period of MonthData. */
    public record MonthRow(long id, String code, String name, LocalDate start, LocalDate end) {}

    /** The year generated so far (FixedData.CurrentYear), or null when FixedData has no row or no year in it. */
    public Integer currentYear() {
        List<Integer> years = jdbc.query("select CurrentYear from FixedData order by IDNo",
                (rs, i) -> { int v = rs.getInt(1); return rs.wasNull() ? null : v; });
        return years.isEmpty() ? null : years.get(0);
    }

    /** Adds the given year: this is step 4 of Run New Year. */
    public void setCurrentYear(int year) {
        jdbc.update("update FixedData set CurrentYear = ?", year);
    }

    public int countMonthData() {
        Integer n = jdbc.queryForObject("select count(*) from MonthData", Integer.class);
        return n == null ? 0 : n;
    }

    /** All the periods of MonthData, in date order. */
    public List<MonthRow> monthRows() {
        return jdbc.query("select id, FINTCODE, FINTNAME, FSTART, FEND from MonthData order by FSTART, id",
                (rs, i) -> new MonthRow(rs.getLong("id"), rs.getString("FINTCODE"), rs.getString("FINTNAME"),
                        rs.getObject("FSTART", LocalDate.class), rs.getObject("FEND", LocalDate.class)));
    }

    /** Moves one MonthData period to new dates: step 2 of Run New Year. */
    public void updateMonthRow(long id, LocalDate start, LocalDate end, long daysInMonth, int firstDay, int lastDay) {
        jdbc.update("update MonthData set FSTART = ?, FEND = ?, DaysInMonth = ?, FirstDay = ?, LastDay = ? where id = ?",
                start, end, daysInMonth, firstDay, lastDay, id);
    }

    /** How many periods the interval table already has for a year (YRCODE). */
    public int countIntervals(String yearCode) {
        Integer n = jdbc.queryForObject("select count(*) from `interval` where YRCODE = ?", Integer.class, yearCode);
        return n == null ? 0 : n;
    }

    /** Adds one period to interval, open (status A) with all rates 0: step 3 of Run New Year. */
    public void insertInterval(String fintCode, String yearCode, String fintName, LocalDate start, LocalDate end) {
        jdbc.update("insert into `interval` (FINTCODE, YRCODE, FINTNAME, FSTART, FEND, FSTATUS) values (?, ?, ?, ?, ?, 'A')",
                fintCode, yearCode, fintName, start, end);
    }
}
