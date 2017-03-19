package net.jaseg.udpcraft;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bouncycastle.util.Arrays;

import net.jaseg.udpcraft.Portal.InvalidLocationException;

public class ConnectionMux implements PubSubHandler { 
	private Map<Portal, Queue<ItemListener>> portalConns = new HashMap<Portal, Queue<ItemListener>>();
	private PortalIndex index;
	private Logger logger;
	private SignatureDataStore sigstore;
	
	public ConnectionMux(Logger logger, PortalIndex index, SignatureDataStore sigstore) {
		this.logger = logger;
		this.index = index;
		this.sigstore = sigstore;
	}
	
	public synchronized void subscribe(String name, String password, ItemListener conn) throws IllegalArgumentException {
		Portal portal = index.lookupPortalOrDie(name);
		if (!portalConns.containsKey(portal))
			portalConns.put(portal, new LinkedList<ItemListener>());
		Queue<ItemListener> conns = portalConns.get(portal);
		if (conns.contains(conn))
			throw new IllegalArgumentException("Portal was already subscribed");
		String ppw = portal.getPassword();
		if (ppw != null && password == null)
			throw new IllegalArgumentException("Passwords do not match");
		if (ppw != null && Arrays.constantTimeAreEqual(password.getBytes(), portal.getPassword().getBytes()))
			throw new IllegalArgumentException("Passwords do not match");
		conns.add(conn);
		portal.queueUpdate();
	}
	
	public synchronized void unsubscribe(String name, ItemListener conn) throws IllegalArgumentException {
		Portal portal = index.lookupPortalOrDie(name);
		Queue<ItemListener> queue = portalConns.get(portal);
		if (!queue.remove(conn))
			throw new IllegalArgumentException("Portal was not subscribed");
		if (queue.isEmpty())
			portalConns.remove(portal);
	}
	
	public synchronized boolean emitMessage(Portal portal, ItemMessage msg) throws IllegalArgumentException {
		logger.log(Level.INFO, "Routing message from "+portal.getName());
		if (!portalConns.containsKey(portal)) {
			logger.log(Level.INFO, "No handlers found");
			return false;
		}
		
		logger.log(Level.INFO, "Handling message TCP transport on connection");
		boolean any = false;
		for (ItemListener conn : portalConns.get(portal)) {
			logger.log(Level.INFO, conn.toString());
			any = any || conn.emitMessage(portal, msg);
		}
		return any;
	}
	
	public void submit(String name, byte msg[]) throws IllegalArgumentException {
		Portal portal = index.lookupPortalOrDie(name);
		try {
			portal.receiveMessage(ItemMessage.deserialize(logger, sigstore, msg));
		} catch(InvalidLocationException ex) {
			throw new IllegalArgumentException("Invalid portal: "+ex.getMessage());
		}
	}
}