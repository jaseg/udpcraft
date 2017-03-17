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
		if (portalConns.containsKey(portal)) {
			portal.ackMessage(msg);
			for (Connection conn : portalConns.get(portal)) {
				conn.enqueueMessage(msg);
			}
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
		private ByteBuffer rbuf = ByteBuffer.allocate(16);
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
			msgs.add(msg);
			try {
				ch.write(ByteBuffer.allocate(0));
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
			ch.read(new ByteBuffer[] { rbuf }, 0, rbuf.remaining());
			if (!rbuf.hasRemaining()) {
				switch(state) {
				case RX_HEADER:
					Tag[] tags = Tag.values();
					int itag = rbuf.getInt();
					if (itag >= tags.length)
						throw new IllegalArgumentException("Illegal tag");
					tag = tags[rbuf.getInt()];
					int len = rbuf.getInt();
					rbuf = ByteBuffer.allocate(len);
					state = State.RX_PAYLOAD;
					break;
				case RX_PAYLOAD:
					switch (tag) {
					case SUBMIT_ITEMS:
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
						if (tag == Tag.SUBSCRIBE_PORTAL)
							server.registerPortalConn(portal, this);
						else
							server.unregisterPortalConn(portal, this);
						break;
					}
					rbuf = ByteBuffer.allocate(16);
					break;
				}
			}
		}
	}
	
	public void run() {
		try {
			selector = Selector.open();
			ServerSocketChannel sch = ServerSocketChannel.open();
			sch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			sch.bind(addr);
			sch.configureBlocking(false);
			sch.register(selector, sch.validOps(), null);
			
			while(true) {
				selector.select();
				Set<SelectionKey> keys = selector.selectedKeys();
				for (SelectionKey key : keys) {
					if (key.isAcceptable()) {
						SocketChannel ch = sch.accept();
						ch.configureBlocking(false);
						Connection conn = new Connection(plugin, this, ch);
						ch.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, conn);
					} else if (key.isReadable()) {
						try {
							((Connection)key.attachment()).handleRead();
						} catch(IllegalArgumentException ex) {
							plugin.getLogger().log(Level.INFO, "Invalid packet received");
						} catch(IOException ex) {
							plugin.getLogger().log(Level.WARNING, "IOException on rx");
						}
					} else if (key.isWritable()) {
						try {
							((Connection)key.attachment()).handleWrite();
						} catch(IOException ex) {
							plugin.getLogger().log(Level.WARNING, "IOException on tx");
						}
					}
				}
				keys.clear();
			}
		} catch(IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Unhandled IO exception", ex);
		}
	}
}
