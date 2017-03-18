package net.jaseg.udpcraft;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

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
	
	public synchronized void subscribe(String name, ItemListener conn) throws IllegalArgumentException {
		Portal portal = index.lookupPortalOrDie(name);
		if (!portalConns.containsKey(portal))
			portalConns.put(portal, new LinkedList<ItemListener>());
		Queue<ItemListener> conns = portalConns.get(portal);
		if (conns.contains(conn))
			throw new IllegalArgumentException("Portal was already subscribed");
		conns.add(conn);
	}
	
	public synchronized void unsubscribe(String name, ItemListener conn) throws IllegalArgumentException {
		Portal portal = index.lookupPortalOrDie(name);
		Queue<ItemListener> queue = portalConns.get(portal);
		if (!queue.remove(conn))
			throw new IllegalArgumentException("Portal was not subscribed");
		if (queue.isEmpty())
			portalConns.remove(portal);
	}
	
	public void emitMessage(Portal portal, ItemMessage msg) throws IllegalArgumentException {
		logger.log(Level.INFO, "Routing message from "+portal.getName());
		if (portalConns.containsKey(portal)) {
			logger.log(Level.INFO, "Handling message TCP transport on connection");
			portal.ackMessage(msg);
			for (ItemListener conn : portalConns.get(portal)) {
				logger.log(Level.INFO, conn.toString());
				conn.emitMessage(portal, msg);
			}
		} else {
			logger.log(Level.INFO, "No handlers found");
		}
	}
	
	public void submit(String name, byte msg[]) throws IllegalArgumentException {
		Portal portal = index.lookupPortalOrDie(name);
		portal.receiveMessage(ItemMessage.deserialize(logger, sigstore, msg));
	}
}