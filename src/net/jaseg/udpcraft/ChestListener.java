package net.jaseg.udpcraft;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;
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
	private PortalIndex index;
	private Logger logger;
	
	public ChestListener(Logger logger, PortalIndex index) {
		this.logger = logger;
		this.index = index;
	}
	
	@EventHandler
	public void onInventoryClose(InventoryCloseEvent evt) {
		index.tryRegisterPortal(evt.getInventory().getLocation());
	}
	
	@EventHandler
	public void onInventoryMoveItem(InventoryMoveItemEvent evt) {
		Inventory dest = evt.getDestination();
		if (dest.getType() == InventoryType.CHEST) {
			Chest chest = (Chest)dest.getHolder();
			if (chest.hasMetadata(Portal.METADATA_KEY)) {
				List<MetadataValue> values = chest.getMetadata(Portal.METADATA_KEY);
				if (values.size() < 1) {
					logger.log(Level.WARNING, "Invalid metadata on portal chest", chest);
					return;
				}
				Portal portal = index.lookupPortal(values.get(0).asString());
				if (portal == null) {
					logger.log(Level.WARNING, "Invalid metadata with unknown name on portal chest", chest);
					return;
				}
				
				ItemStack stack = evt.getItem();
				ItemStack contents[] = dest.getContents();
				int origAmount = stack.getAmount();
				int toDelete = origAmount;
				Material sm = stack.getType();
				for (int i=0; i<contents.length-9; i++) {
					if (contents[i] != null && contents[i].getType() == sm) {
						int amount = contents[i].getAmount();
						if (amount <= toDelete) {
							toDelete -= amount;
							contents[i] = null;
						} else {
							contents[i].setAmount(amount-toDelete);
							toDelete = 0;
							break;
						}
					}
				}
				stack.setAmount(origAmount - toDelete);
				dest.setContents(contents);
				if (origAmount != toDelete)
					portal.emitItem(stack);
				evt.setItem(stack);
			}
		}
	}
}
