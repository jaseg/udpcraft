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
		
		int innerLen = cbytes.length + 4;
		ByteBuffer inner = ByteBuffer.allocate(innerLen);
		inner.putInt(plugin.nextSerial());
		inner.put(cbytes);
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA3Digest(MAC_LENGTH));
		hmac.init(plugin.getSecret());
		hmac.update(inner.array(), 0, innerLen);
		hmac.doFinal(macbytes, 0);

		byte nameBytes[] = portalName.getBytes();
		int length = 1 + nameBytes.length + macbytes.length + innerLen;
		ByteBuffer outer = ByteBuffer.allocate(length);
		outer.put((byte)nameBytes.length);
		outer.put(nameBytes);
		outer.put(macbytes);
		outer.put(inner);
		return outer.array();
	}
	
	public static ItemMessage deserialize(UDPCraftPlugin plugin, byte[] data) throws IllegalArgumentException {
		ByteBuffer buf = ByteBuffer.wrap(data);
		int nameLen = buf.getChar();
		String name = new String(buf.array(), buf.position(), nameLen);
		
		ItemStack stack = unwrapItemStack(plugin, ByteBuffer.wrap(buf.array(), buf.position()+len, buf.remaining()-len));
		
		return new ItemMessage(plugin, name, stack);
	}
	
	private static ItemStack unwrapItemStack(UDPCraftPlugin plugin, ByteBuffer buf) throws IllegalArgumentException{
		if (buf.remaining() < MAC_LENGTH/8 + 1)
			throw new IllegalArgumentException("Invalid framing: not enough data");

		byte macbytes_ref[] = new byte[MAC_LENGTH/8];
		buf.get(macbytes_ref);
		
		int serial = buf.getInt(); /* FIXME check and invalidate this */

		byte macbytes[] = new byte[MAC_LENGTH/8];
		plugin.getLogger().log(Level.SEVERE, "Buffer params: "+buf.position()+" "+buf.remaining()+" "+buf.array().length+" "+macbytes.length);
		HMac hmac = new HMac(new SHA3Digest(MAC_LENGTH));
		hmac.init(plugin.getSecret());
		hmac.update(buf.array(), buf.position(), buf.remaining());
		hmac.doFinal(macbytes, 0);
		
		if (!Arrays.constantTimeAreEqual(macbytes_ref, macbytes))
			throw new IllegalArgumentException("Invalid keys");

		if (!plugin.voidSerial(serial))
			throw new IllegalArgumentException("Serial is already void");
		
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
