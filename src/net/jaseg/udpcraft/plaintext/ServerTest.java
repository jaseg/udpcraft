package net.jaseg.udpcraft.plaintext;

import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Logger;

import org.bouncycastle.crypto.params.KeyParameter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import net.jaseg.udpcraft.Portal;
import net.jaseg.udpcraft.PortalIndex;
import net.jaseg.udpcraft.PubSubHandler;
import net.jaseg.udpcraft.SignatureDataStore;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ Portal.class, PortalIndex.class, SignatureDataStore.class })
public class ServerTest {

	@Rule
	public Timeout timeout = Timeout.millis(1000);
	
	private Server server;
	private PubSubHandler mux;
	private SignatureDataStore sigstore;
	private PortalIndex index;
	private Portal portal;
	
	@Before
	public void setUp() throws IOException {
		portal = mock(Portal.class);
		when(portal.getPassword()).thenReturn(null);
		index = mock(PortalIndex.class);
		when(index.lookupPortal(null)).thenReturn(portal);
		when(index.lookupPortalOrDie(null)).thenReturn(portal);
		sigstore = mock(SignatureDataStore.class);
		when(sigstore.getSecret()).thenReturn(new KeyParameter("foobar".getBytes()));
		when(sigstore.nextSerial()).thenReturn(1);
		mux = mock(PubSubHandler.class);
		server = new Server(Logger.getAnonymousLogger(), new InetSocketAddress("localhost", 0), "testserver", mux);
	}
	
	@Test
	public void testSetupShutdown() {
		server.start();
		try {
			Thread.sleep(100);
		} catch(InterruptedException ex) {}
		server.stop();
	}
	
	@Test
	public void testConnect() throws IOException {
		server.start();
		Socket s = new Socket();
		s.connect(server.getAddress());

		BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
		assertThat(reader.readLine(), startsWith("220 testserver"));
		
		s.close();
		server.stop();
	}
	
	@Test
	public void testSubscribeUnsubscribe() throws IOException {
		server.start();
		Socket s = new Socket();
		s.connect(server.getAddress());
		
		OutputStream os = s.getOutputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
		assertThat(reader.readLine(), startsWith("220 testserver"));
		
		os.write("SUBSCRIBE testportal\r\n".getBytes());
		try {
			Thread.sleep(100);
		} catch (InterruptedException ex) {}
		assertThat(reader.readLine(), startsWith("250 OK"));
		verify(mux).subscribe(eq("testportal"), (String)isNull(), any());

		os.write("UNSUBSCRIBE testportal\r\n".getBytes());
		try {
			Thread.sleep(100);
		} catch (InterruptedException ex) {}
		assertThat(reader.readLine(), startsWith("250 OK"));
		verify(mux).unsubscribe(eq("testportal"), any());

		os.write("SUBSCRIBE testportal secretpassword\r\n".getBytes());
		try {
			Thread.sleep(100);
		} catch (InterruptedException ex) {}
		assertThat(reader.readLine(), startsWith("250 OK"));
		verify(mux).subscribe(eq("testportal"), eq("secretpassword"), any());
		
		s.close();
		server.stop();
	}
	
	@Test
	public void testRogueUnsubscribe() throws IOException {
		server.start();
		Socket s = new Socket();
		s.connect(server.getAddress());
		
		OutputStream os = s.getOutputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
		assertTrue(reader.readLine().startsWith("220 testserver"));
		
		Mockito.doThrow(new IllegalArgumentException("test")).when(mux).unsubscribe(any(), any());

		os.write("UNSUBSCRIBE testportal\r\n".getBytes());
		try {
			Thread.sleep(100);
		} catch (InterruptedException ex) {}
		assertThat(reader.readLine(), startsWith("550 "));
		verify(mux, times(1)).unsubscribe(eq("testportal"), any());
		
		s.close();
		server.stop();
	}
}
