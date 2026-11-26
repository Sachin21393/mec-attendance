package com.example.attendance.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkCalendarRepository extends JpaRepository<WorkCalendarDay, UUID> {
    Optional<WorkCalendarDay> findByCalendarDate(LocalDate calendarDate);

    List<WorkCalendarDay> findByCalendarDateBetweenOrderByCalendarDateAsc(LocalDate start, LocalDate end);

    List<WorkCalendarDay> findByCalendarDateGreaterThanEqual(LocalDate startDate);
}
