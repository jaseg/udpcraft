package net.jaseg.udpcraft;

import org.bouncycastle.crypto.params.KeyParameter;

public interface SignatureDataStore {
	public KeyParameter getSecret();
	public int nextSerial();
	public void voidSerial(int serial) throws IllegalArgumentException;
}
