package net.jaseg.udpcraft;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;


@RunWith(PowerMockRunner.class)
@PrepareForTest({ Location.class, Timer.class, UDPCraftPlugin.class, Chest.class, Block.class, Inventory.class, ItemStack.class, BookMeta.class, ItemMessage.class, FileConfiguration.class })
public class PortalTest {

	private Portal portal;
	private Location location;
	private Clock clock;
	private Timer timer;
	private UDPCraftPlugin plugin;
	private ItemListener listener;
	private Chest chest;
	private Block block;
	private Inventory inventory;
	
	public void setUp(String config[]) throws Portal.InvalidLocationException {
		ItemStack items[] = makeItems(27, null, Portal.MATERIAL_TEMPLATE);
		items[items.length-5] = makeBook(config == null ? new String[]{"#!/udpportal\nname: testportal"} : config);
		setUpTail(items);
	}
	
	private ItemStack makeBook(String[] config) {
		BookMeta meta = mock(BookMeta.class);
		when(meta.getPages()).thenReturn(Arrays.asList(config));
		when(meta.getPageCount()).thenReturn(config.length);
		ItemStack book = mock(ItemStack.class);
		when(book.getItemMeta()).thenReturn(meta);
		when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
		when(book.getAmount()).thenReturn(1);
		return book;
	}
	
	private ItemStack mockStack(Material mat) {
		return mockStack(mat, 1);
	}
	
	private ItemStack mockStack(Material mat, int count) {
		ItemStack stack = mock(ItemStack.class);
		when(stack.getType()).thenReturn(mat);
		when(stack.getAmount()).thenReturn(count);
		return stack;
	}
	
	private ItemStack[] makeItems(int len, Material templateBegin[], Material templateEnd[]) { 
		return makeItems(len, templateBegin, null, templateEnd, null);
	}
	
	private ItemStack[] makeItems(int len, Material templateBegin[], int countBegin[], Material templateEnd[], int countEnd[]) {
		if ((templateBegin != null && countBegin != null && templateBegin.length != countBegin.length)
			||(templateEnd != null && countEnd != null && templateEnd.length != countEnd.length))
			throw new IllegalArgumentException("template material count/lengths not matching");
		ItemStack items[] = new ItemStack[len];
		
		if (templateBegin != null)
			for (int i=0; i<templateBegin.length; i++)
				items[i] = mockStack(templateBegin[i], countBegin == null ? 1 : countBegin[i]);
		
		if (templateEnd != null)
			for (int i=0; i<templateEnd.length; i++)
				items[len-templateEnd.length+i] = mockStack(templateEnd[i], countEnd == null ? 1 : countEnd[i]);
		
		return items;
	}
	
	private void setUpTail(ItemStack items[]) throws Portal.InvalidLocationException {
		inventory = mock(Inventory.class);
		when(inventory.getContents()).thenReturn(items);
		chest = mock(Chest.class);
		when(chest.getBlockInventory()).thenReturn(inventory);
		block = mock(Block.class);
		when(block.getState()).thenReturn(chest);
		location = mock(Location.class);
		when(location.getBlock()).thenReturn(block);
		FileConfiguration config = mock(FileConfiguration.class);
		when(config.getInt(any())).thenReturn(100);
		plugin = mock(UDPCraftPlugin.class);
		when(plugin.getConfig()).thenReturn(config);
		clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneId.systemDefault());
		timer = mock(Timer.class);
		portal = new Portal(Logger.getAnonymousLogger(), plugin, location, listener);
		portal.setTimer(timer);
		portal.setClock(clock);
	}

	@Test
	public void testBasicSetup() throws Portal.InvalidLocationException {
		setUp(null);
		assertEquals("testportal", portal.getName());
		assertEquals(null, portal.getPassword());
		ArgumentCaptor<FixedMetadataValue> nameCap = ArgumentCaptor.forClass(FixedMetadataValue.class);
		verify(chest, times(1)).setMetadata(eq(Portal.METADATA_KEY), nameCap.capture());
		assertEquals("testportal", nameCap.getValue().asString());
	}

	@Test
	public void testPassword() throws Portal.InvalidLocationException {
		setUp(new String[]{"#!/udpportal\nname: testportal\npassword: testpassword"});
		assertEquals("testportal", portal.getName());
		assertEquals("testpassword", portal.getPassword());
	}
	
	@Test(expected=Portal.InvalidLocationException.class)
	public void testEmptyConfig() throws Portal.InvalidLocationException {
		setUp(new String[]{""});
	}

	@Test
	public void testEscapes() throws Portal.InvalidLocationException {
		setUp(new String[]{"§0§0#!§0/udpp§1o§artal§4\n§ename: §3§3testportal\np§fassword: testpassword"});
		assertEquals("testportal", portal.getName());
		assertEquals("testpassword", portal.getPassword());
	}

	@Test
	public void testWhitespace() throws Portal.InvalidLocationException {
		setUp(new String[]{"#!/udpportal   \n\tname : testportal\t \n    password\t\t:  testpassword "});
		assertEquals("testportal", portal.getName());
		assertEquals("testpassword", portal.getPassword());
	}

	@Test(expected=Portal.InvalidLocationException.class)
	public void testInvalidPortalName() throws Portal.InvalidLocationException {
		setUp(new String[]{"#!/udpportal\nname: test=portal"});
	}
	
	@Test(expected=Portal.InvalidLocationException.class)
	public void testInvalidPortalNameUnicode() throws Portal.InvalidLocationException {
		setUp(new String[]{"#!/udpportal\nname: testpörtal"});
	}
	
	@Test(expected=Portal.InvalidLocationException.class)
	public void testInvalidPattern() throws Portal.InvalidLocationException {
		ItemStack items[] = makeItems(27, null, Portal.MATERIAL_TEMPLATE);
		items[items.length-5] = makeBook(new String[]{"#!/udpportal\nname: testportal"});
		items[items.length-2] = mockStack(Material.WOOD);
		setUpTail(items);
	}
	
	@Test
	public void testDirection() throws Portal.InvalidLocationException {
		testDirectionImpl("", Portal.Direction.OUT);
		testDirectionImpl("direction: OUT", Portal.Direction.OUT);
		testDirectionImpl("dir: OUT", Portal.Direction.OUT);
		testDirectionImpl("direction: IN", Portal.Direction.IN);
		testDirectionImpl("dir: IN", Portal.Direction.IN);
		testDirectionImpl("direction: IN\ndir: OUT", Portal.Direction.IN);
		testDirectionImpl("dir: OUT\ndirection: IN", Portal.Direction.IN);
		testDirectionImpl("\n\ndirection:  IN  \n\n", Portal.Direction.IN);
	}
		
	public void testDirectionImpl(String dirstring, Portal.Direction dir) throws Portal.InvalidLocationException {
		setUp(new String[]{"#!/udpportal\nname: testportal\n"+dirstring});
		assertEquals("testportal", portal.getName());
		assertEquals(null, portal.getPassword());
		assertEquals(dir, portal.getDirection());
	}
	
	@Test
	public void testEmitItem() throws Portal.InvalidLocationException {
		setUp(null);
		portal.emitItem(mockStack(Material.WOOD, 64));
		
		listener = mock(ItemListener.class);
		setUp(null);
		when(listener.emitMessage(eq(portal), any())).thenReturn(true);
		portal.emitItem(mockStack(Material.WOOD, 64));
		verify(listener, times(1)).emitMessage(eq(portal), any());
	}

	@Test
	public void testReceiveMessage() throws Portal.InvalidLocationException {
		listener = mock(ItemListener.class);
		testDirectionImpl("dir: IN", Portal.Direction.IN);
		when(inventory.addItem(any())).thenReturn(new HashMap<Integer, ItemStack>());

		ItemMessage msg = mock(ItemMessage.class);
		ItemStack stack = mockStack(Material.WOOD, 64);
		when(msg.getStack()).thenReturn(stack);
		portal.receiveMessage(msg);
		verify(listener, times(0)).emitMessage(any(), any());

		ItemStack excessStack = mockStack(Material.WOOD, 32);
		HashMap<Integer, ItemStack> excess = new HashMap<Integer, ItemStack>();
		excess.put(0,  excessStack);
		when(inventory.addItem(any())).thenReturn(excess);
		
		portal.receiveMessage(msg);
		ArgumentCaptor<ItemMessage> msgCap = ArgumentCaptor.forClass(ItemMessage.class);
		verify(listener, times(1)).emitMessage(eq(portal), msgCap.capture());
		assertEquals(excessStack, msgCap.getValue().getStack());
	}
	
	@Test
	public void testQueueUpdate() throws Portal.InvalidLocationException{
		listener = mock(ItemListener.class);
		when(listener.emitMessage(any(), any())).thenReturn(true);
		setUp(new String[]{"#!/udpportal\nname: testportal\ndirection: in"});
		portal.queueUpdate();
		verify(timer, times(0)).schedule(any(), any());
		
		setUp(null);
		portal.queueUpdate();
		verify(timer, times(1)).schedule(any(), eq(100L));

		ItemStack items[] = makeItems(27, new Material[]{Material.WOOD, Material.APPLE}, new int[]{23, 42}, Portal.MATERIAL_TEMPLATE, null);
		items[items.length-10] = mockStack(Material.STONE);
		items[items.length-5] = makeBook(new String[]{"#!/udpportal\nname: testportal"});
		ItemStack bak0 = items[0], bak1 = items[1], baklast = items[items.length-10];
		setUpTail(items);
		portal.queueUpdate();
		ArgumentCaptor<TimerTask> taskCap = ArgumentCaptor.forClass(TimerTask.class);
		verify(timer, times(1)).schedule(taskCap.capture(), eq(100L));
		taskCap.getValue().run();
		
		ArgumentCaptor<ItemMessage> msgCap = ArgumentCaptor.forClass(ItemMessage.class);
		verify(listener, times(3)).emitMessage(eq(portal), msgCap.capture());
		List<ItemMessage> msgs = msgCap.getAllValues();
		assertEquals(bak0, msgs.get(0).getStack());
		assertEquals(bak1, msgs.get(1).getStack());
		assertEquals(baklast, msgs.get(2).getStack());
		
		ArgumentCaptor<ItemStack[]> itemsCap = ArgumentCaptor.forClass(ItemStack[].class);
		verify(inventory, times(1)).setContents(itemsCap.capture());
		
		ItemStack foo[] = itemsCap.getValue();
		for (int i=0; i<foo.length-9; i++)
			assertEquals("Non-null element at index "+i, null, foo[i]);
		for (int i=0; i<9; i++)
			assertEquals("Template destroyed at index "+(foo.length-9+i), Portal.MATERIAL_TEMPLATE[i], foo[foo.length-9+i].getType());
		
		/* FIXME: add concurrency stuff here */
	}
}
