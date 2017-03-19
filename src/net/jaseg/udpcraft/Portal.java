package net.jaseg.udpcraft;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
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
	private static Timer staticTimer = new Timer();
	
	public static class InvalidLocationException extends Exception {
		private static final long serialVersionUID = 6670188551758192901L;

		public InvalidLocationException(String msg) {
			super("Not a portal: "+msg);
		}
	};
	
	public static Material MATERIAL_TEMPLATE[] = {
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
	
	public static enum Direction { IN, OUT };
	
	private String name;
	private Location location;
	private ItemListener listener;
	private UDPCraftPlugin plugin;
	private Logger logger;
	private int updateDelay;
	private int maxUpdateDelay;
	private Direction direction = Direction.OUT;
	private Map<String, String> config = new HashMap<String, String>();
	
	private long lastUpdate;
	private TimerTask updateTask;

	private Timer timer = staticTimer;
	private Clock clock = Clock.systemDefaultZone();
	
	public synchronized void queueUpdate() {
		if (direction != Direction.OUT)
			return;
			
		if (updateTask != null) {
			if (clock.millis() - lastUpdate > maxUpdateDelay-updateDelay) {
				return;
			} else {
				updateTask.cancel();
			}
		}

		updateTask = new TimerTask() {
			public void run() {
				updateTask = null;
				synchronized(Portal.this) {
					lastUpdate = clock.millis();
					try {
						Inventory inv = validateLocation().getBlockInventory();
						ItemStack stacks[] = inv.getContents();

						for (int i=0; i<stacks.length-MATERIAL_TEMPLATE.length; i++) {
							if (stacks[i] != null) {
								if (emitItem(stacks[i]))
									stacks[i] = null;
							}
						}
						
						inv.setContents(stacks);
					} catch(InvalidLocationException ex) {
						plugin.unregisterPortal(Portal.this);
					} 
				}
			}
		};
		timer.schedule(updateTask, updateDelay);
	}
	
	public Portal(Logger logger, UDPCraftPlugin plugin, Location loc, ItemListener listener) throws InvalidLocationException {
		this.plugin = plugin;
		this.logger = logger;
		this.location = loc;
		this.listener = listener;
		this.updateDelay = plugin.getConfig().getInt("updateDelayMillis");
		this.maxUpdateDelay = plugin.getConfig().getInt("maxUpdateDelayMillis");
		validateLocation();
	}
	
	public String getName() {
		return name;
	}
	
	public Location getLocation() {
		return location;
	}
	
	public Direction getDirection() {
		return direction;
	}
	
	public String getPassword() {
		return config.getOrDefault("password", null);
	}

	public boolean emitItem(ItemStack stack) {
		logger.log(Level.INFO, "Emitting message from "+name);
		ItemMessage msg = new ItemMessage(plugin, name, stack);
		return listener != null && listener.emitMessage(this, msg);
	}
	
	public void receiveMessage(ItemMessage msg) throws InvalidLocationException {
		logger.log(Level.INFO, "Received message at", name);
		Chest state = validateLocation();
		if (state == null)
			plugin.unregisterPortal(this);
		
		if (direction != Direction.IN)
			throw new InvalidLocationException("Portal is not an INPUT portal");
		
		Inventory inventory = state.getBlockInventory();
		HashMap<Integer, ItemStack> excess = inventory.addItem(msg.getStack());
		if (!excess.isEmpty()) {
			logger.log(Level.INFO, "Excess content for target chest");
			emitItem(excess.get(0));
		}
	}
	
	private Chest validateLocation() throws InvalidLocationException {
		logger.log(Level.INFO, "Validating portal location");
		BlockState state = location.getBlock().getState();
		if (!(state instanceof Chest))
			throw new InvalidLocationException("Not instanceof chest");
		
		Inventory inventory = ((Chest)state).getBlockInventory();
		ItemStack stacks[] = inventory.getContents();
		
		int first = stacks.length-MATERIAL_TEMPLATE.length;
		for (int i=first; i<stacks.length; i++) {
			if (stacks[i] == null
					|| stacks[i].getType() != MATERIAL_TEMPLATE[i-first]
					|| stacks[i].getAmount() != 1) {
				throw new InvalidLocationException("Template error at slot "+Integer.toString(i));
			}
		}
		
		BookMeta meta = (BookMeta)stacks[first+4].getItemMeta();
		if (meta.getPageCount() == 0)
			throw new InvalidLocationException("Page count is zero");

		List<String> pages = meta.getPages();
		if (!removeFormatting(pages.get(0)).startsWith("#!/udpportal"))
			throw new InvalidLocationException("Shibboleth does not match: \""+meta.getPage(1)+"\"");
		
		Pattern pat = Pattern.compile("\\s*:\\s*");
		for (String page : pages) {
			for (String line : page.split("\\r?\n")) {
				String parts[] = pat.split(removeFormatting(line.trim()), 2);
				if (parts.length == 2)
					config.put(parts[0].toLowerCase(), parts[1]);
			}
		}

		name = config.getOrDefault("name", null);
		if (name == null)
			throw new InvalidLocationException("Name missing");

		if (!checkPortalName(name))
			throw new InvalidLocationException("Name invalid");

		direction = config.getOrDefault("direction", config.getOrDefault("dir", "OUT")).equals("OUT") ? Direction.OUT : Direction.IN;

		logger.log(Level.INFO, "Portal accepted. Name: "+config.get("name")+" Direction: "+direction);
		state.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
		return (Chest)state;
	}
	
	public static boolean checkPortalName(String name) {
		return Pattern.matches("[0-9a-zA-Z_/]{3,16}", name);
	}
	
	public static String removeFormatting(String formatted) {
		return formatted.replaceAll("§.", "");
	}
	
	/* For testing */
	public void setClock(Clock clock) {
		this.clock = clock;
	}
	
	public void setTimer(Timer timer) {
		this.timer = timer;
	}
}
