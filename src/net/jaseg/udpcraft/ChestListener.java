package net.jaseg.udpcraft;

import java.util.List;
import java.util.logging.Level;

import org.bukkit.block.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;

public class ChestListener implements Listener {
	private UDPCraftPlugin uplug;
	
	public ChestListener(UDPCraftPlugin uplug) {
		this.uplug = uplug;
	}
	
	@EventHandler
	public void onInventoryClose(InventoryCloseEvent evt) {
		uplug.tryRegisterPortal(evt.getInventory().getLocation());
	}
	
	@EventHandler
	public void onInventoryMoveItem(InventoryMoveItemEvent evt) {
		Inventory dest = evt.getDestination();
		if (dest.getType() == InventoryType.CHEST) {
			Chest chest = (Chest)dest.getHolder();
			if (chest.hasMetadata(Portal.METADATA_KEY)) {
				List<MetadataValue> values = chest.getMetadata(Portal.METADATA_KEY);
				if (values.size() < 1) {
					uplug.getLogger().log(Level.WARNING, "Invalid metadata on portal chest", chest);
					return;
				}
				Portal portal = uplug.lookupPortal(values.get(0).asString());
				if (portal == null) {
					uplug.getLogger().log(Level.WARNING, "Invalid metadata with unknown name on portal chest", chest);
					return;
				}
				ItemStack stack = evt.getItem();
				portal.emitItem(stack.clone());
				stack.setAmount(0);
			}
		}
	}
}
