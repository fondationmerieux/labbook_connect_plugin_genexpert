#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import socket

# ASTM control characters
ENQ = 0x05
ACK = 0x06
NAK = 0x15
EOT = 0x04
STX = 0x02
ETX = 0x03
CR = 0x0D
LF = 0x0A

DEFAULT_SPECIMEN_ID = "5928D"

ASTM_LINES_HBV = [
    "H|@^\\|GXM-17315433330||SN 846682 Labo test^GeneXpert^6.2|||||GX_01||P|1394-97|20250809160651",
    "P|1|||CQE UK NEQAS 20-11-2025|^^^^|||||||||||||||||||||||||||||",
    "O|1|5928D||^^^hbv_test|R|20251120105041|||||||||ORH||||||||||F",
    "R|1|^^^hbv_test^Xpert HBV Viral Load^1^^|^3078.31|IU/mL|10.00 to...114709|Cepheid-1F23904^846682^913994^940366684^19902^20260111|",
    "R|2|^^^hbv_test^Xpert HBV Viral Load^1^^LOG|^3.49|IU/mL|1.00 to ...114709|Cepheid-1F23904^846682^913994^940366684^19902^20260111|",
    "R|3|^^^hbv_test^^^HBV^|POS^|||",
    "R|4|^^^hbv_test^^^HBV^Ct|^27.9|||",
    "R|5|^^^hbv_test^^^HBV^EndPt|^568.0|||",
    "R|6|^^^hbv_test^^^HBV^Delta Ct|^-2.8|||",
    "R|7|^^^hbv_test^^^IQS-H^|PASS^|||",
    "R|8|^^^hbv_test^^^IQS-H^Ct|^20.3|||",
    "R|9|^^^hbv_test^^^IQS-H^EndPt|^285.0|||",
    "R|10|^^^hbv_test^^^IQS-L^|PASS^|||",
    "R|11|^^^hbv_test^^^IQS-L^Ct|^30.8|||",
    "R|12|^^^hbv_test^^^IQS-L^EndPt|^457.0|||",
    "L|1|N",
]

ASTM_MESSAGE_TEMPLATE = "\r".join(ASTM_LINES_HBV) + "\r"


def build_astm_frame(frame_no: int, payload: str) -> bytes:
    """Build one ASTM E1381 frame (single frame, no ETB)."""
    body = str(frame_no).encode("ascii") + payload.encode("ascii")
    checksum = (sum(body) + ETX) & 0xFF
    chk_str = f"{checksum:02X}".encode("ascii")
    return bytes([STX]) + body + bytes([ETX]) + chk_str + bytes([CR, LF])


def printable(b: int) -> str:
    """Return readable representation of a control byte."""
    if 32 <= b <= 126:
        return "'" + chr(b) + "'"
    mapping = {
        STX: "STX",
        ETX: "ETX",
        EOT: "EOT",
        ENQ: "ENQ",
        ACK: "ACK",
        NAK: "NAK",
        CR: "CR",
        LF: "LF",
    }
    return mapping.get(b, f"0x{b:02X}")


def send_astm_message(sock: socket.socket, message: str) -> None:
    """Send ASTM message (one frame) to server plugin."""
    sock.settimeout(10)

    print(">>> ENQ")
    sock.sendall(bytes([ENQ]))
    resp = sock.recv(1)
    if not resp:
        raise RuntimeError("No response after ENQ")
    print("<<<", printable(resp[0]))
    if resp[0] != ACK:
        raise RuntimeError(f"Expected ACK after ENQ, got {printable(resp[0])}")

    frame = build_astm_frame(1, message)
    print(">>> frame #1 (len=%d)" % len(frame))
    sock.sendall(frame)

    resp = sock.recv(1)
    if not resp:
        raise RuntimeError("No response after frame")
    print("<<<", printable(resp[0]))
    if resp[0] != ACK:
        raise RuntimeError(f"Expected ACK after frame, got {printable(resp[0])}")

    print(">>> EOT")
    sock.sendall(bytes([EOT]))


def receive_astm_response(sock: socket.socket) -> str:
    """Receive ASTM response from server plugin (turnaround message)."""
    sock.settimeout(10)

    print("Waiting for response ENQ from server...")
    try:
        b = sock.recv(1)
    except socket.timeout:
        print("No ENQ from server (timeout).")
        return ""
    if not b:
        print("Server closed connection before ENQ.")
        return ""

    print("<<<", printable(b[0]))
    if b[0] != ENQ:
        print("Unexpected byte instead of ENQ, aborting.")
        return ""

    print(">>> ACK")
    sock.sendall(bytes([ACK]))

    assembled = bytearray()

    while True:
        b = sock.recv(1)
        if not b:
            print("Server closed connection during response.")
            break

        if b[0] == EOT:
            print("<<< EOT (response complete)")
            break

        if b[0] != STX:
            print("Unexpected byte while waiting for STX/EOT:", printable(b[0]))
            continue

        frame_no_byte = sock.recv(1)
        if not frame_no_byte:
            raise RuntimeError("Stream closed after STX")
        frame_no = frame_no_byte[0]

        payload = bytearray()
        while True:
            c = sock.recv(1)
            if not c:
                raise RuntimeError("Stream closed inside frame")
            if c[0] in (ETX,):
                terminator = c[0]
                break
            payload.append(c[0])

        trailer = sock.recv(4)
        if len(trailer) < 4:
            raise RuntimeError("Incomplete trailer in response frame")

        c1, c2, cr, lf = trailer
        if cr != CR or lf != LF:
            raise RuntimeError("Invalid CR/LF in response frame trailer")

        received_chk = int(chr(c1) + chr(c2), 16)
        sum_body = (frame_no + sum(payload) + terminator) & 0xFF

        if sum_body != received_chk:
            print("Checksum mismatch in response frame, sending NAK")
            sock.sendall(bytes([NAK]))
            continue
        else:
            sock.sendall(bytes([ACK]))

        assembled.extend(payload)

    if not assembled:
        return ""

    msg = assembled.decode("ascii").replace("\r\n", "\r").strip()
    return msg


def main() -> None:
    parser = argparse.ArgumentParser(
        description="ASTM E1381 client to test GeneXpert LAB-29 (HBV) against LabBook Connect."
    )
    parser.add_argument("--host", default="127.0.0.1", help="Host of the Java plugin")
    parser.add_argument("--port", type=int, default=7501, help="Port of the Java plugin")
    parser.add_argument(
        "--specimen",
        default=DEFAULT_SPECIMEN_ID,
        help="Specimen ID to use inside O|1|<specimen> (default: 5928D)",
    )
    args = parser.parse_args()

    # Build message with specimen replacement
    message = ASTM_MESSAGE_TEMPLATE.replace(DEFAULT_SPECIMEN_ID, args.specimen)

    print("Using specimen ID:", args.specimen)
    print(f"Connecting to {args.host}:{args.port}...")

    try:
        with socket.create_connection((args.host, args.port), timeout=10) as sock:
            print("TCP connection established.")
            print("Sending ASTM message...")
            send_astm_message(sock, message)
            print("ASTM message sent. Waiting for ASTM response...")
            response = receive_astm_response(sock)
    except Exception as e:
        print("ERROR during communication:", repr(e))
        return

    if response:
        print("\n=== ASTM response from plugin ===")
        print(response.replace("\r", "\n"))
    else:
        print("\nNo ASTM response received (empty or none).")


if __name__ == "__main__":
    main()
