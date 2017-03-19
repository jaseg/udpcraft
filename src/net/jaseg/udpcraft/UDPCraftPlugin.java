package net.jaseg.udpcraft;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import net.jaseg.udpcraft.plaintext.Server;


public class UDPCraftPlugin extends JavaPlugin implements PortalIndex, SignatureDataStore {
	private HashMap<String, Portal> portals = new HashMap<String, Portal>();
	private KeyParameter secret;
	private int maxLifetimeMillis;
	private int currentSerial;
	private Map<Integer, Long> activeSerials = new HashMap<Integer, Long>();
	private ConnectionMux mux = new ConnectionMux(getLogger(), this, this);
	private Server server;
	private TimerTask tokenPurgeTask;
	
	@Override
	public void onEnable() {
		jarLoadingHack();
		getConfig().options().copyDefaults(true);
		saveConfig();
		try {
			server = new Server(getLogger(),
					new InetSocketAddress(getConfig().getString("server.host"), getConfig().getInt("server.port")),
					getServer().getName(),
					mux);
		} catch(IOException ex) {
			getLogger().log(Level.SEVERE, "Error creating listening socket", ex);
			return;
		}
		
		ConfigurationSection section = getConfig().getConfigurationSection("portals");
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
			savePortals();
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
		
		if (getConfig().isSet("activeTokens")) {
			long now = System.currentTimeMillis();
			for (Integer token : getConfig().getIntegerList("activeTokens"))
				activeSerials.put(token, now);
		}
		
		maxLifetimeMillis = getConfig().getInt("maxLifetimeSeconds")*1000;
		currentSerial = getConfig().getInt("currentSerial", 0);
		getServer().getPluginManager().registerEvents(new ChestListener(getLogger(), this), this);
		
		server.start();
		
		tokenPurgeTask = new TimerTask() {
			public void run() {
				
			}
		};
		Timer timer = new Timer("UDPCraft Token Purger");
		timer.schedule(tokenPurgeTask, maxLifetimeMillis, maxLifetimeMillis);
		
		getLogger().log(Level.INFO, "UDPCraft loaded successfully");
	}
	
	@Override
	public void onDisable() {
		getLogger().log(Level.INFO, "Disabling UDPCraft");
		if (server != null)
			server.stop();
		tokenPurgeTask.cancel();
		getConfig().set("activeTokens", new ArrayList<Integer>(activeSerials.keySet()));
		saveConfig();
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
	
    /* Signature handling */
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
	
	public synchronized void voidSerial(int serial) {
		if (!activeSerials.containsKey(serial))
			throw new IllegalArgumentException("Invalid key!");

		if (System.currentTimeMillis() - activeSerials.get(serial) > maxLifetimeMillis) {
			getLogger().log(Level.INFO, "Expired item with time "+activeSerials.get(serial)+", current time "+System.currentTimeMillis()+", max lifetime "+maxLifetimeMillis);
			throw new IllegalArgumentException("Item is expired!");
		}
		activeSerials.remove(serial);
		getLogger().log(Level.INFO, "Voiding serial", serial);
	}
	
	/* Portal index */
	public boolean tryRegisterPortal(Location location) {
		getLogger().log(Level.INFO, "Checking out potential portal location "+location.toString());
		try {
			Portal portal = new Portal(getLogger(), this, location, mux);
			String name = portal.getName();
			
			if (portals.containsKey(name)) {
				if (portals.get(name).getLocation().equals(location))
					getLogger().log(Level.INFO, "Portal \""+name+"\" already known");
				else
					getLogger().log(Level.INFO, "Overriding portal \""+name+"\"");
			} else {
				getLogger().log(Level.INFO, "Creating new portal \""+name+"\"");
			}
			
			registerPortal(portal);
			return true;
		} catch (Portal.InvalidLocationException ex) {
			return false;
		}
	}
	
	public void registerPortal(Portal portal) {
		portals.put(portal.getName(), portal);
		savePortals();
	}
	
	public void savePortals() {
		Map <String, Location> locationmap = new HashMap<String, Location>();
		for (Map.Entry<String, Portal> e : portals.entrySet())
			locationmap.put(e.getKey(), e.getValue().getLocation());
		getConfig().createSection("portals", locationmap);
		saveConfig();
	}

	public void unregisterPortal(Portal portal) {
		portals.remove(portal.getName());
		savePortals();
	}
	
	public Portal lookupPortal(String name) {
		return portals.getOrDefault(name, null);
	}
	
	public Portal lookupPortalOrDie(String name) throws IllegalArgumentException {
		if (!Portal.checkPortalName(name))
			throw new IllegalArgumentException("Invalid portal name \""+name+"\"");
		if (!portals.containsKey(name))
			throw new IllegalArgumentException("Portal \""+name+"\" does not exist");
		return portals.get(name);
	}
	
	/* Periodically re-check portals and re-save config
	 * Periodically clear cache of timeouted items
	 * Periodically sync sequence numbers
	 */
}
