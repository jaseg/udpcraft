package net.jaseg.udpcraft;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;

public class UDPCraftServer implements Runnable, ItemListener {
	private UDPCraftPlugin plugin;
	private Thread listener;
	private InetSocketAddress addr;
	private Map<Portal, Queue<Connection>> portalConns = new HashMap<Portal, Queue<Connection>>();
	private Selector selector;
	
	public UDPCraftServer(UDPCraftPlugin plugin, int port, InetAddress addr) throws SocketException {
		this.plugin = plugin;
		this.addr = new InetSocketAddress(addr, port);
		plugin.getLogger().log(Level.INFO, "Starting UDPCraft listener on port "+Integer.toString(port));
		this.listener = new Thread(this);
		this.listener.start();
	}
	
	public void close() {
		listener.stop(); /* FIXME */
		if (selector != null) {
			for (SelectionKey key : selector.keys()) {
				try {
					key.channel().close();
				} catch (IOException ex) {
					plugin.getLogger().log(Level.WARNING, "Error closing channel", ex);
				}
			}
		}
	}
	
	public void emitMessage(Portal portal, ItemMessage msg) {
		plugin.getLogger().log(Level.INFO, "Handling message TCP transportation for "+portal.getName());
		if (portalConns.containsKey(portal)) {
			plugin.getLogger().log(Level.INFO, "Handling message TCP transport on connection");
			portal.ackMessage(msg);
			for (Connection conn : portalConns.get(portal)) {
				plugin.getLogger().log(Level.INFO, conn.toString());
				conn.enqueueMessage(msg);
			}
		} else {
			plugin.getLogger().log(Level.INFO, "No handlers found");
		}
	}
	
	private synchronized void registerPortalConn(Portal portal, Connection conn) {
		if (!portalConns.containsKey(portal))
			portalConns.put(portal, new LinkedList<Connection>());
		portalConns.get(portal).add(conn);
	}
	
	private synchronized void unregisterPortalConn(Portal portal, Connection conn) {
		Queue<Connection> queue = portalConns.get(portal);
		queue.remove(conn);
		if (queue.isEmpty())
			portalConns.remove(portal);
	}
	
	public static class Connection {
		public static final int HEADER_BYTES = 8;
		private static enum State {
			RX_HEADER,
			RX_PAYLOAD
		}
		public static enum Tag {
			SUBMIT_ITEMS,
			SUBSCRIBE_PORTAL,
			UNSUBSCRIBE_PORTAL
		}
		
		private SocketChannel ch;
		private ByteBuffer rbuf = ByteBuffer.allocate(HEADER_BYTES);
		State state = State.RX_HEADER;
		Tag tag;
		private Queue<ItemMessage> msgs = new LinkedList<ItemMessage>();
		private ByteBuffer wbuf = ByteBuffer.allocate(0);
		
		private UDPCraftPlugin plugin;
		private UDPCraftServer server;
		
		public Connection(UDPCraftPlugin plugin, UDPCraftServer server, SocketChannel ch) {
			this.plugin = plugin;
			this.server = server;
			this.ch = ch;
		}
		
		public void enqueueMessage(ItemMessage msg) {
			plugin.getLogger().log(Level.INFO, "Queueing message");
			msgs.add(msg);
			try {
				handleWrite();
				// FIXME ch.write(ByteBuffer.allocate(0));
			} catch(IOException ex) {
				plugin.getLogger().log(Level.WARNING, "IOException on tx");
			}
		}
		
		private void handleWrite() throws IOException {
			if (wbuf.hasRemaining()) {
				ch.write(wbuf);
				if (wbuf.hasRemaining())
					return;
			}
			
			while (!msgs.isEmpty() && !wbuf.hasRemaining()) {
				wbuf = ByteBuffer.wrap(msgs.remove().serialize());
				ch.write(wbuf);
			}
		}
		
		private void handleRead() throws IOException {
			plugin.getLogger().log(Level.INFO, "Reading from connection, buffer size: "+Integer.toString(rbuf.remaining()));
			ch.read(rbuf);
			if (!rbuf.hasRemaining()) {
				rbuf.rewind();
				switch(state) {
				case RX_HEADER:
					plugin.getLogger().log(Level.INFO, "Handling tcp conn header");
					Tag[] tags = Tag.values();
					int itag = rbuf.getInt();
					if (itag >= tags.length)
						throw new IllegalArgumentException("Illegal tag");
					tag = tags[itag];
					int len = rbuf.getInt();
					/* FIXME add length check */
					rbuf = ByteBuffer.allocate(len);
					state = State.RX_PAYLOAD;
					break;
				case RX_PAYLOAD:
					switch (tag) {
					case SUBMIT_ITEMS:
						plugin.getLogger().log(Level.INFO, "Handling SUBMIT_ITEMS");
						ItemMessage msg = ItemMessage.deserialize(plugin, rbuf.array());
						plugin.routeIncomingMessage(msg);
						break;
					case SUBSCRIBE_PORTAL:
					case UNSUBSCRIBE_PORTAL:
						String name = new String(rbuf.array(), rbuf.position(), rbuf.remaining());
						Portal portal = plugin.lookupPortal(name);
						if (portal == null) {
							plugin.getLogger().log(Level.INFO, "Unknown portal", name);
							break;
						}
						if (tag == Tag.SUBSCRIBE_PORTAL) {
							plugin.getLogger().log(Level.INFO, "Handling SUSCRIBE_PORTAL");
							server.registerPortalConn(portal, this);
						} else {
							plugin.getLogger().log(Level.INFO, "Handling UNSUSCRIBE_PORTAL");
							server.unregisterPortalConn(portal, this);
						}
						break;
					}
					rbuf = ByteBuffer.allocate(HEADER_BYTES);
					state = State.RX_HEADER;
					break;
				}
			}
		}
	}
	
	public void run() {
		try {
			plugin.getLogger().log(Level.INFO, "Running UDPCraft TCP server");
			selector = Selector.open();
			ServerSocketChannel sch = ServerSocketChannel.open();
			sch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			sch.bind(addr);
			sch.configureBlocking(false);
			sch.register(selector, sch.validOps(), null);
			
			while(true) {
				plugin.getLogger().log(Level.INFO, "Running UDPCraft TCP server selector");
				selector.select();
				plugin.getLogger().log(Level.INFO, "Processing UDPCraft TCP server events");
				Set<SelectionKey> keys = selector.selectedKeys();
				for (SelectionKey key : keys) {
					if (key.isAcceptable()) {
						plugin.getLogger().log(Level.INFO, "Accepting UDPCraft TCP server connection");
						SocketChannel ch = sch.accept();
						ch.configureBlocking(false);
						Connection conn = new Connection(plugin, this, ch);
						ch.register(selector, SelectionKey.OP_READ/* | SelectionKey.OP_WRITE*/, conn);
					} else if (key.isReadable()) {
						plugin.getLogger().log(Level.INFO, "Reading from UDPCraft TCP server connection");
						try {
							((Connection)key.attachment()).handleRead();
						} catch(IllegalArgumentException ex) {
							plugin.getLogger().log(Level.INFO, "Invalid packet received");
						} catch(IOException ex) {
							plugin.getLogger().log(Level.WARNING, "IOException on rx");
						}
					}/* FIXME else if (key.isWritable()) {
						plugin.getLogger().log(Level.INFO, "Writing to UDPCraft TCP server connection");
						try {
							((Connection)key.attachment()).handleWrite();
						} catch(IOException ex) {
							plugin.getLogger().log(Level.WARNING, "IOException on tx");
						}
					}*/
				}
				keys.clear();
			}
		} catch(IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Unhandled IO exception", ex);
		}
	}
}
