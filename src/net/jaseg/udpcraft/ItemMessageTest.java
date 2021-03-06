package net.jaseg.udpcraft;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.Arrays;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ YamlConfiguration.class, ItemStack.class, UDPCraftPlugin.class })
public class ItemMessageTest {
	static final byte referenceMessage[] = {
		   56,  -32,   61,  104,   87, -102,   74,   41,  -35,  -11,  -66,  -45, -115,   14,  -35,  105,
		   75,   -9,  -28, -102,  123,  -62,   58, -126,  -88,  -82,  110,   35,  -52, -102,   42,   69,
		    0,    0,    0,    1,  116,  104,  105,  115,   32,  105,  115,   32,  111,  110,  108,  121,
		   32,   97,   32,  116,  101,  115,  116
	};
	
	@Test
	public void testSerialize() {
		UDPCraftPlugin plugin = mock(UDPCraftPlugin.class);
		when(plugin.getSecret()).thenReturn(new KeyParameter("foobar".getBytes()));
		when(plugin.nextSerial()).thenReturn(1);
		
		byte serialized[] = ItemMessage.sign(plugin, "this is only a test".getBytes());
		
		assertArrayEquals(referenceMessage, serialized);
	}
	
	private void unsignImpl(byte data[], String portalname, int serial) {
		UDPCraftPlugin plugin = mock(UDPCraftPlugin.class);
		when(plugin.getSecret()).thenReturn(new KeyParameter("foobar".getBytes()));
		
		byte payload[] = ItemMessage.unsign(plugin, data);
		
		verify(plugin).voidSerial(eq(serial));
		assertArrayEquals("this is only a test".getBytes(), payload);
	}

	@Test
	public void testDeserialize() {
		unsignImpl(referenceMessage, "testportal", 1);
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testDeserializeTamperSignature() {
		byte data[] = Arrays.copyOf(referenceMessage, referenceMessage.length);
		data[2] = 23;
		unsignImpl(data, "testportal", 1);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testDeserializeTamperSerial() {
		byte data[] = Arrays.copyOf(referenceMessage, referenceMessage.length);
		data[32+2] = 23;
		unsignImpl(data, "testportal", 2);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testDeserializeTamperPayload() {
		byte data[] = Arrays.copyOf(referenceMessage, referenceMessage.length);
		data[32+4+2] = 23;
		unsignImpl(data, "testportal", 1);
	}
}
