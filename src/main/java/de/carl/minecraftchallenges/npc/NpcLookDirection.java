package de.carl.minecraftchallenges.npc;

/** Minecraft's packet angles, computed independently for each viewer's eye position. */
public record NpcLookDirection(byte yaw, byte pitch) {
    public static NpcLookDirection towards(double dx, double dy, double dz, float fallbackYaw) {
        double horizontal = Math.hypot(dx, dz);
        double yaw = horizontal < 1.0e-6 ? fallbackYaw : Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = horizontal < 1.0e-6 && Math.abs(dy) < 1.0e-6
                ? 0.0 : -Math.toDegrees(Math.atan2(dy, horizontal));
        return new NpcLookDirection(pack(yaw), pack(pitch));
    }

    private static byte pack(double degrees) {
        return (byte) Math.floor(degrees * 256.0 / 360.0);
    }
}
