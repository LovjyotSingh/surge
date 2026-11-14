package dev.surge.catalog.event;

import dev.surge.catalog.error.EventNotFoundException;
import dev.surge.catalog.error.InvalidParameterException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/events")
public class EventController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final EventRepository events;

    public EventController(EventRepository events) {
        this.events = events;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public EventPage list(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (page < 0) {
            throw new InvalidParameterException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidParameterException("size must be between 1 and " + MAX_SIZE);
        }
        Page<Event> result = events.findAllBy(PageRequest.of(page, size, Sort.by("startsAt", "id")));
        return new EventPage(result.map(EventView::of).getContent(), page, size, result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public EventView get(@PathVariable long id) {
        return events.findWithVenueById(id).map(EventView::of)
                .orElseThrow(() -> new EventNotFoundException(id));
    }
}
