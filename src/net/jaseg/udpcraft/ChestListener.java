package net.jaseg.udpcraft;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

public class ChestListener implements Listener {
	private UDPCraftPlugin uplug;
	
	public ChestListener(UDPCraftPlugin uplug) {
		this.uplug = uplug;
	}
	
	@EventHandler
	public void onInventoryClose(InventoryCloseEvent evt) {
		uplug.tryRegisterPortal(evt.getInventory().getLocation());
	}
}
