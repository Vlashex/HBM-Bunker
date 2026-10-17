"""Bounded RCON transport for a disposable benchmark; never imported by the director."""
import socket
import struct
import time

class Failure(Exception):
    def __init__(self,state):
        self.state=state

def command(password,text,timeout=45):
    sent=False
    deadline=time.monotonic()+timeout
    try:
        with socket.create_connection(('127.0.0.1',25578),timeout=2) as stream:
            def send(ident,kind,value):
                payload=struct.pack('<ii',ident,kind)+value.encode('utf-8')+b'\0\0'
                stream.sendall(struct.pack('<i',len(payload))+payload)
            def exact(size):
                chunks=b''
                while len(chunks)<size:
                    remaining=deadline-time.monotonic()
                    if remaining<=0:
                        raise TimeoutError()
                    stream.settimeout(remaining)
                    data=stream.recv(size-len(chunks))
                    if not data:
                        raise EOFError()
                    chunks+=data
                return chunks
            def receive():
                size=struct.unpack('<i',exact(4))[0]
                if not 10<=size<=65536:
                    raise ValueError()
                payload=exact(size)
                if payload[-2:]!=b'\0\0':
                    raise ValueError()
                return (*struct.unpack('<ii',payload[:8]),payload[8:-2])
            send(1,3,password)
            ident,kind,_=receive()
            if ident!=1 or kind!=2:
                raise Failure('rejected')
            sent=True
            send(2,2,text)
            ident,kind,payload=receive()
            if ident!=2 or kind!=0:
                raise ValueError()
            result=[payload]
            total=len(payload)
            send(3,0,'')
            for _ in range(128):
                ident,kind,payload=receive()
                if ident==3 and kind==0:
                    return b''.join(result).decode('utf-8',errors='replace')
                if ident!=2 or kind!=0:
                    raise ValueError()
                total+=len(payload)
                if total>262144:
                    raise ValueError()
                result.append(payload)
            raise ValueError()
    except Failure:
        raise
    except (OSError,EOFError,ValueError,struct.error):
        raise Failure('uncertain' if sent else 'unavailable') from None
