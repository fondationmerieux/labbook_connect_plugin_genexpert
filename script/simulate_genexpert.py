#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GeneXpert instrument simulator for the LabBook Connect plugin.

The plugin listens (analyzer_genexpert.toml, [analyzer.socket] mode = "server"),
so this script connects to it as the instrument does.

It speaks ASTM E1381 as described in the GeneXpert LIS Interface Protocol
Specification, 302-2261 Rev. F (2023-06):

  Establishment  ENQ -> ACK                              (section 3.2.2)
  Transfer       <STX> FN Text <ETB|ETX> C1 C2 <CR> <LF> (section 3.2.3.1)
  Termination    EOT                                     (section 3.2.4)

Frames carry at most 240 bytes of message text. Every record inside the text is
terminated by CR. All frames but the last end with ETB, the last one with ETX.

Usage:
    python3 simulate_genexpert.py --host 127.0.0.1 --port 7500 --scenario query-all
    python3 simulate_genexpert.py --scenario query-specimen --specimen SID-888
    python3 simulate_genexpert.py --scenario cancel
    python3 simulate_genexpert.py --scenario results
    python3 simulate_genexpert.py --scenario query-all --nak-frame 1
"""

import argparse
import socket
import sys
import time

ENQ = 0x05
ACK = 0x06
NAK = 0x15
EOT = 0x04
STX = 0x02
ETX = 0x03
ETB = 0x17
CR = 0x0D
LF = 0x0A

NAMES = {ENQ: "ENQ", ACK: "ACK", NAK: "NAK", EOT: "EOT", STX: "STX"}

MAX_FRAME_TEXT = 240     # section 3.2.3.1
REPLY_TIMEOUT = 15       # section 3.2.5.2
RECEIVE_TIMEOUT = 30     # section 3.2.5.2


# Xpert Carba-R targets, as named by vendor_result_code in mapping_genexpert.toml.
# Values are the ones the LIS accepts for a positive/negative variable, "+" or "-".
CARBA_TARGETS = [
    ("^carba_v2^^imp1^Xpert Carba-R^2^IMP1^", "-"),
    ("^carba_v2^^kpc^Xpert Carba-R^2^KPC^", "-"),
    ("^carba_v2^^ndm^Xpert Carba-R^2^NDM^", "+"),
    ("^carba_v2^^oxa48^Xpert Carba-R^2^OXA48^", "-"),
    ("^carba_v2^^vim^Xpert Carba-R^2^VIM^", "-"),
]


def now():
    return time.strftime("%Y%m%d%H%M%S")


# --------------------------------------------------------------------------
# Messages
# --------------------------------------------------------------------------

def header():
    """H record with the delimiters GeneXpert actually uses (sections 3.2.7, 6.3)."""
    return "H|@^\\|" + now() + "||GeneXpert PC^GeneXpert^6.1|||||LIS||P|1394-97|" + now()


def build_message(scenario, specimen):
    """Return the ASTM records for the requested scenario."""
    if scenario == "query-all":
        # Section 6.3.1.1, instrument queries for all pending orders
        return [header(), "Q|1|ALL||||||||||O@N", "L|1|N"]

    if scenario == "query-specimen":
        # Section 6.3.2, Q-3 is Patient ID 1 ^ Specimen ID ^ Patient ID 2
        return [header(), "Q|1|^" + specimen + "||||||||||O@N", "L|1|N"]

    if scenario == "query-patient-specimen":
        return [header(), "Q|1|PatientID-556^" + specimen + "||||||||||O@N", "L|1|N"]

    if scenario == "cancel":
        # Section 6.3.1.2, cancellation carries A in Q-13
        return [header(),
                "Q|1|||||||||||A",
                "C|1|I|timeout^last request has been cancelled|I",
                "L|1|N"]

    if scenario == "results":
        # Section 6.3.4, result upload. The test and target codes must match the
        # vendor_test_code and vendor_result_code entries of the mapping file,
        # otherwise the plugin cannot translate them for the LIS.
        records = [header(),
                   "P|1",
                   "O|1|" + specimen + "||^^^carba_v2|R|" + now() +
                   "|||||C||||ORH||||||||||F"]

        for index, (target, value) in enumerate(CARBA_TARGETS, start=1):
            records.append("R|%d|%s|%s||||||F||%s" % (index, target, value, now()))

        records.append("L|1|N")
        return records

    raise SystemExit("unknown scenario: " + scenario)


# --------------------------------------------------------------------------
# Framing
# --------------------------------------------------------------------------

def checksum(frame_no, text, terminator):
    """Sum of frame number, text and terminator, modulo 256 (section 3.2.3.1)."""
    total = ord(str(frame_no))
    for ch in text.encode("ascii", "replace"):
        total += ch
    total += terminator
    return "%02X" % (total & 0xFF)


def split_frames(records):
    """Assemble the records then cut the text into slices of MAX_FRAME_TEXT bytes."""
    text = "".join(r + "\r" for r in records)
    return [text[i:i + MAX_FRAME_TEXT] for i in range(0, len(text), MAX_FRAME_TEXT)] or [""]


def encode_frame(frame_no, chunk, last):
    terminator = ETX if last else ETB
    out = bytearray([STX])
    out.extend(str(frame_no).encode("ascii"))
    out.extend(chunk.encode("ascii", "replace"))
    out.append(terminator)
    out.extend(checksum(frame_no, chunk, terminator).encode("ascii"))
    out.append(CR)
    out.append(LF)
    return bytes(out)


# --------------------------------------------------------------------------
# Link
# --------------------------------------------------------------------------

class Link:
    def __init__(self, sock, verbose):
        self.sock = sock
        self.verbose = verbose

    def send_byte(self, value):
        print("  >>> %s" % NAMES.get(value, hex(value)))
        self.sock.sendall(bytes([value]))

    def read_byte(self, timeout):
        self.sock.settimeout(timeout)
        data = self.sock.recv(1)
        if not data:
            raise ConnectionError("connection closed by the plugin")
        value = data[0]
        print("  <<< %s" % NAMES.get(value, repr(chr(value))))
        return value

    # -- sending ----------------------------------------------------------

    def send_message(self, records, nak_frame=None):
        """Establishment, transfer, termination. Returns True when fully accepted."""
        print("\n[TRANSFER] sending %d record(s)" % len(records))
        for r in records:
            print("    %s" % r)

        self.send_byte(ENQ)
        reply = self.read_byte(REPLY_TIMEOUT)
        if reply != ACK:
            print("  [FAIL] expected ACK after ENQ")
            return False

        chunks = split_frames(records)
        print("  [INFO] %d frame(s) of at most %d bytes" % (len(chunks), MAX_FRAME_TEXT))

        frame_no = 1
        for index, chunk in enumerate(chunks):
            last = (index == len(chunks) - 1)
            frame = encode_frame(frame_no, chunk, last)
            print("  >>> frame %d (%d bytes, %s)"
                  % (frame_no, len(chunk), "ETX" if last else "ETB"))
            if self.verbose:
                print("      %r" % frame)
            self.sock.sendall(frame)

            reply = self.read_byte(REPLY_TIMEOUT)
            if reply != ACK:
                print("  [FAIL] frame %d was not accepted" % frame_no)
                self.send_byte(EOT)
                return False
            frame_no = (frame_no + 1) % 8

        self.send_byte(EOT)
        return True

    # -- receiving --------------------------------------------------------

    def receive_message(self, nak_frame=None):
        """Wait for the plugin to send a message. Returns the reassembled text."""
        print("\n[RECEIVE] waiting for the plugin")
        try:
            first = self.read_byte(RECEIVE_TIMEOUT)
        except socket.timeout:
            print("  [INFO] nothing received within %d s" % RECEIVE_TIMEOUT)
            return None

        if first != ENQ:
            print("  [FAIL] expected ENQ, got %s" % hex(first))
            return None

        self.send_byte(ACK)

        text = ""
        expected = 1
        naked = set()

        while True:
            byte = self.read_byte(RECEIVE_TIMEOUT)

            if byte == EOT:
                print("  [INFO] transfer finished")
                break

            if byte != STX:
                print("  [WARN] expected STX, got %s, ignored" % hex(byte))
                continue

            frame_no = int(chr(self.sock.recv(1)[0]))

            payload = bytearray()
            while True:
                b = self.sock.recv(1)[0]
                if b in (ETX, ETB):
                    terminator = b
                    break
                payload.append(b)

            raw_checksum = self.sock.recv(2).decode("ascii", "replace")
            self.sock.recv(2)   # CR LF

            body = payload.decode("ascii", "replace")
            wanted = checksum(frame_no, body, terminator)
            ok = (wanted.upper() == raw_checksum.upper())

            print("  <<< frame %d (%d bytes, %s, checksum %s %s)"
                  % (frame_no, len(body), "ETX" if terminator == ETX else "ETB",
                     raw_checksum, "ok" if ok else "expected " + wanted))
            if self.verbose:
                print("      %r" % body)

            if frame_no != expected:
                print("  [WARN] frame number %d received, %d expected" % (frame_no, expected))

            # forced rejection, to check the plugin re-sends the same frame number
            if nak_frame is not None and frame_no == nak_frame and frame_no not in naked:
                naked.add(frame_no)
                print("  [TEST] rejecting frame %d once" % frame_no)
                self.send_byte(NAK)
                continue

            if not ok:
                self.send_byte(NAK)
                continue

            text += body
            self.send_byte(ACK)
            expected = (frame_no + 1) % 8

        return text


# --------------------------------------------------------------------------
# Checks on the reply
# --------------------------------------------------------------------------

def report(text):
    if not text:
        print("\n[RESULT] the plugin sent nothing")
        return

    records = [r for r in text.replace("\r\n", "\r").split("\r") if r]
    print("\n[RESULT] %d record(s) received" % len(records))
    for r in records:
        print("    %s" % r)

    orders = [r for r in records if r.startswith("O|")]
    patients = [r for r in records if r.startswith("P|")]
    terminators = [r for r in records if r.startswith("L|")]

    print("\n[CHECK] %d patient record(s), %d order record(s)" % (len(patients), len(orders)))

    for line in terminators:
        fields = line.split("|")
        code = fields[2] if len(fields) > 2 else ""
        if code == "F" and not orders:
            print("[CHECK] L-3 = F while no order was sent, expected I (section 6.3.1)")
        elif code == "I" and orders:
            print("[CHECK] L-3 = I while orders were sent, expected F (section 6.3.1)")
        else:
            print("[CHECK] L-3 = %s, consistent with %d order(s)" % (code, len(orders)))

    header_records = [r for r in records if r.startswith("H|")]
    for line in header_records:
        delimiters = line.split("|")[1] if "|" in line else ""
        if delimiters != "@^\\":
            print("[CHECK] header delimiters are %r, GeneXpert uses '@^\\\\'" % delimiters)
        else:
            print("[CHECK] header delimiters are the ones GeneXpert uses")


# --------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=7500)
    parser.add_argument("--scenario", default="query-all",
                        choices=["query-all", "query-specimen", "query-patient-specimen",
                                 "cancel", "results"])
    parser.add_argument("--specimen", default="SID-888")
    parser.add_argument("--nak-frame", type=int, default=None,
                        help="reject this frame once when receiving, to check retransmission")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    records = build_message(args.scenario, args.specimen)

    print("[INFO] connecting to %s:%d" % (args.host, args.port))
    with socket.create_connection((args.host, args.port), timeout=REPLY_TIMEOUT) as sock:
        link = Link(sock, args.verbose)

        if not link.send_message(records):
            return 1

        if args.scenario == "cancel":
            print("\n[INFO] a cancellation expects no order in return (section 6.3.1.2)")

        text = link.receive_message(nak_frame=args.nak_frame)
        report(text)

        if args.scenario == "cancel" and text:
            print("[CHECK] the plugin answered a cancellation, it should stay silent")

    return 0


if __name__ == "__main__":
    sys.exit(main())
