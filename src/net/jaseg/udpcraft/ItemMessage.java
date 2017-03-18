package net.jaseg.udpcraft;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.util.Arrays;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public class ItemMessage {
	private static final int MAC_LENGTH = 256;
	
	private ItemStack stack;
	private String portalName;
	private byte[] serialized;
	
	private SignatureDataStore sigdata;
	
	public ItemMessage(SignatureDataStore sigdata, String portalName, ItemStack stack) {
		this.sigdata = sigdata;
		this.stack = stack;
		this.portalName = portalName;
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
	
	public synchronized byte[] serialize() {
		/* This is a bit improvised. Please excuse me. */
		if (serialized == null)
			serialized = sign(sigdata, serializeStack());
		return serialized;
	}
	
	public static byte[] sign(SignatureDataStore sigdata, byte[] cbytes) {
		if (cbytes.length > Integer.MAX_VALUE/2)
			throw new IllegalArgumentException("Got item stack serializing to "+cbytes.length+" > INT_MAX/2 bytes");
		
		int innerLen = cbytes.length + 4;
		int outerLen = MAC_LENGTH/8 + innerLen;
		ByteBuffer buf = ByteBuffer.allocate(outerLen);
		buf.position(MAC_LENGTH/8);
		buf.putInt(sigdata.nextSerial());
		buf.put(cbytes);
		
		HMac hmac = new HMac(new SHA256Digest());
		hmac.init(sigdata.getSecret());
		hmac.update(buf.array(), MAC_LENGTH/8, innerLen);
		hmac.doFinal(buf.array(), 0);

		return buf.array();
	}
	
	public static ItemMessage deserialize(Logger logger, SignatureDataStore sigdata, byte data[]) throws IllegalArgumentException {
		return new ItemMessage(sigdata, null, unwrapItemStack(unsign(sigdata, data)));
	}
	
	public static byte[] unsign(SignatureDataStore plugin, byte []in) throws IllegalArgumentException{
		ByteBuffer buf = ByteBuffer.wrap(in);
		
		byte macbytes_ref[] = new byte[MAC_LENGTH/8];
		buf.get(macbytes_ref);
		
		byte macbytes[] = new byte[MAC_LENGTH/8];
		HMac hmac = new HMac(new SHA256Digest());
		hmac.init(plugin.getSecret());
		hmac.update(buf.array(), buf.position(), buf.remaining());
		hmac.doFinal(macbytes, 0);
		
		if (!Arrays.constantTimeAreEqual(macbytes_ref, macbytes))
			throw new IllegalArgumentException("Invalid signature");

		plugin.voidSerial(buf.getInt());
		
		byte out[] = new byte[buf.remaining()];
		buf.get(out);
		return out;
	}
	
	public static ItemStack unwrapItemStack(byte data[]) throws IllegalArgumentException{
		FileConfiguration fconfig = new YamlConfiguration();
		String cstring = new String(data);
		try {
			fconfig.loadFromString(cstring);
		} catch (InvalidConfigurationException ex) {
			throw new IllegalArgumentException("Interrnal error");
		}
		return fconfig.getItemStack("item");
	}
}
