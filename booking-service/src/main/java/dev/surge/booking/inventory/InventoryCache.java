package dev.surge.booking.inventory;

import dev.surge.booking.error.EventNotFoundException;
import dev.surge.booking.event.EventRef;
import dev.surge.booking.event.EventRefRepository;
import dev.surge.booking.event.Seat;
import dev.surge.booking.event.SeatRepository;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory copy of event and seat facts that never change while selling (seat status is NOT cached). */
@Component
public class InventoryCache {

    private final EventRefRepository events;
    private final SeatRepository seats;
    private final ConcurrentHashMap<Long, EventInventory> cache = new ConcurrentHashMap<>();

    public InventoryCache(EventRefRepository events, SeatRepository seats) {
        this.events = events;
        this.seats = seats;
    }

    public EventInventory get(long eventId) {
        EventInventory cached = cache.get(eventId);
        if (cached != null) {
            return cached;
        }
        EventRef ref = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
        return cache.computeIfAbsent(eventId, id -> load(ref));
    }

    public List<EventInventory> all() {
        List<EventInventory> result = new ArrayList<>();
        for (EventRef ref : events.findAll()) {
            result.add(cache.computeIfAbsent(ref.getId(), id -> load(ref)));
        }
        result.sort(Comparator.comparingLong(EventInventory::eventId));
        return result;
    }

    public void evict(long eventId) {
        cache.remove(eventId);
    }

    private EventInventory load(EventRef ref) {
        Map<Long, SeatInfo> map = new LinkedHashMap<>();
        for (Seat s : seats.findByEventIdOrderByRowLabelAscNumberAsc(ref.getId())) {
            map.put(s.getId(), new SeatInfo(s.getId(), s.getSection(), s.getRowLabel(), s.getNumber(), s.getTier()));
        }
        return new EventInventory(ref.getId(), ref.getTitle(), ref.getVenueName(), ref.getSaleOpensAt(),
                ZoneId.of(ref.getZoneId()), map);
    }
}
