package net.jaseg.udpcraft;

public interface PubSubHandler extends ItemListener {
	void subscribe(String portal, ItemListener listener) throws IllegalArgumentException;
	void unsubscribe(String portal, ItemListener listener) throws IllegalArgumentException;
	void emitMessage(Portal portal, ItemMessage msg) throws IllegalArgumentException;
	void submit(String portal, byte msg[]) throws IllegalArgumentException;
}
