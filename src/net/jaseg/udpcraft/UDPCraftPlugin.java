package net.jaseg.udpcraft;

import java.io.BufferedReader;
import java.io.CharArrayReader;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.crypto.digests.SHA3Digest;


public class UDPCraftPlugin extends JavaPlugin {
	private static final int MAC_LENGTH = 256;
	private UDPCraftServer server;
	private HashMap<String, Location> listeners = new HashMap<String, Location>();
	private HashMap<Location, String> rlisteners = new HashMap<Location, String>();
	private KeyParameter secret;
	private int maxLifetimeSeconds;
	private int currentSerial;
	
	@Override
	public void onEnable() {
		getConfig().options().copyDefaults(true);
		saveConfig();
		try {
			server = new UDPCraftServer(this,
					getConfig().getInt("socket.port"),
					InetAddress.getByName(getConfig().getString("socket.host")));
			getServer().getPluginManager().registerEvents(new ChestListener(this), this);
		} catch(SocketException ex) {
			getLogger().log(Level.SEVERE, "Error creating listening socket", ex);
			return;
		} catch(UnknownHostException ex) {
			getLogger().log(Level.SEVERE, "Error creating listening socket", ex);
			return;
		}
		
		Map<String, Object> map = getConfig().getConfigurationSection("listeners").getValues(false);
		for (Map.Entry<String, Object> e : map.entrySet()) {
			Object value = e.getValue();
			if (!(e instanceof Location)) {
				getLogger().log(Level.WARNING, "Listener for key "+e.getKey()+" is not a Location");
				continue;
			}
			listeners.put(e.getKey(), (Location)value);
			rlisteners.put((Location)value, e.getKey());
		}
		
		if (!getConfig().isSet("secret")) {
			getLogger().log(Level.WARNING, "No server secret is set! Creating a random one.");
			SecureRandom srand = new SecureRandom();
			byte secretBytes[] = new byte[24];
			srand.nextBytes(secretBytes);
			String secretStr = Base64.getEncoder().encodeToString(secretBytes);
			getConfig().set("secret", secretStr);
			saveConfig();
		}
		secret = new KeyParameter(getConfig().getString("secret").getBytes());
		
		maxLifetimeSeconds = getConfig().getInt("maxLifetimeSeconds");
		currentSerial = getConfig().getInt("currentSerial", 0);
	}
	
	@Override
	public void onDisable() {
		if (server != null)
			server.close();
	}
	
	public String portalName(Location location) {
		BlockState state = location.getBlock().getState();
		if (!(state instanceof Chest))
			return null;
		
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
		for (int i=first; i<=last; i++)
			if (stacks[i] == null
				|| stacks[i].getType() != materials[i-first]
				|| stacks[i].getAmount() != 1)
				return null;
		
		BookMeta meta = (BookMeta)stacks[first+4].getItemMeta();
		if (meta.getPageCount() == 0)
			return null;
		if (!meta.getPage(0).startsWith("This is a\nUDP Portal."))
			return null;
		
		String name = meta.getTitle();
		if (!Pattern.matches("[0-9a-zA-Z_/]{3,16}", name))
			return null;
		return name;
	}
	
	boolean tryRegisterPortal(Location location) {
		String name = portalName(location);
		if (name == null)
			return false;
		registerPortal(name, location);
		return true;
	}
	
	private void registerPortal(String name, Location location) {
		listeners.put(name, location);
		rlisteners.put(location, name);
		saveListeners();
	}
	
	private void saveListeners() {
		getConfig().createSection("listeners", listeners);
		saveConfig();
	}
	
	public synchronized ByteBuffer wrapItemStack(ItemStack stack) {
		/* This is a bit improvised. Please excuse me. */
		FileConfiguration fconfig = new YamlConfiguration();
		fconfig.set("item", stack);
		byte cbytes[] = fconfig.saveToString().getBytes();

		if (cbytes.length > Integer.MAX_VALUE/2) {
			getLogger().log(Level.SEVERE, "Got item stack serializing to more than INT_MAX/2 bytes:", cbytes.length);
			throw new IllegalArgumentException();
		}
		
		int innerLen = cbytes.length + 12;
		ByteBuffer inner = ByteBuffer.allocate(innerLen);
		inner.putInt(innerLen); // buffer length
		inner.putLong(System.currentTimeMillis()); // timestamp
		inner.put(cbytes);
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA3Digest(256));
		hmac.init(secret);
		hmac.update(inner.array(), 0, innerLen);
		hmac.doFinal(macbytes, macbytes.length);
		
		ByteBuffer outer = ByteBuffer.allocate(innerLen+macbytes.length);
		outer.put(macbytes);
		outer.put(inner);
		return outer;
	}
	
	public synchronized ItemStack unwrapItemStack(ByteBuffer buf) throws IllegalArgumentException{
		if (buf.remaining() < MAC_LENGTH/8 + 12 + 1)
			throw new IllegalArgumentException("Invalid framing: not enough data");

		byte macbytes_ref[] = new byte[MAC_LENGTH/8];
		buf.get(macbytes_ref);
		
		int len = buf.getInt();
		long timestamp = buf.getLong();
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA3Digest(256));
		hmac.init(secret);
		hmac.update(buf.array(), macbytes.length, len);
		hmac.doFinal(macbytes, macbytes.length);
		
		if (!Arrays.constantTimeAreEqual(macbytes_ref, macbytes))
			throw new IllegalArgumentException("Invalid keys");

		if (System.currentTimeMillis() - timestamp > maxLifetimeSeconds)
			throw new IllegalArgumentException("Item is expired!");
		
		FileConfiguration fconfig = new YamlConfiguration();
		String cstring = new String(buf.array(), buf.position(), buf.remaining());
		try {
			fconfig.loadFromString(cstring);
		} catch (InvalidConfigurationException ex) {
			getLogger().log(Level.SEVERE, "Cannot parse signed configuration. This is likely a bug.", ex);
			return new ItemStack(Material.OBSIDIAN, 1);
		}
		
		return fconfig.getItemStack("item");
	}
	
	public void parseMessage(ByteBuffer buf) throws IllegalArgumentException{
		short len = buf.getShort();
		String name = new String(buf.array(), 2, 2+len);
		if (!listeners.containsKey(name))
			throw new IllegalArgumentException("Unknown listener name");
		Location loc = listeners.get(name);
		
		String currentName = portalName(loc);
		if (currentName == null) {
			listeners.remove(name);
			rlisteners.remove(loc);
			saveListeners();
			getLogger().log(Level.INFO, "Portal removed at", loc);
			throw new IllegalArgumentException("Portal has been removed");
		}
		
		if (!currentName.equals(name)) {
			getLogger().log(Level.FINE, "Portal renamed at", loc);
			getLogger().log(Level.FINE, "    Old name:", name);
			getLogger().log(Level.FINE, "    Current name:", currentName);
			listeners.remove(name);
			rlisteners.remove(loc);
			saveListeners();
			throw new IllegalArgumentException("Portal has been renamed");
		}
		
		ItemStack stack = unwrapItemStack(ByteBuffer.wrap(buf.array(), 2+len, buf.remaining()));
		
		BlockState st = loc.getBlock().getState();
		if (!(st instanceof Chest)) {
			throw new IllegalArgumentException("Portal has disappeared during processing");
		}
		Inventory inventory = ((Chest)st).getBlockInventory();
		
		HashMap<Integer, ItemStack> excess = inventory.addItem(stack);
		
		if (!excess.isEmpty())
			emitItems(excess.get(0), loc);
	}
	
	public void emitItems(ItemStack stack, Location loc) {
		String name = rlisteners.get(loc);
		ByteBuffer buf = ByteBuffer.allocate(name);
	}
	
	/* Periodically re-check portals and re-save config
	 * Periodically clear cache of timeouted items
	 * Periodically sync sequence numbers
	 */
}
