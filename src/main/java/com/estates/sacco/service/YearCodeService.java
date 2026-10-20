package com.estates.sacco.service;

import com.estates.sacco.repo.YearCodeRepository;
import com.estates.sacco.repo.YearCodeRepository.MonthRow;
import com.estates.sacco.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Generates the period codes of a new year. The steps, all in one transaction (all or nothing):
 * <ol>
 *   <li>read Current Year from FixedData: the year generated so far; the year to generate is that plus one;</li>
 *   <li>move FSTART and FEND of every MonthData row forward one year (a date that was a month end stays a month end,
 *       so 28 Feb becomes 29 Feb in a leap year);</li>
 *   <li>insert the MonthData rows into interval, with the new year as YRCODE;</li>
 *   <li>add one to Current Year in FixedData.</li>
 * </ol>
 * The rules are here; the database statements are in {@link YearCodeRepository}.
 */
@Service
@RequiredArgsConstructor
public class YearCodeService {
    private final YearCodeRepository repository;
    private final AuditService audit;

    public record Status(Integer currentYear, Integer nextYear, String problem) {}

    public record Result(int year, int periods) {}

    /** What the form shows: the year generated so far, the year that would be generated, and anything that stops it. */
    @Transactional(readOnly = true)
    public Status status() {
        try {
            Integer current = repository.currentYear();
            if (current == null) return new Status(null, null, "FixedData has no Current Year yet. Add it first.");
            int next = current + 1;
            if (repository.countMonthData() == 0) return new Status(current, next, "MonthData is empty: there are no periods to carry forward.");
            if (repository.countIntervals(String.valueOf(next)) > 0) return new Status(current, next, "The year " + next + " already has periods in interval.");
            return new Status(current, next, null);
        } catch (DataAccessException e) {
            return new Status(null, null, "A table needed for this (FixedData, MonthData or interval) is missing or cannot be read.");
        }
    }

    @Transactional
    public Result generate() {
        Status st = status();
        if (st.problem() != null) throw new ApiException(HttpStatus.CONFLICT, st.problem());
        int target = st.nextYear();

        List<MonthRow> rows = repository.monthRows();
        for (MonthRow r : rows) {
            // step 2: MonthData moves forward one year
            LocalDate start = plusYear(r.start()), end = plusYear(r.end());
            if (start.getYear() != target || end.getYear() != target) {
                throw new ApiException(HttpStatus.CONFLICT, "MonthData period " + r.code() + " would move to " + start + " - " + end
                        + ", which is not in " + target + ". MonthData has to hold the dates of " + st.currentYear() + " before the year is generated.");
            }
            repository.updateMonthRow(r.id(), start, end, ChronoUnit.DAYS.between(start, end) + 1, start.getDayOfMonth(), end.getDayOfMonth());
            // step 3: the same period goes into interval for the new year
            repository.insertInterval(r.code(), String.valueOf(target), r.name(), start, end);
        }

        // step 4: the year generated so far moves on
        repository.setCurrentYear(target);
        audit.log(null, "EDIT", "Year codes", (long) target, "Generated the year " + target + ": " + rows.size() + " periods");
        return new Result(target, rows.size());
    }

    /** One year later; a month end stays a month end. */
    static LocalDate plusYear(LocalDate d) {
        LocalDate n = d.plusYears(1);
        return d.equals(d.with(TemporalAdjusters.lastDayOfMonth())) ? n.with(TemporalAdjusters.lastDayOfMonth()) : n;
    }
}
