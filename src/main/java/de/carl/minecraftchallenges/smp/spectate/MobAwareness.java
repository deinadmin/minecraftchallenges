package de.carl.minecraftchallenges.smp.spectate;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Paper 26.3: mobs notice players through {@code TargetingConditions}: looking at them, following food, fleeing,
 * begging, villagers' sensors. Hiding a player from other clients does not change that, so everyone would see
 * mobs stare at or follow an empty spot. Every such condition gets a selector that rejects spectating admins.
 */
final class MobAwareness {
    /** Classes whose static conditions are shared by many mobs (sensors, tempting, dolphins …). */
    private static final List<String> STATIC_HOLDERS = List.of(
            "net.minecraft.world.entity.ai.targeting.TargetingConditions",
            "net.minecraft.world.entity.ai.sensing.Sensor",
            "net.minecraft.world.entity.ai.sensing.TemptingSensor",
            "net.minecraft.world.entity.ai.goal.TemptGoal",
            "net.minecraft.world.entity.ai.goal.OfferFlowerGoal",
            "net.minecraft.world.entity.ai.goal.BreedGoal",
            "net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal",
            "net.minecraft.world.entity.animal.dolphin.Dolphin",
            "net.minecraft.world.entity.animal.panda.Panda",
            "net.minecraft.world.entity.animal.equine.AbstractHorse",
            "net.minecraft.world.entity.animal.fish.Pufferfish",
            "net.minecraft.world.entity.animal.goat.GoatAi",
            "net.minecraft.world.entity.animal.nautilus.NautilusAi",
            "net.minecraft.world.entity.ambient.Bat",
            "net.minecraft.world.entity.monster.Phantom");

    private final Class<?> conditionsClass = Class.forName("net.minecraft.world.entity.ai.targeting.TargetingConditions");
    private final Class<?> selectorClass = Class.forName("net.minecraft.world.entity.ai.targeting.TargetingConditions$Selector");
    private final Field selectorField = accessible(conditionsClass.getDeclaredField("selector"));
    private final Method selectorTest = selectorClass.getMethod("test",
            Class.forName("net.minecraft.world.entity.LivingEntity"), Class.forName("net.minecraft.server.level.ServerLevel"));
    private final Class<?> goalClass = Class.forName("net.minecraft.world.entity.ai.goal.Goal");
    private final Field goalSelector = accessible(Class.forName("net.minecraft.world.entity.Mob").getDeclaredField("goalSelector"));
    private final Field targetSelector = accessible(Class.forName("net.minecraft.world.entity.Mob").getDeclaredField("targetSelector"));
    private final Method availableGoals = Class.forName("net.minecraft.world.entity.ai.goal.GoalSelector").getMethod("getAvailableGoals");
    private final Method wrappedGoal = Class.forName("net.minecraft.world.entity.ai.goal.WrappedGoal").getMethod("getGoal");
    private final Class<?> lookAtGoal = Class.forName("net.minecraft.world.entity.ai.goal.LookAtPlayerGoal");
    private final Field lookAt = accessible(lookAtGoal.getDeclaredField("lookAt"));
    private final Field lookTime = accessible(lookAtGoal.getDeclaredField("lookTime"));
    private final Field lookAtType = accessible(lookAtGoal.getDeclaredField("lookAtType"));
    private final Field goalPriority = accessible(Class.forName("net.minecraft.world.entity.ai.goal.WrappedGoal")
            .getDeclaredField("priority"));
    private final Class<?> playerClass = Class.forName("net.minecraft.world.entity.player.Player");
    private final Method entityHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity").getMethod("getHandle");
    private final Method getBrain = Class.forName("net.minecraft.world.entity.LivingEntity").getMethod("getBrain");
    private final Method eraseMemory = Class.forName("net.minecraft.world.entity.ai.Brain")
            .getMethod("eraseMemory", Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType"));
    private final Object lookTargetMemory = Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType")
            .getField("LOOK_TARGET").get(null);

    /** Server-side handles of the spectating admins; read on every check, so it is an identity set. */
    private final Set<Object> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> patched = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Class<?>, List<Field>> conditionFields = new HashMap<>();
    private boolean staticsPatched;

    MobAwareness() throws ReflectiveOperationException { }

    void hide(Player player) throws ReflectiveOperationException {
        hideHandle(entityHandle.invoke(player));
    }

    /** Also used by the smoke test with a mob standing in for a player. */
    void hideHandle(Object handle) throws ReflectiveOperationException {
        patchStatics();
        hidden.add(handle);
    }

    void revealHandle(Object handle) {
        hidden.remove(handle);
    }

    void reveal(Player player) throws ReflectiveOperationException {
        hidden.remove(entityHandle.invoke(player));
    }

    boolean active() {
        return staticsPatched;
    }

    /** Gives every targeting condition of this mob's goals the filter; goals added later are caught on the next call. */
    void patch(Mob mob) throws ReflectiveOperationException {
        Object handle = entityHandle.invoke(mob);
        for (Object goal : goals(handle)) {
            for (Field field : conditionFields(goal.getClass())) patchConditions(field.get(goal));
        }
    }

    /** Brain-driven mobs (villagers, piglins …) have no goals; their own looking leaves room for ours. */
    static final int BRAIN_LOOK_PRIORITY = 8;

    /**
     * The priority of the mob's "look at player" goal, so looking at the double ranks alike; null if the mob never
     * looks at players.
     */
    Integer playerLookPriority(Mob mob) throws ReflectiveOperationException {
        Object goals = availableGoals.invoke(goalSelector.get(entityHandle.invoke(mob)));
        Set<?> wrapped = (Set<?>) goals;
        if (wrapped.isEmpty()) return BRAIN_LOOK_PRIORITY;
        for (Object entry : wrapped) {
            Object goal = wrappedGoal.invoke(entry);
            if (lookAtGoal.isInstance(goal) && playerClass.isAssignableFrom((Class<?>) lookAtType.get(goal))) {
                return goalPriority.getInt(entry);
            }
        }
        return null;
    }

    /** Mobs that already look at the admin let go right away instead of following the empty spot. */
    void forget(Player player, Collection<Entity> nearby) throws ReflectiveOperationException {
        Object admin = entityHandle.invoke(player);
        for (Entity entity : nearby) {
            if (!(entity instanceof Mob mob)) continue;
            Object handle = entityHandle.invoke(mob);
            for (Object goal : goals(handle)) {
                if (lookAtGoal.isInstance(goal) && lookAt.get(goal) == admin) lookTime.setInt(goal, 0);
            }
            eraseMemory.invoke(getBrain.invoke(handle), lookTargetMemory);
        }
    }

    private List<Object> goals(Object mobHandle) throws ReflectiveOperationException {
        List<Object> goals = new ArrayList<>();
        for (Field selector : List.of(goalSelector, targetSelector)) {
            Object goalSelectorObject = selector.get(mobHandle);
            if (goalSelectorObject == null) continue;
            for (Object wrapped : (Set<?>) availableGoals.invoke(goalSelectorObject)) goals.add(wrappedGoal.invoke(wrapped));
        }
        return goals;
    }

    private void patchStatics() throws ReflectiveOperationException {
        if (staticsPatched) return;
        for (String name : STATIC_HOLDERS) {
            Class<?> holder;
            try {
                holder = Class.forName(name);
            } catch (ClassNotFoundException missing) {
                continue;
            }
            for (Field field : holder.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == conditionsClass) {
                    patchConditions(accessible(field).get(null));
                }
            }
        }
        staticsPatched = true;
    }

    private List<Field> conditionFields(Class<?> type) {
        return conditionFields.computeIfAbsent(type, ignored -> {
            List<Field> fields = new ArrayList<>();
            for (Class<?> current = type; current != null && current != goalClass; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() == conditionsClass) fields.add(accessible(field));
                }
            }
            return fields;
        });
    }

    private void patchConditions(Object conditions) throws ReflectiveOperationException {
        if (conditions == null || !patched.add(conditions)) return;
        Object previous = selectorField.get(conditions);
        selectorField.set(conditions, selector(previous));
    }

    private Object selector(Object previous) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "test" -> {
                if (!hidden.isEmpty() && hidden.contains(args[0])) yield false;
                if (previous == null) yield true;
                try {
                    yield selectorTest.invoke(previous, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "SpectatorFilter[" + previous + "]";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return Proxy.newProxyInstance(selectorClass.getClassLoader(), new Class<?>[] {selectorClass}, handler);
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }
}
