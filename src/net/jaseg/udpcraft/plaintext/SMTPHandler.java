package net.jaseg.udpcraft.plaintext;

import java.util.*;
import java.lang.reflect.*;
import java.lang.annotation.*;

public class SMTPHandler implements LineBufferThing.LineHandler{
	public interface ConnectionHandler {
		void close();
		void reply(String r);
	}

	private ConnectionHandler ch;
	
	public SMTPHandler(ConnectionHandler ch) {
		this.ch = ch;
	}
	
	public void handleLine(String line) {
		String [] args = line.split(" ");
		if (args[0].equals("SUBSCRIBE")) {
			if (args.length != 2) {
				ch.reply("550 Invalid arguments");
			} else {
				ch.reply("250 OK\r\n");
			}
		} else if (args[0].equals("UNSUBSCRIBE")) {
			if (args.length != 2) {
				ch.reply("550 Invalid arguments");
			} else {
				ch.reply("250 OK\r\n");
			}
		} else if (args[0].equals("SUBMIT")) {
			if (args.length != 3) {
				ch.reply("550 Invalid arguments");
			} else {
				ch.reply("250 OK\r\n");
			}
		} else if (args[0].equals("QUIT")) {
			ch.close();
		} else ch.reply("550 Command not supported\r\n");
	}
}
