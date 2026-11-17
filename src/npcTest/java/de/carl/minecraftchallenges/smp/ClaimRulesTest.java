package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.smp.claim.Claim;
import de.carl.minecraftchallenges.smp.claim.ClaimRules;

import java.util.UUID;

/** Plot sizes, prices and the geometry that keeps plots apart. Needs no server. */
public final class ClaimRulesTest {
    private static int checks;

    public static void main(String[] args) {
        ClaimRules rules = ClaimRules.DEFAULT;
        expect(rules.maxRadius() == 80, "Seven prices give radius 20 to 80");
        expect(rules.level(20) == 1 && rules.level(80) == 7 && rules.levels() == 7, "Levels count from 1 to 7");
        expect(rules.buyPrice(0) == 150 && rules.buyPrice(1) == 300 && rules.buyPrice(2) == 600,
                "Every further plot costs double");
        expect(rules.upgradePrice(20) == 400 && rules.upgradePrice(70) == 8000, "Upgrades follow the price list");
        expect(rules.upgradePrice(80) == -1, "The largest plot cannot grow");
        long total = rules.buyPrice(0);
        for (int radius = 20; radius < 80; radius += 10) total += rules.upgradePrice(radius);
        expect(total == 20_550, "A full-size plot costs 20,550 coins in total");
        expect(rules.refund(550) == 275, "Giving up a plot returns half");

        Claim claim = new Claim(UUID.randomUUID(), UUID.randomUUID(), 100, -50, 20, 150);
        expect(claim.side() == 41, "Radius 20 is 41 blocks wide");
        expect(claim.contains(120, -30) && claim.contains(80, -70), "The corners belong to the plot");
        expect(!claim.contains(121, -50) && !claim.contains(100, -71), "One block further is outside");

        expect(ClaimRules.gap(0, 0, 20, 41, 0, 20) == 0, "Plots side by side touch without overlapping");
        expect(ClaimRules.gap(0, 0, 20, 40, 0, 20) == -1 && ClaimRules.overlaps(0, 0, 20, 40, 0, 20),
                "Sharing one column of blocks is an overlap");
        expect(ClaimRules.gap(0, 0, 20, 56, 5, 20) == 15, "Fifteen free blocks between two plots");
        expect(ClaimRules.gap(0, 0, 20, 41, 41, 20) == 0, "Diagonal neighbours touch at the corner");
        expect(!ClaimRules.overlaps(0, 0, 20, 30, 100, 20), "Far apart along one axis is enough");
        expect(ClaimRules.overlaps(0, 0, 80, 10, 10, 20), "A plot inside another overlaps");

        expect(ClaimRules.distanceToSquare(0, 0, 20, 10, -5) == 0, "A point inside has distance 0");
        expect(ClaimRules.distanceToSquare(0, 0, 20, 50, 0) == 30, "Straight distance to the edge");
        expect(Math.abs(ClaimRules.distanceToSquare(0, 0, 20, 23, 24) - 5) < 1e-9, "Diagonal distance to the corner");
        // Spawn radius 150 plus 30 blocks buffer.
        expect(rules.touchesSpawn(200, 0, 20, 0, 0, 150), "180 blocks from spawn is still too close");
        expect(!rules.touchesSpawn(201, 0, 20, 0, 0, 150), "One block further is fine");
        expect(!rules.touchesSpawn(150, 150, 20, 0, 0, 150), "The round spawn leaves the diagonal free");
        System.out.println(checks + " claim rule checks passed.");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
