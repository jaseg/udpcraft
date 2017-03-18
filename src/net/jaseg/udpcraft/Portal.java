package net.jaseg.udpcraft;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.metadata.FixedMetadataValue;

public class Portal {
	public static final String METADATA_KEY = "net.jaseg.udpcraft.isportal";
	
	private String cachedName;
	private Location location;
	private ItemListener listener;
	private Set<ItemMessage> outgoing = new HashSet<ItemMessage>();
	private UDPCraftPlugin plugin;
	private Chest cachedBlockState;
	
	private Portal(UDPCraftPlugin plugin, Location loc, ItemListener listener) {
		this.plugin = plugin;
		this.location = loc;
		this.listener = listener;
	}
	
	public static Portal fromLocation(UDPCraftPlugin plugin, Location loc, ItemListener listener) {
		Portal portal = new Portal(plugin, loc, listener);
		if (!portal.validateLocation())
			return null;
		return portal;
	}
	
	public String getName() {
		return cachedName;
	}
	
	public Location getLocation() {
		return location;
	}

	public void emitItem(ItemStack stack) {
		plugin.getLogger().log(Level.INFO, "Emitting message from "+cachedName);
		ItemMessage msg = new ItemMessage(plugin, cachedName, stack);
		outgoing.add(msg);
		synchronized(this) {
			if (listener != null) {
				listener.emitMessage(this, msg);
			}
		}
	}
	
	public void ackMessage(ItemMessage msg) {
		outgoing.remove(msg);
	}
	
	public void receiveMessage(ItemMessage msg) {
		plugin.getLogger().log(Level.INFO, "Received message at", cachedName);
		if (!validateLocation())
			plugin.unregisterPortal(this);
		Inventory inventory = cachedBlockState.getBlockInventory();
		
		HashMap<Integer, ItemStack> excess = inventory.addItem(msg.getStack());
		
		if (!excess.isEmpty()) {
			plugin.getLogger().log(Level.INFO, "Excess content for target chest");
			emitItem(excess.get(0));
		}
	}
	
	private boolean validateLocation() {
		plugin.getLogger().log(Level.INFO, "Validating portal location");
		BlockState state = location.getBlock().getState();
		if (!(state instanceof Chest)) {
			plugin.getLogger().log(Level.INFO, "Not instanceof chest");
			return false;
		}
		
		Inventory inventory = ((Chest)state).getBlockInventory();
		
		ItemStack stacks[] = inventory.getContents();
		
		Material materials[] = {
				Material.DIAMOND_BLOCK,
				Material.DIAMOND_BLOCK,
				Material.REDSTONE_BLOCK,
				Material.ENDER_PEARL,
				Material.WRITTEN_BOOK,
				Material.ENDER_PEARL,
				Material.REDSTONE_BLOCK,
				Material.DIAMOND_BLOCK,
				Material.DIAMOND_BLOCK
		};
		
		int last  = stacks.length-1,
			first = stacks.length-materials.length;
		for (int i=first; i<=last; i++) {
			if (stacks[i] == null
					|| stacks[i].getType() != materials[i-first]
					|| stacks[i].getAmount() != 1) {
				plugin.getLogger().log(Level.INFO, "Error with template index "+Integer.toString(i));
				return false;
			}
		}
		
		BookMeta meta = (BookMeta)stacks[first+4].getItemMeta();
		if (meta.getPageCount() == 0) {
			plugin.getLogger().log(Level.INFO, "Page count is zero");
			return false;
		}

		if (!meta.getPage(1).startsWith("#!/udpportal")) {
			plugin.getLogger().log(Level.INFO, "Shibboleth does not match: \""+meta.getPage(1)+"\"");
			return false;
		}
		
		String name = meta.getTitle();
		if (!Pattern.matches("[0-9a-zA-Z_/]{3,16}", name)) {
			plugin.getLogger().log(Level.INFO, "Name does not match");
			return false;
		}

		plugin.getLogger().log(Level.INFO, "Portal accepted. Name: "+name);
		state.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, name));
		cachedBlockState = (Chest)state;
		cachedName = name;
		return true;
	}
}
