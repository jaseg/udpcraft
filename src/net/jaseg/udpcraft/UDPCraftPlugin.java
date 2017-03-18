package net.jaseg.udpcraft;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bukkit.Bukkit;
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
		jarLoadingHack();
		getConfig().options().copyDefaults(true);
		saveConfig();
		try {
			server = new UDPCraftServer(this,
					getConfig().getInt("server.port"),
					InetAddress.getByName(getConfig().getString("server.host")));
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
			getLogger().log(Level.WARNING, "Portal list not found. ");
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
		getServer().getPluginManager().registerEvents(new ChestListener(this), this);
		
		getLogger().log(Level.INFO, "UDPCraft loaded successfully");
	}
	
	@Override
	public void onDisable() {
		getLogger().log(Level.INFO, "Disabling UDPCraft");
		if (server != null)
			server.close();
	}
	
	public void jarLoadingHack() {
        try {
            final File[] libs = new File[] {
                    new File(getDataFolder(), "bcprov.jar") };
            for (final File lib : libs) {
                if (!lib.exists()) {
                    JarUtils.extractFromJar(lib.getName(),
                            lib.getAbsolutePath());
                }
            }
            for (final File lib : libs) {
                if (!lib.exists()) {
                    getLogger().warning(
                            "There was a critical error loading My plugin! Could not find lib: "
                                    + lib.getName());
                    Bukkit.getServer().getPluginManager().disablePlugin(this);
                    return;
                }
                addClassPath(JarUtils.getJarUrl(lib));
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
	}
	
    private void addClassPath(final URL url) throws IOException {
        final URLClassLoader sysloader = (URLClassLoader) ClassLoader
                .getSystemClassLoader();
        final Class<URLClassLoader> sysclass = URLClassLoader.class;
        try {
            final Method method = sysclass.getDeclaredMethod("addURL",
                    new Class[] { URL.class });
            method.setAccessible(true);
            method.invoke(sysloader, new Object[] { url });
        } catch (final Throwable t) {
            t.printStackTrace();
            throw new IOException("Error adding " + url
                    + " to system classloader");
        }
    }
	
	public KeyParameter getSecret() {
		return secret;
	}
	
	public synchronized int nextSerial() {
		int serial = currentSerial;
		activeSerials.put(serial, System.currentTimeMillis());
		currentSerial++;
		getLogger().log(Level.INFO, "Issuing serial", serial);
		return serial;
	}
	
	public synchronized boolean voidSerial(int serial) {
		if (!activeSerials.containsKey(serial))
			return false;

		if (System.currentTimeMillis() - activeSerials.get(serial) > maxLifetimeSeconds)
			throw new IllegalArgumentException("Item is expired!");
		activeSerials.remove(serial);
		getLogger().log(Level.INFO, "Voiding serial", serial);
		return true;
	}
	
	boolean tryRegisterPortal(Location location) {
		Portal portal = Portal.fromLocation(this, location, server);
		getLogger().log(Level.INFO, "Checking out potential portal location "+location.toString());
		if (portal == null)
			return false;
		registerPortal(portal);
		return true;
	}
	
	private void registerPortal(Portal portal) {
		getLogger().log(Level.INFO, "Registering portal at location "+portal.getLocation().toString());
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
		getLogger().log(Level.INFO, "Routing message to", msg.portalName());
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
