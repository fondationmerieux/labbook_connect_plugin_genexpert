# Installation — GeneXpert plugin for LabBook Connect

This folder contains everything needed to connect a **Cepheid GeneXpert** (GeneXpert Dx System software) to LabBook through LabBook Connect.

| File | Role | Destination on the server |
|---|---|---|
| `AnalyzerGeneXpert.jar` | Plugin (compiled Java) | `/storage/resource/connect/analyzer/plugin/` |
| `analyzer_genexpert.toml` | Settings file: analyzer identity and connection | `/storage/resource/connect/analyzer/setting/` |
| `mapping_genexpert.toml` | Mapping between GeneXpert test codes and LabBook variables | `/storage/resource/connect/analyzer/mapping/` |

Connection: **ASTM** (E1381 / E1394) over TCP socket. LabBook Connect listens (`server` mode); the GeneXpert computer connects to it.

---

## 1. Before you start

Collect the following information:

- The **IP address** of the GeneXpert computer on the laboratory network.
- A free **TCP port** on the LabBook server (e.g. `7503`). It must be different from the ports used by other analyzers.
- The list of **cartridges (assays)** used in the laboratory (MTB/RIF Ultra, HIV-1 Viral Load XC, HBV VL…).
- The **LabBook analysis and variable codes** that will receive the results.

---

## 2. Fill in the settings file

Open `analyzer_genexpert.toml` in a text editor and replace **every** `XXX` / `X.X.X.X` value.

```toml
version = "1.0.1"

[analyzer]
brand = "GENEXPERT"
name = "GeneXpert"
id = "GX1"                           # Unique identifier — same value as in LabBook
plugin = "AnalyzerGeneXpert"         # Do not change
url_lis = "http://localhost/sigl"    # LabBook address seen from LabBook Connect
operation_mode = "batch"
archive_msg = "Y"
type_cnx = "socket"                  # Do not change
type_msg = "ASTM"                    # Do not change
mapping = "/storage/resource/connect/analyzer/mapping/mapping_genexpert.toml"

[analyzer.socket]
mode = "server"                      # LabBook Connect listens, the GeneXpert connects
ip = "192.168.1.60"                  # GeneXpert computer IP address
port = 7503                          # Listening port (number, no quotes)
```

| Key | What to enter |
|---|---|
| `name` | Model name, e.g. `GeneXpert IV`, `GeneXpert XVI` |
| `id` | Short unique identifier with no spaces, e.g. `GX1`. You will enter **exactly the same value** in LabBook (step 4). |
| `url_lis` | Leave `http://localhost/sigl` when LabBook and LabBook Connect run on the same server. |
| `ip` | IP address of the GeneXpert computer |
| `port` | TCP port, written as a **number without quotes** |
| `mapping` | Path to the mapping file. Leave as is if you keep the default location. |

Do not modify `plugin`, `type_cnx` and `type_msg`.

> ⚠️ **Never leave a placeholder value (`XXX`, `X.X.X.X`) in a file placed in the `setting/` folder.**
> LabBook reads every file in that folder. A single invalid file prevents **all** analyzers from loading, not only this one.

---

## 3. Copy the files to the server

```bash
cp AnalyzerGeneXpert.jar     /storage/resource/connect/analyzer/plugin/
cp analyzer_genexpert.toml   /storage/resource/connect/analyzer/setting/
cp mapping_genexpert.toml    /storage/resource/connect/analyzer/mapping/
```

Then **restart LabBook Connect**. The plugin (`.jar`) is only loaded at startup; restarting LabBook alone is not enough.

---

## 4. Declare the analyzer in LabBook

1. In LabBook, open the analyzer management page and add a new analyzer.
2. Enter the **same identifier** as `id` in the settings file (e.g. `GX1`).
3. Set the **Mode**:
   - **Query** (recommended): LabBook sends nothing on its own. When the operator scans the sample barcode, the GeneXpert asks LabBook for the test to perform.
   - **Batch**: LabBook sends a test request to the GeneXpert each time a sample is created.

> The `operation_mode` key in the settings file has no effect on behavior. The **Mode of the analyzer record in LabBook** is the setting that counts.

---

## 5. Configure the GeneXpert Dx System

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

---

## 6. Adapt the mapping

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

---

## 7. Check that it works

1. Look at the log:
   ```bash
   tail -f /var/log/labbook/connect/labbook_connect.log
   ```
   After the restart, the log must show that the analyzer is loaded and listening on the chosen port.
2. Run a test on the GeneXpert (or re-send a result from the Dx System).
3. In LabBook, check that the message appears in **Transactions** and that the result is pre-filled in the analysis.

## Troubleshooting

| Symptom | Check |
|---|---|
| No analyzer loads at all | A file in `setting/` is invalid (placeholder value, syntax error). |
| LabBook answers "analyzer not added" | `plugin = "AnalyzerGeneXpert"` spelled exactly, and LabBook Connect restarted after copying the `.jar`. |
| The Dx System shows a host communication error | IP address and port in the Dx System, firewall, port not used by another analyzer. |
| Result received but not pre-filled | Code in the mapping does not match the code sent by the GeneXpert, or the LabBook code is longer than 10 characters. |
| Wrong test requested on the GeneXpert | Host test code in the Dx System does not match the mapping. |
