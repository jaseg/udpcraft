package net.jaseg.udpcraft;

import java.nio.ByteBuffer;
import java.util.logging.Level;

import org.bouncycastle.crypto.digests.SHA256Digest;
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
	
	public ItemMessage(UDPCraftPlugin plugin) {
		this.plugin = plugin;
	}
	
	public ItemStack getStack() {
		return stack;
	}
	
	public String portalName() {
		return portalName;
	}
	
	public byte[] serializeStack() {
		FileConfiguration fconfig = new YamlConfiguration();
		fconfig.set("item", stack);
		return fconfig.saveToString().getBytes();
	}
	
	public byte[] serialize() {
		/* This is a bit improvised. Please excuse me. */
		return sign(serializeStack());
	}
	
	public byte[] sign(byte[] cbytes) {
		if (cbytes.length > Integer.MAX_VALUE/2) {
			plugin.getLogger().log(Level.SEVERE, "Got item stack serializing to more than INT_MAX/2 bytes:", cbytes.length);
			throw new IllegalArgumentException();
		}
		
		int innerLen = cbytes.length + 4;
		ByteBuffer inner = ByteBuffer.allocate(innerLen);
		inner.putInt(plugin.nextSerial());
		inner.put(cbytes);
		inner.rewind();
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA256Digest());
		hmac.init(plugin.getSecret());
		hmac.update(inner.array(), 0, innerLen);
		hmac.doFinal(macbytes, 0);

		byte nameBytes[] = portalName.getBytes();
		int length = 1 + nameBytes.length + macbytes.length + innerLen;
		byte out[] = new byte[length];
		ByteBuffer outer = ByteBuffer.wrap(out);
		outer.put((byte)nameBytes.length);
		outer.put(nameBytes);
		outer.put(macbytes);
		outer.put(inner);
		return out;
	}
	
	public static ItemMessage deserialize(UDPCraftPlugin plugin, byte data[]) throws IllegalArgumentException {
		ItemMessage msg = new ItemMessage(plugin);
		msg.deserializeFrom(data);
		return msg;
	}
	
	public void deserializeFrom(byte data[]) throws IllegalArgumentException{
		byte payload[] = unsign(data);
		this.stack = unwrapItemStack(plugin, payload);
	}
	
	public byte[] unsign(byte []in) throws IllegalArgumentException{
		ByteBuffer buf = ByteBuffer.wrap(in);
		int nameLen = buf.get();
		portalName = new String(buf.array(), buf.position(), nameLen);
		
		if (!Portal.checkPortalName(portalName))
			throw new IllegalArgumentException("Invalid portal name");
		
		buf.position(buf.position() + nameLen);
		
		byte macbytes_ref[] = new byte[MAC_LENGTH/8];
		buf.get(macbytes_ref);
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA256Digest());
		hmac.init(plugin.getSecret());
		hmac.update(buf.array(), buf.position(), buf.remaining());
		hmac.doFinal(macbytes, 0);
		
		if (!Arrays.constantTimeAreEqual(macbytes_ref, macbytes))
			throw new IllegalArgumentException("Invalid signature");

		int serial = buf.getInt();
		if (!plugin.voidSerial(serial))
			throw new IllegalArgumentException("Serial is already void");
		
		byte out[] = new byte[buf.remaining()];
		buf.get(out);
		return out;
	}
	
	public static ItemStack unwrapItemStack(UDPCraftPlugin plugin, byte data[]) throws IllegalArgumentException{
		FileConfiguration fconfig = new YamlConfiguration();
		String cstring = new String(data);
		try {
			fconfig.loadFromString(cstring);
		} catch (InvalidConfigurationException ex) {
			plugin.getLogger().log(Level.SEVERE, "Cannot parse signed configuration. This is likely a bug.", ex);
			return new ItemStack(Material.OBSIDIAN, 1);
		}
		
		return fconfig.getItemStack("item");
	}
}
