package net.jaseg.udpcraft.plaintext;

import com.sun.org.apache.xml.internal.security.exceptions.Base64DecodingException;
import com.sun.org.apache.xml.internal.security.utils.Base64;

import net.jaseg.udpcraft.ItemListener;
import net.jaseg.udpcraft.ItemMessage;
import net.jaseg.udpcraft.Portal;
import net.jaseg.udpcraft.PubSubHandler;

public class SMTPHandler implements LineBufferThing.LineHandler, ItemListener {
	public interface ConnectionHandler {
		void close();
		void reply(String r);
	}

	private ConnectionHandler ch;
	private PubSubHandler pubsub;
	
	public SMTPHandler(ConnectionHandler ch, PubSubHandler pubsub) {
		this.ch = ch;
		this.pubsub = pubsub;
	}
	
	public boolean emitMessage(Portal portal, ItemMessage msg) {
		synchronized (ch) {
			ch.reply("ITEM "+portal.getName()+" "+Base64.encode(msg.serialize())+"\r\n");
		}
		return true;
	}
	
	public void handleLine(String line) {
		synchronized (ch) {
			try {
				String [] args = line.trim().split(" ");
				if (args[0].equals("SUBSCRIBE")) {
					if (args.length == 2)
						pubsub.subscribe(args[1], null, this);
					else if (args.length == 3)
						pubsub.subscribe(args[1], args[2], this);
					else throw new IllegalArgumentException("Invalid number of arguments");
				} else if (args[0].equals("UNSUBSCRIBE")) {
					if (args.length != 2)
						throw new IllegalArgumentException("Invalid number of arguments");
					pubsub.unsubscribe(args[1], this);
				} else if (args[0].equals("SUBMIT")) {
					if (args.length != 3)
						throw new IllegalArgumentException("Invalid number of arguments "+args.length);
					try {
						pubsub.submit(args[1], Base64.decode(args[2]));
					} catch (Base64DecodingException ex) {
						throw new IllegalArgumentException("Invalid Base64-encoded message");
					}
				} else if (args[0].equals("QUIT")) {
					ch.close();
					return;
				} else {
					ch.reply("550 Command not supported\r\n");
					return;
				}
			} catch (IllegalArgumentException ex) {
				ch.reply("550 Invalid arguments: "+ex.toString()+"\r\n");
				return;
			}
			ch.reply("250 OK\r\n");
		}
	}
}
