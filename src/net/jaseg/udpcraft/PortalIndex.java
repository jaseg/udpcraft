package net.jaseg.udpcraft;

import org.bukkit.Location;

public interface PortalIndex {
	boolean tryRegisterPortal(Location location);
	void registerPortal(Portal portal);
	void savePortals();
	public void unregisterPortal(Portal portal);
	public Portal lookupPortal(String name);
	public Portal lookupPortalOrDie(String name) throws IllegalArgumentException;
}
