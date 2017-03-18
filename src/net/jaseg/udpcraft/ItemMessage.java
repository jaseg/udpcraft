package net.jaseg.udpcraft;

import java.nio.ByteBuffer;
import java.util.logging.Level;

import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.util.Arrays;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public class ItemMessage {
	private static final int MAC_LENGTH = 256;
	
	private ItemStack stack;
	private String portalName;
	
	private UDPCraftPlugin plugin;
	
	public ItemMessage(UDPCraftPlugin plugin, String portalName, ItemStack stack) {
		this.plugin = plugin;
		this.stack = stack;
		this.portalName = portalName;
	}
	
	
	public ItemStack getStack() {
		return stack;
	}
	
	public String portalName() {
		return portalName;
	}
	
	
	public byte[] serialize() {
		/* This is a bit improvised. Please excuse me. */
		FileConfiguration fconfig = new YamlConfiguration();
		fconfig.set("item", stack);
		byte cbytes[] = fconfig.saveToString().getBytes();

		if (cbytes.length > Integer.MAX_VALUE/2) {
			plugin.getLogger().log(Level.SEVERE, "Got item stack serializing to more than INT_MAX/2 bytes:", cbytes.length);
			throw new IllegalArgumentException();
		}
		
		int innerLen = cbytes.length + 16;
		ByteBuffer inner = ByteBuffer.allocate(innerLen);
		inner.putInt(innerLen); // buffer length
		inner.putInt(plugin.nextSerial());
		inner.putLong(System.currentTimeMillis()); // timestamp
		inner.put(cbytes);
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA3Digest(MAC_LENGTH));
		hmac.init(plugin.getSecret());
		hmac.update(inner.array(), 0, innerLen);
		hmac.doFinal(macbytes, 0);
		
		byte listenerBytes[] = portalName.getBytes();
		int length = listenerBytes.length+macbytes.length+innerLen;
		ByteBuffer outer = ByteBuffer.allocate(4+length);
		outer.putInt(length);
		outer.put(listenerBytes);
		outer.put(macbytes);
		outer.put(inner);
		return outer.array();
	}
	
	public static ItemMessage deserialize(UDPCraftPlugin plugin, byte[] data) throws IllegalArgumentException {
		ByteBuffer buf = ByteBuffer.wrap(data);
		int len = buf.getInt();
		String name = new String(buf.array(), buf.position(), len);
		
		ItemStack stack = unwrapItemStack(plugin, ByteBuffer.wrap(buf.array(), buf.position()+len, buf.remaining()-len));
		
		return new ItemMessage(plugin, name, stack);
	}
	
	private static ItemStack unwrapItemStack(UDPCraftPlugin plugin, ByteBuffer buf) throws IllegalArgumentException{
		if (buf.remaining() < MAC_LENGTH/8 + 12 + 1)
			throw new IllegalArgumentException("Invalid framing: not enough data");

		byte macbytes_ref[] = new byte[MAC_LENGTH/8];
		buf.get(macbytes_ref);
		
		int len = buf.getInt();
		int serial = buf.getInt(); /* FIXME check and invalidate this */
		long timestamp = buf.getLong();

		byte macbytes[] = new byte[MAC_LENGTH/8];
		plugin.getLogger().log(Level.SEVERE, "Buffer params: "+buf.position()+" "+buf.remaining()+" "+buf.array().length+" "+macbytes.length+" "+len);
		HMac hmac = new HMac(new SHA3Digest(MAC_LENGTH));
		hmac.init(plugin.getSecret());
		hmac.update(buf.array(), macbytes.length, len);
		hmac.doFinal(macbytes, 0);
		
		if (!Arrays.constantTimeAreEqual(macbytes_ref, macbytes))
			throw new IllegalArgumentException("Invalid keys");

		if (System.currentTimeMillis() - timestamp > plugin.getMaxLifetimeSeconds())
			throw new IllegalArgumentException("Item is expired!");
		
		FileConfiguration fconfig = new YamlConfiguration();
		String cstring = new String(buf.array(), buf.position(), buf.remaining());
		try {
			fconfig.loadFromString(cstring);
		} catch (InvalidConfigurationException ex) {
			plugin.getLogger().log(Level.SEVERE, "Cannot parse signed configuration. This is likely a bug.", ex);
			return new ItemStack(Material.OBSIDIAN, 1);
		}
		
		return fconfig.getItemStack("item");
	}
}
