# GeneXpert – LabBook Connect plugin

This plugin enables communication between a Cepheid GeneXpert analyzer and LabBook.
The analyzer communicates using ASTM only; HL7 is used exclusively toward the LIS.

## Installation note

This bundle is NOT a ready-to-use directory.

Files must be installed individually, either:
- by copying them manually to their corresponding locations on the server, or
- by uploading them through the LabBook user interface (when supported).

The analyzer setting file is a sample and MUST be edited before use
(network parameters, analyzer ID, URLs).

Do not deploy the bundle as a single directory.

## Communication protocols

- Analyzer ↔ LabBook Connect: ASTM E1381 over TCP socket, as specified in the
  GeneXpert LIS Interface Protocol Specification, 302-2261 Rev. F (2023-06)
- LabBook Connect ↔ LIS: HL7 v2.5.1 (HTTP)

## Supported transactions

- LAB-27 (Query)  
  ASTM Q| (analyzer) → HL7 QBP^Q11 (to LIS)  
  HL7 RSP^K11 (from LIS) → ASTM (to analyzer)

- LAB-28 (Orders)  
  HL7 OML^O33 (from LIS) → ASTM (to analyzer)

- LAB-29 (Results)  
  ASTM (from analyzer) → HL7 OUL^R22 (to LIS)

## Deployment modes

- server (validated, production mode)  
  LabBook Connect listens on a TCP port and waits for the analyzer connection.

- client (experimental)  
  LabBook Connect connects to the analyzer IP/port.  
  Not recommended for production use.

## Configuration files

Two configuration files are required for each GeneXpert analyzer instance:
- one analyzer setting file (connection and routing)
- one mapping file (tests and result mapping)

### 1) Analyzer settings

Location:  
    /storage/resource/connect/analyzer/setting/

Sample file:  
    doc/analyzer_genexpert.toml

Important:
- The operator MUST edit this file before use.
- In server mode, the ip field is ignored.
- In client mode (experimental), the ip field is required.
- Allowed TCP port ranges:
  - 3100-3199
  - 7500-7599
  - 12300-12399

### 2) Mapping file

Location:  
    /storage/resource/connect/analyzer/mapping/

Sample file:  
    doc/mapping_genexpert.toml

Notes:
- Only tests explicitly listed are supported.
- Additional tests and result mappings must be added as needed.

## GeneXpert limitation

The test code sent to GeneXpert (Host Test Code / ASTM O segment, field O|...^^^CODE)
MUST be 15 characters or less.

Longer codes may be accepted by LabBook but cause GeneXpert results
to be received without being correctly matched or displayed.

## Logging

- Logs use the global LabBook Connect logging configuration.
- Low-level ASTM traffic (ENQ, ACK, frames) is logged for diagnostic purposes.

## Testing without an instrument

`script/simulate_genexpert.py` plays the part of the instrument. It connects to the plugin the way a
GeneXpert does, speaks ASTM E1381 in both directions, and reports what the plugin answered.

```bash
python3 script/simulate_genexpert.py --host <connect host> --port <analyzer port> \
        --scenario query-specimen --specimen SAMP-027
```

The port is the one set in the analyzer settings file, not a fixed value.

| Scenario | What the instrument asks | What it checks |
|---|---|---|
| `query-specimen` | order for one specimen | specimen extraction, mapping of the test code |
| `query-patient-specimen` | same order, with the patient identifier in front | that the patient part is ignored |
| `query-all` | every pending order | multi-frame splitting, and that only tests known to the mapping are returned |
| `cancel` | cancellation of the previous query | that nothing is sent back |
| `results` | a Xpert Carba-R result upload | the five targets reach the LIS |

The script also checks the reply and warns when something does not follow the specification: orders
announced when none were sent, header delimiters that are not the ones GeneXpert uses, frame numbers
out of order, wrong checksums.

Two options help during troubleshooting. `--nak-frame N` rejects frame N once, so that the plugin has
to send it again. `--verbose` prints the raw bytes.

The `results` scenario is written for the Xpert Carba-R. Testing another analysis means editing the
codes and values at the top of the script.

## Message archiving

Message archiving is controlled by the `archive_msg` setting in the analyzer configuration file.

When enabled (`archive_msg = "Y"`), raw messages are archived on disk for traceability and diagnostics.

Archived messages are stored per analyzer instance in:
    /storage/resource/connect/analyzer/{id_analyzer}/

Subdirectories:
- archive_lab27 (LAB-27 queries)
- archive_lab28 (LAB-28 orders)
- archive_lab29 (LAB-29 results)

Messages are saved as plain text files.
Filenames include the transaction type, message source (Analyzer or LIS), and a timestamp.

## Limitations

- No automatic frame retransmission on ASTM NAK (send side).
- Client mode is experimental.
- RSP^K11 responses always terminate with L|1|N.

## Versioning

- Plugin version is embedded in the JAR.
- Setting and mapping files have independent versions.
