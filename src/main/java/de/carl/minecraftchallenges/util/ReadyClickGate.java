package de.carl.minecraftchallenges.util;

import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A cancelled block use can be followed by a second item-use packet for the same click. */
public final class ReadyClickGate {
    private static final int DUPLICATE_WINDOW_TICKS = 4;
    private final Map<UUID, Integer> acceptedTicks = new HashMap<>();

    public boolean accept(UUID player, EquipmentSlot hand, int tick) {
        if (hand != EquipmentSlot.HAND) return false;
        Integer previous = acceptedTicks.get(player);
        if (previous != null && tick - previous < DUPLICATE_WINDOW_TICKS) return false;
        acceptedTicks.put(player, tick);
        return true;
    }

    public void forget(UUID player) { acceptedTicks.remove(player); }
}
