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

astm_lines = [
    "H|\\^&|||GeneXpert^4.7||||||P|1394-97|20250623093000",
    "P|1|123456||Doe^John||19800101|M||||123 Main St^^Paris^75000||0123456789",
    "O|1|SP123456||^^^XPRT01^COVID19^4.7^^|SERUM|20250623091500||||||||||||||||||F",
    "R|1|RES01|DETECTED|||||||F",
    "C|Valid result from simulated GeneXpert",
    "L|1|N"
]

def calculate_checksum(frame_bytes):
    cs = 0
    for b in frame_bytes[1:]:  # Skip STX
        cs += b
    return f"{cs & 0xFF:02X}"

def send_astm_message(conn):
    print("[SEND] ENQ")
    conn.sendall(ENQ)
    resp = conn.recv(1)
    if resp != ACK:
        print(f"[ERR] Did not receive ACK after ENQ, got {resp}")
        return

    for i, line in enumerate(astm_lines):
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

def main():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.bind((HOST, PORT))
        server.listen(1)
        print(f"[INFO] Waiting for plugin (as client) on port {PORT}...")

        conn, addr = server.accept()
        with conn:
            print(f"[INFO] Plugin connected from {addr}")
            send_astm_message(conn)
            print("[INFO] LAB-29 simulation complete.")

if __name__ == '__main__':
    main()

