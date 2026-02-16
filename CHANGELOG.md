# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.3] - 2026-02-16
### Fixed
- LAB-27: fix duplicate P segments in LAB-27 ASTM response.

## [1.0.2] - 2026-02-16
### Fixed
- LAB-27: correct mapping of OBR-4 (GXxx) to vendor_test_code in ASTM response

## [1.0.1] - 2026-02-12
### Fixed
- LAB-27: return `L|1|Y` on successful RSP^K11 processing; `L|1|N` only on technical error.

## [1.0.0] - 2026-02-02
### Changed
- Initial stable release of the GeneXpert plugin
- Added operator README and generated Javadoc

## [0.9.15] - 2026-01-13
### Changed
- build analyzer result value from result + reference range fields when available

## [0.9.14] - 2026-01-12
### Fixed
- Fix GeneXpert mapping to accept integer and float factor values.

## [0.9.13] - 2026-01-08
### Fixed
- Fix GeneXpert mapping to accept integer and float factor values.

## [0.9.12] - 2026-01-06
### Fixed
- GeneXpert result mapping to use the ASTM R|2 field for vendor result code matching

## [0.9.11] - 2025-12-22
### Added
- Load LIVD-like mappings at startup and apply them (test/result codes, units, conversions) during ASTM→HL7 OUL^R22 conversion

## [0.9.10] - 2025-12-01
### Fixed
- Added proper server/client listening handling to prevent port binding issues and ensure clean reconnection.
- Corrected ASTM → HL7 LAB-29 mapping: result values are now properly placed in OBX-5.
- Fixed result parsing so values and units are stored correctly in LabBook.
- Improved listener shutdown to ensure sockets are always released on restart.

## [0.9.9] - 2025-11-26
### Fixed
- Fixed GeneXpert ASTM R-segment parsing so OBX-5 contains the numeric result value

## [0.9.8] - 2025-10-21
### Changed
- Improved multi-frame (ETB) handling in ASTM reception.
- Removed extra CR injection between frames.
- Added fallback for non-HL7 upstream responses (return `L|1|N`).
- Fixed server socket double accept.
- Added ETB to printable logs.

## [0.9.7] - 2025-10-13
### Changed
- modified listeForIncoming and include ETB caracter

## [0.9.6] - 2025-09-25
### Changed
- include "socket" to type of connection (already accept socket_E1381)
- thread processing

## [0.9.5] - 2025-09-17
### Added
- server mode ASTM

## [0.9.4] - 2025-07-18
### Added
- Added logging of all incoming bytes (hex + printable form) in listenForIncomingMessages() to help diagnose ASTM protocol-level issues.

### Changed
- The plugin listens continuously, and if no data is received within 10 seconds, a timeout occurs, but the socket remains open and listening resumes normally.
- Replaced boolean listening with AtomicBoolean listening for proper thread-safe state management across listener threads.
- Updated convertRSP_K11toASTM(...) to only include O| segments if both SPM and OBR fields are available, preventing invalid partial responses.

## [0.9.3] - 2025-07-17
### Changed
- remove numbers at start of frame astm

### Fixed
- last line of HL7 message for Lab29 transactions
- convertRSP_K11toASTM

## [0.9.2] - 2025-07-10
### Added
- type_cnx : socket_E1381
- convertOML_O33ToASTM
- convertASTMQueryToQBP_Q11
- convertASTMtoOUL_R22
- convertRSP_K11toASTM
- generateAckR22

## [0.8.0] - 2025-03-17
### Added
- setting file for GeneXpert