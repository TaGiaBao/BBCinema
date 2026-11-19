package com.bbcinema.repository;

import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.enums.DayType;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicketPriceRepository extends JpaRepository<TicketPrice, Long> {

    Optional<TicketPrice> findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
            SeatType seatType, ScreenFormat format, DayType dayType, TimeSlot timeSlot);

    List<TicketPrice> findAllByOrderByFormatAscDayTypeAscTimeSlotAscSeatTypeAsc();
}
