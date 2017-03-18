#!/usr/bin/env python3

import hmac
import hashlib
import struct

testdata = b'this is only a test'
testname = b'testportal'
testkey = b'foobar'
testserial = 1

testpayload = struct.pack('>i', testserial) + testdata
signature = hmac.HMAC(testkey, testpayload, hashlib.sha256).digest()

def javadump(bs, blocksize=16):
    lines = []
    for i in range(0, len(bs), blocksize):
        lines.append(', '.join('{: 4}'.format(((bs[j]+128)%256)-128) for j in range(i, i+blocksize) if j<len(bs)))
    print(',\n'.join(lines))

message = bytes([len(testname)]) + testname + signature + testpayload

print('message:', message)
print('payload length:', len(testpayload))
print('javadump:')
javadump(message)
