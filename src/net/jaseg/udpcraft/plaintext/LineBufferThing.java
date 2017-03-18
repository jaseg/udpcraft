package net.jaseg.udpcraft.plaintext;

import java.io.*;
import java.nio.*;

/* From the book "Things you definitely don't want to do with java, 7th revised and expanded edition */
public class LineBufferThing {
	private enum State {CR, LF};
	private State st = State.CR;
	private StringBuilder sb = new StringBuilder();

	public interface LineHandler {
		void handleLine(String line);
	}

	private LineHandler lh;

	public LineBufferThing(LineHandler lh) {
		this.lh = lh;
	}

	public void readLine(ByteBuffer cb) throws IOException {
		/* Calls the registered handler for every line in cb */
		cb.flip();
		while (cb.hasRemaining()) {
			int i = cb.get();
			if (i < 0 || i > 127)
				throw new IOException("Read the spec, bro");
			char c = (char) i;

			if (st == State.CR) {
				if (c == '\r')
					st = State.LF;
				else
					sb.append(c);
			} else { /* state == LF */
				st = State.CR;
				if (c == '\n') {
					String s = sb.toString();
					sb = new StringBuilder();
					lh.handleLine(s);
				}
			}
		}
	}
}
