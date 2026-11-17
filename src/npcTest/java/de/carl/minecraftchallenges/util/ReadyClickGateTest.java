package de.carl.minecraftchallenges.util;

import org.bukkit.inventory.EquipmentSlot;
import java.util.UUID;

public final class ReadyClickGateTest {
    private static int checks;
    public static void main(String[] args) {
        ReadyClickGate gate = new ReadyClickGate();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        expect(gate.accept(first, EquipmentSlot.HAND, 100), "First block click makes the player ready");
        expect(!gate.accept(first, EquipmentSlot.HAND, 100), "Duplicate item-use in the same tick cannot undo readiness");
        expect(!gate.accept(first, EquipmentSlot.OFF_HAND, 100), "Off-hand block event cannot toggle readiness");
        expect(!gate.accept(first, EquipmentSlot.HAND, 101), "A delayed duplicate packet cannot undo readiness");
        expect(!gate.accept(first, EquipmentSlot.HAND, 103), "Duplicates do not extend the debounce window");
        expect(gate.accept(first, EquipmentSlot.HAND, 104), "A deliberate later click can cancel readiness");
        expect(gate.accept(second, EquipmentSlot.HAND, 104), "Players do not block each other's clicks");
        gate.forget(first);
        expect(gate.accept(first, EquipmentSlot.HAND, 104), "Disconnect clears click state");
        UUID offhand = UUID.randomUUID();
        expect(!gate.accept(offhand, EquipmentSlot.OFF_HAND, 10), "An off-hand-only event is ignored");
        expect(gate.accept(offhand, EquipmentSlot.HAND, 10), "An off-hand event does not consume a main-hand click");
        UUID wrap = UUID.randomUUID();
        gate.accept(wrap, EquipmentSlot.HAND, Integer.MAX_VALUE - 1);
        expect(!gate.accept(wrap, EquipmentSlot.HAND, Integer.MIN_VALUE), "Tick wraparound still rejects duplicate packets");
        expect(gate.accept(wrap, EquipmentSlot.HAND, Integer.MIN_VALUE + 2), "Tick wraparound still allows later clicks");
        System.out.println(checks + " ready click regression checks passed.");
    }
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
