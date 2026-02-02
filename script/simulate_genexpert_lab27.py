import socket
import time

ENQ = b'\x05'
ACK = b'\x06'
NAK = b'\x15'
EOT = b'\x04'
STX = b'\x02'
ETX = b'\x03'
CR = b'\x0D'
LF = b'\x0A'

HOST = '0.0.0.0'
PORT = 12345

astm_query = [
    "H|\\^&|||GeneXpert^4.7||||||Q|1394-97|20250715120000",
    "Q|1|ALL",
    "L|1|F"
]

def calculate_checksum(frame_bytes):
    cs = 0
    for b in frame_bytes[1:]:  # Skip STX
        cs += b
    return f"{cs & 0xFF:02X}"

def send_astm_query(conn):
    print("[SEND] ENQ")
    conn.sendall(ENQ)
    resp = conn.recv(1)
    if resp != ACK:
        print(f"[ERR] Did not receive ACK after ENQ, got {resp}")
        return

    for i, line in enumerate(astm_query):
        frame_text = f"{(i+1)%8}{line}"
        body = frame_text.encode('ascii')
        frame = bytearray()
        frame.append(STX[0])
        frame.extend(body)
        frame.append(ETX[0])
        cs = calculate_checksum(frame)
        frame.extend(cs.encode('ascii'))
        frame.append(CR[0])
        frame.append(LF[0])

        print(f"[SEND] Frame {i+1}: {line}")
        conn.sendall(frame)
        resp = conn.recv(1)
        if resp != ACK:
            print(f"[ERR] Frame {i+1} not ACKed. Got: {resp}")
            return
        time.sleep(0.1)  # Simulate delay between frames

    print("[SEND] EOT")
    conn.sendall(EOT)
    
    # Receive response from plugin (optional)
    data = conn.recv(4096)
    print("[RECV] Response from plugin:")
    print(data.decode('ascii', errors='replace').replace('\r', '\n'))

def main():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((HOST, PORT))
        server.listen(1)
        print(f"[INFO] Waiting for plugin (as client) on port {PORT}...")

        conn, addr = server.accept()
        with conn:
            print(f"[INFO] Plugin connected from {addr}")
            send_astm_query(conn)
            print("[INFO] LAB-27 simulation complete.")

if __name__ == '__main__':
    main()

