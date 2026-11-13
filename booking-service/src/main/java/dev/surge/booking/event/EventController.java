package dev.surge.booking.event;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/events")
public class EventController {

    private final SeatMapService service;

    public EventController(SeatMapService service) {
        this.service = service;
    }

    @GetMapping
    public List<EventSummary> list() {
        return service.listEvents();
    }

    @GetMapping("/{eventId}")
    public EventDetail detail(@PathVariable long eventId) {
        return service.detail(eventId);
    }
}
