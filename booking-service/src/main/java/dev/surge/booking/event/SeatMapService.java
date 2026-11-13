package dev.surge.booking.event;

import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.inventory.EventInventory;
import dev.surge.booking.inventory.InventoryCache;
import dev.surge.booking.inventory.SeatInfo;
import dev.surge.booking.pricing.TierPrices;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SeatMapService {

    private final InventoryCache inventory;
    private final SeatRepository seats;
    private final StringRedisTemplate redis;
    private final TierPrices prices;

    public SeatMapService(InventoryCache inventory, SeatRepository seats, StringRedisTemplate redis, TierPrices prices) {
        this.inventory = inventory;
        this.seats = seats;
        this.redis = redis;
        this.prices = prices;
    }

    public List<EventSummary> listEvents() {
        List<EventSummary> result = new ArrayList<>();
        for (EventInventory inv : inventory.all()) {
            int sold = (int) seats.countByEventIdAndStatus(inv.eventId(), SeatStatus.SOLD);
            result.add(new EventSummary(inv.eventId(), inv.title(), inv.venueName(), inv.zoneId().getId(),
                    inv.saleOpensAt(), inv.totalSeats(), sold));
        }
        return result;
    }

    public EventDetail detail(long eventId) {
        EventInventory inv = inventory.get(eventId);
        Set<Long> sold = new HashSet<>();
        for (Seat s : seats.findByEventIdOrderByRowLabelAscNumberAsc(eventId)) {
            if (s.getStatus() == SeatStatus.SOLD) {
                sold.add(s.getId());
            }
        }
        List<SeatInfo> infos = new ArrayList<>(inv.seats().values());
        List<String> keys = infos.stream().map(i -> RedisKeys.seat(eventId, i.seatId())).toList();
        List<String> held = keys.isEmpty() ? List.of() : redis.opsForValue().multiGet(keys);
        List<SeatView> views = new ArrayList<>(infos.size());
        for (int i = 0; i < infos.size(); i++) {
            SeatInfo info = infos.get(i);
            SeatState state = sold.contains(info.seatId()) ? SeatState.SOLD
                    : (held != null && held.get(i) != null) ? SeatState.HELD : SeatState.AVAILABLE;
            views.add(new SeatView(info.seatId(), info.rowLabel(), info.number(), info.tier(),
                    prices.base(info.tier()), state));
        }
        return new EventDetail(inv.eventId(), inv.title(), inv.venueName(), inv.zoneId().getId(),
                inv.saleOpensAt(), views);
    }
}
