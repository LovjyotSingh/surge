package dev.surge.booking.event;

import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.stream.SeatStreamRegistry;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/bookings/events")
public class EventController {

    private final SeatMapService service;
    private final InventoryCache inventory;
    private final SeatStreamRegistry streams;

    public EventController(SeatMapService service, InventoryCache inventory, SeatStreamRegistry streams) {
        this.service = service;
        this.inventory = inventory;
        this.streams = streams;
    }

    @GetMapping
    public List<EventSummary> list() {
        return service.listEvents();
    }

    @GetMapping("/{eventId}")
    public EventDetail detail(@PathVariable long eventId) {
        return service.detail(eventId);
    }

    @GetMapping(value = "/{eventId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long eventId) {
        inventory.get(eventId); // 404 for an unknown event
        return streams.subscribe(eventId);
    }
}
