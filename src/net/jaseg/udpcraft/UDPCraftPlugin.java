package net.jaseg.udpcraft;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;


public class UDPCraftPlugin extends JavaPlugin {
	private UDPCraftServer server;
	private HashMap<String, Portal> listeners = new HashMap<String, Portal>();
	private HashMap<Location, Portal> rlisteners = new HashMap<Location, Portal>();
	private KeyParameter secret;
	private int maxLifetimeSeconds;
	private int currentSerial;
	private Map<Integer, Long> activeSerials = new HashMap<Integer, Long>();
	
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
		
		ConfigurationSection section = getConfig().getConfigurationSection("listeners");
		if (section != null) {
			Map<String, Object> map = section.getValues(false);
			for (Map.Entry<String, Object> e : map.entrySet()) {
				Object value = e.getValue();
				if (!(value instanceof Location)) {
					getLogger().log(Level.WARNING, "Listener for key "+e.getKey()+" is not a Location");
					continue;
				}
				tryRegisterPortal((Location)value);
			}
		} else {
			getLogger().log(Level.WARNING, "Portal list not found. Creating an empty one.");
			saveListeners();
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
	
	public KeyParameter getSecret() {
		return secret;
	}
	
	public long getMaxLifetimeSeconds() {
		return maxLifetimeSeconds;
	}
	
	public synchronized int nextSerial() {
		int serial = currentSerial;
		activeSerials.put(serial, System.currentTimeMillis());
		currentSerial++;
		return serial;
	}
	
	public synchronized boolean voidSerial(int serial) {
		if (!activeSerials.containsKey(serial))
			return false;
		activeSerials.remove(serial);
		return true;
	}
	
	boolean tryRegisterPortal(Location location) {
		Portal portal = Portal.fromLocation(this, location, server);
		if (portal == null)
			return false;
		registerPortal(portal);
		return true;
	}
	
	private void registerPortal(Portal portal) {
		listeners.put(portal.getName(), portal);
		rlisteners.put(portal.getLocation(), portal);
		saveListeners();
	}
	
	private void saveListeners() {
		getConfig().createSection("listeners", listeners);
		saveConfig();
	}

	public void unregisterPortal(Portal portal) {
		rlisteners.remove(portal.getLocation());
		listeners.remove(portal.getName());
		saveListeners();
	}
	
	public boolean routeIncomingMessage(ItemMessage msg) {
		if (!listeners.containsKey(msg.portalName()))
			return false;
		listeners.get(msg.portalName()).receiveMessage(msg);
		return true;
	}
	
	public Portal lookupPortal(String name) {
		return listeners.getOrDefault(name, null);
	}
	
	/* Periodically re-check portals and re-save config
	 * Periodically clear cache of timeouted items
	 * Periodically sync sequence numbers
	 */
}
