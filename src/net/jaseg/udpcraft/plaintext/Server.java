import java.io.*;
import java.nio.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Server implements Runnable {

	public static final int MAX_LINE_LEN = 512; /* see spec */

	private ServerSocketChannel sch;
	private Selector sel;

	private HashMap<SocketChannel, LineBufferThing> map = new HashMap<SocketChannel, LineBufferThing>();

	private Logger logger;
	private String name;
	private boolean shouldStop = false;

	public Server(Logger logger, InetSocketAddress addr, String name) throws IOException {
		sel = Selector.open();
		sch = ServerSocketChannel.open();
		sch.configureBlocking(false);
		sch.socket().bind(addr);
		sch.register(sel, SelectionKey.OP_ACCEPT);

		this.logger = logger;
		this.name = name;
	}
	
	public void interrupt() {
		shouldStop = true;
		sel.wakeup();
	}

	public void run() {
		ByteBuffer fnord = ByteBuffer.allocate(MAX_LINE_LEN);
		CharBuffer foo = fnord.asCharBuffer();
		while (true) {
			try { 
				sel.select();

				Iterator<SelectionKey> it=sel.selectedKeys().iterator();
				while (it.hasNext()) {
					final SelectionKey narf = it.next();
					if (narf.isAcceptable()) {
						logger.log(Level.INFO, "Accepting connection");
						final SocketChannel ch = sch.accept(); /* We're only listening on one socket */
						ch.configureBlocking(false);
						ch.register(sel, SelectionKey.OP_READ);

						SMTPHandler.ConnectionHandler hnd = new SMTPHandler.ConnectionHandler() {
							public void reply(String r) {
								try {
									ByteBuffer frob = ByteBuffer.wrap(r.getBytes());
									ch.write(frob);
								} catch (IOException ex) {
									try {
										ch.close();
									} catch (IOException ex1) {}
									narf.cancel();
								}
							}
							public void close() {
								try {
									ByteBuffer frob = ByteBuffer.wrap("221 Service closing transmission channel\r\n".getBytes());
									ch.write(frob);
								} catch (IOException ex) {}
								try {
									ch.close();
								} catch (IOException ex) {
								} finally {
									narf.cancel();
								}
							}
						};

						/* Register chain of line segmentation and protocol handling */
						map.put(ch, new LineBufferThing(new SMTPHandler(hnd)));

						/* Welcome our new friend. */
						hnd.reply("220 "+name+" CrappySMTPd\r\n");
					} else if(narf.isReadable()) {
						SocketChannel ch = (SocketChannel)narf.channel();
						try {
							/* And here was I thinking java.io was bad. */
							int nrd = ch.read(fnord);
							if (nrd == -1) {
								logger.log(Level.INFO, "Closing this end of connection closed on other end");
								ch.close();
								narf.cancel();
							}

							/* Call connection line segmentation */
							map.get(ch).readLine(fnord);
						} catch (BufferOverflowException ex) {
							/* Ignore. */
						} catch (IOException ex) {
							ch.close();
							narf.cancel();
						} finally {
							fnord.clear();
						}
					}
					it.remove();
				}
			} catch(IOException ex) {
				logger.log(Level.INFO, "Caught exception: "+ex.toString());
			}
		}
	}
}
