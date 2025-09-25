# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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