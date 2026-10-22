# GeneXpert – LabBook Connect plugin

This plugin enables communication between a Cepheid GeneXpert analyzer and LabBook.
The analyzer communicates using ASTM only, HL7 being used toward the LIS alone.

## Installation

The files to install are attached to each release:
https://github.com/fondationmerieux/labbook_connect_plugin_genexpert/releases/latest

| File | Role | Destination on the server |
|---|---|---|
| `AnalyzerGeneXpert.jar` | Plugin (compiled Java) | `/storage/resource/connect/analyzer/plugin/` |
| `analyzer_genexpert.toml` | Settings file: analyzer identity and connection | `/storage/resource/connect/analyzer/setting/` |
| `mapping_genexpert.toml` | Mapping between GeneXpert test codes and LabBook variables | `/storage/resource/connect/analyzer/mapping/` |

Each file is installed individually, either by copying it to its location on the server or
by uploading it through the LabBook user interface (when supported).

### 1. Before you start

Collect the following information:

- The **IP address** of the GeneXpert computer on the laboratory network.
- A free **TCP port** on the LabBook server, different from the ports used by other analyzers
  (allowed ranges in [Configuration files](#configuration-files)).
- The list of **cartridges (assays)** used in the laboratory (MTB/RIF Ultra, HIV-1 Viral Load XC, HBV VL…).
- The **LabBook analysis and variable codes** that will receive the results.

### 2. Fill in the settings file

`analyzer_genexpert.toml` is a sample and MUST be edited before use.

| Key | What to enter |
|---|---|
| `name` | Model name, e.g. `GeneXpert IV`, `GeneXpert XVI` |
| `id` | Short unique identifier with no spaces, e.g. `GX_01`. You will enter **exactly the same value** in LabBook (step 4). |
| `url_lis` | Leave `http://localhost/sigl` when LabBook and LabBook Connect run on the same server. |
| `ip` | IP address of the GeneXpert computer, used in client mode only |
| `port` | TCP port, written as a **number without quotes** |
| `mapping` | Path to the mapping file. Leave as is if you keep the default location. |

Do not modify `plugin`, `type_cnx` and `type_msg`.

> ⚠️ **Never leave a placeholder value in a file placed in the `setting/` folder.**
> LabBook reads every file in that folder. A single invalid file prevents **all** analyzers from loading, not only this one.

### 3. Copy the files to the server

```bash
cp AnalyzerGeneXpert.jar     /storage/resource/connect/analyzer/plugin/
cp analyzer_genexpert.toml   /storage/resource/connect/analyzer/setting/
cp mapping_genexpert.toml    /storage/resource/connect/analyzer/mapping/
```

Then **restart LabBook Connect**. The plugin (`.jar`) is only loaded at startup, restarting LabBook alone is not enough.

### 4. Declare the analyzer in LabBook

1. In LabBook, open the analyzer management page and add a new analyzer.
2. Enter the **same identifier** as `id` in the settings file (e.g. `GX_01`).
3. Set the **Mode**:
   - **Query** (recommended): LabBook sends nothing on its own. When the operator scans the sample barcode, the GeneXpert asks LabBook for the test to perform.
   - **Batch**: LabBook sends a test request to the GeneXpert each time a sample is created.

> The `operation_mode` key in the settings file has no effect on behavior. The **Mode of the analyzer record in LabBook** is the setting that counts.

### 5. Configure the GeneXpert Dx System

On the GeneXpert computer, open the host communication settings (**Setup → System Configuration → Host Communication Settings**, depending on the software version) and set:

| Setting | Value |
|---|---|
| Protocol | ASTM |
| Connection | TCP/IP |
| Host IP address | IP address of the LabBook server |
| Host port | Same value as `port` in the settings file |
| Automatic result upload | Enabled |
| Host query | Enabled if LabBook Mode is **Query** |

In the assay settings, enter the **host test code** of each cartridge. These codes must match the codes used in the mapping file (step 6).

### 6. Adapt the mapping

`mapping_genexpert.toml` links each GeneXpert result to a **LabBook variable code**.

The GeneXpert result code depends on the number of results the assay **reports**:

| Case | Code structure | Examples |
|---|---|---|
| One reported result | `^^^ASSAY_CODE^Name^Version^^` | HIV-1 VL XC, HBV VL, SARS-CoV-2 |
| Several reported results | `^ASSAY_CODE^^PARAM_CODE^Name^Version^Analyte^` | MTB/RIF Ultra, MTB-XDR, Carba-R, HIV-1 Qual |

`ASSAY_CODE` and `PARAM_CODE` are the host codes entered in the Dx System. `Name`, `Version` and `Analyte` come from the Cepheid cartridge definition: read them from a real message, do not invent them.

Length limits:

- Host test code in the Dx System: **15 characters** maximum.
- LabBook variable code in the mapping: **10 characters** maximum. Beyond that, the result is received but never pre-filled.

Check that every LabBook code in the mapping exists in your LabBook analyses. If a code does not match, the result is still received and visible in the analyzer results panel, but **no field is pre-filled**.

The mapping is reloaded without restarting LabBook Connect.

### 7. Check that it works

1. Look at the log:
   ```bash
   tail -f /var/log/labbook/connect/labbook_connect.log
   ```
   After the restart, the log must show that the analyzer is loaded and listening on the chosen port.
2. Run a test on the GeneXpert (or re-send a result from the Dx System).
3. In LabBook, check that the message appears in **Transactions** and that the result is pre-filled in the analysis.

### Troubleshooting

| Symptom | Check |
|---|---|
| No analyzer loads at all | A file in `setting/` is invalid (placeholder value, syntax error). |
| LabBook answers "analyzer not added" | `plugin = "AnalyzerGeneXpert"` spelled exactly, and LabBook Connect restarted after copying the `.jar`. |
| The Dx System shows a host communication error | IP address and port in the Dx System, firewall, port not used by another analyzer. |
| Result received but not pre-filled | Code in the mapping does not match the code sent by the GeneXpert, or the LabBook code is longer than 10 characters. |
| Wrong test requested on the GeneXpert | Host test code in the Dx System does not match the mapping. |

## Compatible models

| Models | Transactions |
|---|---|
| GeneXpert (Cepheid) | query, orders and results |

Based on the host interface specification 302-2261 Rev. F, validated on an Xpert Carba-R.

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

## Logging

- Logs use the global LabBook Connect logging configuration.
- Low-level ASTM traffic (ENQ, ACK, frames) is logged for diagnostic purposes.

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

## Limitations

- The test code sent to the analyzer, in the ASTM O segment, must be 15 characters or less.
  A longer code is accepted by LabBook but its results come back without being matched.
- No automatic frame retransmission on ASTM NAK (send side).
- Client mode is experimental.
- RSP^K11 responses always terminate with L|1|N.

## Versioning

- Plugin version is embedded in the JAR.
- Setting and mapping files have independent versions.
