package net.jaseg.udpcraft.plaintext;

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

import net.jaseg.udpcraft.ItemListener;
import net.jaseg.udpcraft.ItemMessage;
import net.jaseg.udpcraft.Portal;
import net.jaseg.udpcraft.UDPCraftPlugin;

public class TCPPlaintextServer implements Runnable, ItemListener {
	private UDPCraftPlugin plugin;
	private Thread listener;
	private InetSocketAddress addr;
	private Map<Portal, Queue<Connection>> portalConns = new HashMap<Portal, Queue<Connection>>();
	private Selector selector;
	private boolean exitListener = false;
	
	public TCPPlaintextServer(UDPCraftPlugin plugin, int port, InetAddress addr) throws SocketException {
		this.plugin = plugin;
		this.addr = new InetSocketAddress(addr, port);
		plugin.getLogger().log(Level.INFO, "Starting UDPCraft listener on port "+Integer.toString(port));
		this.listener = new Thread(this);
		this.listener.start();
	}
	
	public void close() {
		exitListener = true;
		if (selector != null)
			selector.wakeup();
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
			selector.wakeup();
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
		private SocketChannel ch;
		private ByteBuffer rbuf = ByteBuffer.allocate(4096);
		private Queue<ItemMessage> msgs = new LinkedList<ItemMessage>();
		private ByteBuffer wbuf = ByteBuffer.allocate(0);
		
		private UDPCraftPlugin plugin;
		private TCPPlaintextServer server;
		
		public Connection(UDPCraftPlugin plugin, TCPPlaintextServer server, SocketChannel ch) {
			this.plugin = plugin;
			this.server = server;
			this.ch = ch;
		}
		
		public void enqueueMessage(ItemMessage msg) {
			plugin.getLogger().log(Level.INFO, "Queueing message");
			msgs.add(msg);
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
			int oldpos = rbuf.position();
			if (ch.read(rbuf) != 0) {
				int newline = new String(rbuf.array(), oldpos, rbuf.position()-oldpos).indexOf('\n');
				if (newline >= 0) {
					
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
			
			while(!exitListener) {
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
					} else if (key.isWritable()) {
						plugin.getLogger().log(Level.INFO, "Writing to UDPCraft TCP server connection");
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

		for (SelectionKey key : selector.keys()) {
			try {
				key.channel().close();
			} catch (IOException ex) {
				plugin.getLogger().log(Level.WARNING, "Error closing channel", ex);
			}
		}
	}
}
