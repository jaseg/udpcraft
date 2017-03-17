package net.jaseg.udpcraft;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.logging.Level;

public class UDPCraftServer implements Runnable{
	private static final int MAX_PKT_LEN = 65536;
	private UDPCraftPlugin plugin;
	private DatagramSocket socket;
	private Thread listener;
	
	public UDPCraftServer(UDPCraftPlugin plugin, int port, InetAddress host) throws SocketException {
		this.plugin = plugin;
		this.socket = new DatagramSocket(port, host);
		this.socket.setReuseAddress(true);
		this.listener = new Thread(this);
		this.listener.start();
	}
	
	public void close() {
		listener.stop();
		socket.close();
	}
	
	public void transmit(InetAddress target, int port, ByteBuffer buf) throws IOException{
		DatagramPacket pkt = new DatagramPacket(buf.array(), buf.capacity());
		pkt.setAddress(target);
		pkt.setPort(port);
		socket.send(pkt);
	}
	
	public void run() {
		byte buf[] = new byte[MAX_PKT_LEN];
		DatagramPacket rpack = new DatagramPacket(buf, buf.length);
		
		while(true) {
			try { 
				socket.receive(rpack);
				
				plugin.parseMessage(ByteBuffer.wrap(rpack.getData()));
			} catch(IllegalArgumentException ex) {
				plugin.getLogger().log(Level.INFO, "Invalid packet received");
			} catch(IOException ex) {
				plugin.getLogger().log(Level.WARNING, "IOException on rx socket");
			}
		}
	}
}
