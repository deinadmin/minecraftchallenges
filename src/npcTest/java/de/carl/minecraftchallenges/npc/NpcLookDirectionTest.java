package de.carl.minecraftchallenges.npc;

public final class NpcLookDirectionTest {
    private static int checks;
    public static void main(String[] args) {
        expect(0, 0, NpcLookDirection.towards(0, 0, 5, 0), "South");
        expect(-64, 0, NpcLookDirection.towards(5, 0, 0, 0), "East");
        expect(64, 0, NpcLookDirection.towards(-5, 0, 0, 0), "West");
        expect(-128, 0, NpcLookDirection.towards(0, 0, -5, 0), "North");
        expect(-32, 0, NpcLookDirection.towards(5, 0, 5, 0), "Diagonal");
        expect(0, -32, NpcLookDirection.towards(0, 5, 5, 0), "Look upwards");
        expect(0, 32, NpcLookDirection.towards(0, -5, 5, 0), "Look downwards");
        expect(32, -64, NpcLookDirection.towards(0, 5, 0, 45), "Directly above preserves yaw");
        expect(32, 64, NpcLookDirection.towards(0, -5, 0, 45), "Directly below preserves yaw");
        expect(32, 0, NpcLookDirection.towards(0, 0, 0, 45), "Coincident eyes do not produce invalid angles");
        NpcLookDirection first = NpcLookDirection.towards(5, 0, 0, 0);
        NpcLookDirection second = NpcLookDirection.towards(-5, 0, 0, 0);
        expect(-64, 0, first, "Second viewer does not overwrite first viewer's rotation");
        expect(64, 0, second, "Opposite viewer receives independent rotation");
        System.out.println(checks + " NPC look direction checks passed.");
    }
    private static void expect(int yaw, int pitch, NpcLookDirection result, String message) {
        checks++;
        if (result.yaw() != yaw || result.pitch() != pitch) throw new AssertionError(message + ": " + result);
    }
}
