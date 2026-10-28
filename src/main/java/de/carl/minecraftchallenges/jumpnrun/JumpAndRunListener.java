package de.carl.minecraftchallenges.jumpnrun;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class JumpAndRunListener implements Listener {
    private final JumpAndRunService service;

    public JumpAndRunListener(JumpAndRunService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPressurePlate(PlayerInteractEvent event) {
        if ((event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                && service.isAbortItem(event.getItem())) {
            event.setCancelled(true);
            service.abort(event.getPlayer());
            return;
        }
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) return;
        if (service.isTrigger(event.getClickedBlock())) {
            service.start(event.getPlayer(), event.getClickedBlock());
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ())) return;
        boolean enteredNewBlock = event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ();
        if (enteredNewBlock && service.tryStartFromPressurePlate(event.getPlayer())) return;
        service.handleMove(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clear(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        service.clear(event.getPlayer());
    }
}
