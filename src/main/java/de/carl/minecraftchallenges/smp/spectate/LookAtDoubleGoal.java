package de.carl.minecraftchallenges.smp.spectate;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Vanilla's "look at player" for the spectator's double: mobs only look at real players, and the double is a
 * mannequin. Same chance, distance and duration as vanilla, and only while the double is the nearest "player".
 */
final class LookAtDoubleGoal implements Goal<Mob> {
    static final float DISTANCE = 8.0f;
    private static final float PROBABILITY = 0.02f;

    private final GoalKey<Mob> key;
    private final Mob mob;
    private final Function<Mob, Mannequin> nearestDouble;
    private final Predicate<Player> hidden;
    private Mannequin looking;
    private int lookTime;

    LookAtDoubleGoal(GoalKey<Mob> key, Mob mob, Function<Mob, Mannequin> nearestDouble, Predicate<Player> hidden) {
        this.key = key;
        this.mob = mob;
        this.nearestDouble = nearestDouble;
        this.hidden = hidden;
    }

    @Override
    public boolean shouldActivate() {
        if (ThreadLocalRandom.current().nextFloat() >= PROBABILITY || mob.getTarget() != null) return false;
        Mannequin body = nearestDouble.apply(mob);
        if (body == null) return false;
        double distance = body.getLocation().distanceSquared(mob.getEyeLocation());
        // A real player who is nearer gets vanilla's attention instead.
        for (Player player : mob.getWorld().getPlayers()) {
            if (hidden.test(player) || player.getGameMode() == GameMode.SPECTATOR) continue;
            if (player.getLocation().distanceSquared(mob.getEyeLocation()) < distance) return false;
        }
        looking = body;
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        if (looking == null || !looking.isValid() || lookTime <= 0) return false;
        Location eyes = mob.getEyeLocation();
        return looking.getWorld().equals(eyes.getWorld())
                && looking.getLocation().distanceSquared(eyes) <= DISTANCE * DISTANCE;
    }

    @Override
    public void start() {
        lookTime = 40 + ThreadLocalRandom.current().nextInt(40);
    }

    @Override
    public void stop() {
        looking = null;
    }

    @Override
    public void tick() {
        if (looking == null || !looking.isValid()) return;
        mob.lookAt(looking);
        lookTime--;
    }

    @Override
    public @NotNull GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public @NotNull EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.LOOK);
    }
}
