package plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.model.Group;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Structure;
import ca.uhn.hl7v2.model.v251.datatype.CX;
import ca.uhn.hl7v2.model.v251.datatype.IS;
import ca.uhn.hl7v2.model.v251.datatype.ST;
import ca.uhn.hl7v2.model.v251.datatype.TS;
import ca.uhn.hl7v2.model.v251.datatype.XAD;
import ca.uhn.hl7v2.model.v251.datatype.XPN;
import ca.uhn.hl7v2.model.v251.datatype.XTN;
import ca.uhn.hl7v2.model.v251.group.OML_O33_SPECIMEN;
import ca.uhn.hl7v2.model.v251.message.OML_O33;
import ca.uhn.hl7v2.model.v251.message.QBP_Q11;
import ca.uhn.hl7v2.model.v251.segment.MSH;
import ca.uhn.hl7v2.model.v251.segment.OBR;
import ca.uhn.hl7v2.model.v251.segment.PID;
import ca.uhn.hl7v2.model.v251.segment.QPD;
import ca.uhn.hl7v2.model.v251.segment.RCP;
import ca.uhn.hl7v2.model.v251.segment.SPM;
import ca.uhn.hl7v2.model.v251.message.ACK;

/**
 * Implementation of the Analyzer interface specific for GeneXpert analyzers.
 * <p>
 * This class provides the functionalities needed to communicate with GeneXpert analyzers 
 * using ASTM protocol, handling LAB-27, LAB-28, and LAB-29 transactions.
 */
public class AnalyzerGeneXpert implements Analyzer {
	
	private static final Logger logger = LoggerFactory.getLogger(AnalyzerGeneXpert.class); // Uses Connect's logback.xml
	
	private final String jar_version = "0.9.6";

    // === General Configuration ===
    protected String version = "";
    protected String id_analyzer = "";
    protected String url_upstream_lab27 = "";
    protected String url_upstream_lab29 = "";

    // === Connection Configuration ===
    protected String type_cnx = "";
    protected String type_msg = "";
    protected String archive_msg = "";
    protected String operation_mode = "batch";
    protected String mode = "";
    protected String ip_analyzer = "";
    protected int port_analyzer = 0;

    // === Runtime State ===
    protected AtomicBoolean listening = new AtomicBoolean(false);
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    
    // ASTM control characters
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;

    
    /**
     * Default constructor.
     * <p>
     * Instantiates a new AnalyzerGeneXpert with default settings.
     */
    public AnalyzerGeneXpert() {
    }

    // === Getters and Setters ===
    @Override
    public String getId_analyzer() {
        return id_analyzer;
    }

    @Override
    public void setId_analyzer(String id_analyzer) {
        this.id_analyzer = id_analyzer;
    }

    @Override
    public String getUrl_upstream_lab27() {
        return url_upstream_lab27;
    }

    @Override
    public void setUrl_upstream_lab27(String url) {
        this.url_upstream_lab27 = url;
    }

    @Override
    public String getUrl_upstream_lab29() {
        return url_upstream_lab29;
    }

    @Override
    public void setUrl_upstream_lab29(String url) {
        this.url_upstream_lab29 = url;
    }

    @Override
    public void setVersion(String version) {
        this.version = version;
    }

    @Override
    public void setType_cnx(String type_cnx) {
        this.type_cnx = type_cnx;
    }

    @Override
    public void setType_msg(String type_msg) {
        this.type_msg = type_msg;
    }

    @Override
    public void setArchive_msg(String archive_msg) {
        this.archive_msg = archive_msg;
    }
    
    @Override
    public void setOperationMode(String operation_mode) {
        this.operation_mode = operation_mode;
    }

    @Override
    public void setMode(String mode) {
        this.mode = mode;
    }

    @Override
    public void setIp_analyzer(String ip_analyzer) {
        this.ip_analyzer = ip_analyzer;
    }

    @Override
    public void setPort_analyzer(int port_analyzer) {
        this.port_analyzer = port_analyzer;
    }

    // === Core Functionalities ===

    @Override
    public AnalyzerGeneXpert copy() {
        AnalyzerGeneXpert newAnalyzer = new AnalyzerGeneXpert();
        newAnalyzer.setId_analyzer(this.id_analyzer);
        newAnalyzer.setVersion(this.version);
        newAnalyzer.setUrl_upstream_lab27(this.url_upstream_lab27);
        newAnalyzer.setUrl_upstream_lab29(this.url_upstream_lab29);
        newAnalyzer.setType_cnx(this.type_cnx);
        newAnalyzer.setType_msg(this.type_msg);
        newAnalyzer.setArchive_msg(this.archive_msg);
        newAnalyzer.setMode(this.mode);
        newAnalyzer.setIp_analyzer(this.ip_analyzer);
        newAnalyzer.setPort_analyzer(this.port_analyzer);
        return newAnalyzer;
    }

    @Override
    public String test() {
        return this.getClass().getSimpleName();
    }
    
    @Override
    public String info() {
        return String.format(
            "Analyzer Info: [Jar=%s Version=%s, Version=%s, ID=%s, Lab27=%s, Lab29=%s, TypeCnx=%s, TypeMsg=%s, ArchiveMsg=%s, OperationMode=%s, Mode=%s, IP=%s, Port=%d]",
            this.jar_version, this.version, this.id_analyzer, this.url_upstream_lab27, this.url_upstream_lab29,
            this.type_cnx, this.type_msg, this.archive_msg, this.operation_mode, this.mode, this.ip_analyzer, this.port_analyzer
        );
    }

    @Override
    public boolean isListening() {
        return this.listening.get();
    }

    // === Methods for LAB Transactions ===

    /**
     * Handles a LAB-27 transaction (ASTM query from analyzer).
     * Converts ASTM Q| message into HL7 QBP^Q11, sends to LabBook,
     * receives RSP^K11, and converts the response back into ASTM format.
     * 
     * @param msg The raw ASTM message received from GeneXpert
     * @return ASTM response to send back to analyzer, or null if error
     */
    @Override
    public String lab27(final String msg) {
        logger.info("Lab27 GeneXpert : Received ASTM query message\n" + msg);

        try {
            Connect_util.archiveMessage(this.getId_analyzer(), this.archive_msg, msg, "LAB-27", "Analyzer");

            // Parse ASTM message into lines
            String[] astmLines = logAndSplitAstm(msg);

            // Convert ASTM query to HL7 QBP^Q11
            String qbpMsg = convertASTMQueryToQBP_Q11(astmLines);
            if (qbpMsg == null) {
                logger.error("Lab27 GeneXpert : Failed to convert ASTM to HL7 QBP^Q11");
                return null;
            }

            logger.info("Lab27 GeneXpert : Converted HL7 QBP^Q11\n" + qbpMsg.replace("\r", "\n"));

            // Send QBP^Q11 to LabBook
            String rspMsg = Connect_util.send_hl7_msg(this, this.url_upstream_lab27, qbpMsg);
            logger.info("Lab27 GeneXpert : Received RSP^K11 from LabBook\n" + rspMsg.replace("\r", "\n"));
            
            // Convert RSP^K11 back to ASTM message for GeneXpert
            String[] astmResponse = convertRSP_K11toASTM(rspMsg);
            if (astmResponse == null || astmResponse.length == 0) {
                logger.error("Lab27 GeneXpert : Failed to convert RSP^K11 to ASTM response");
                return null;
            }

            return String.join("\r", astmResponse);  // Send back ASTM response

        } catch (Exception e) {
            logger.error("Lab27 GeneXpert : Unexpected error: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Handles a LAB-28 transaction (order message from LIS to analyzer).
     * Parses the incoming HL7 OML^O33 message, extracts patient/specimen/order info,
     * converts the message into ASTM format, and sends it to the analyzer over socket.
     * 
     * Returns an HL7 ACK^R22 to confirm whether the analyzer accepted the message (ACK) or not (NAK).
     *
     * @param str_OML_O33 HL7 message string in ER7 format (OML^O33)
     * @return HL7 ACK^R22 message to be returned to LabBook
     */
    @Override
    public String lab28(final String str_OML_O33) {
        logger.info("Lab28 GeneXpert : Received message\n" + str_OML_O33.replace("\r", "\r\n"));

        try {
            Connect_util.archiveMessage(this.getId_analyzer(), this.archive_msg, str_OML_O33.replace("\r", "\r\n"), "LAB-28", "LIS");

            PipeParser parser = new PipeParser();
            OML_O33 omlMessage = (OML_O33) parser.parse(str_OML_O33);

            // Log and check number of SPECIMEN groups
            int specimenCount = omlMessage.getSPECIMENReps();
            logger.info("Lab28 GeneXpert : Number of SPECIMEN groups = {}", specimenCount);

            if (specimenCount == 0) {
                logger.error("Lab28 GeneXpert : Error - No SPECIMEN group found in the message");
                return "ERROR Lab28 GeneXpert : No SPECIMEN group found.";
            }

            // Get first SPECIMEN group
            OML_O33_SPECIMEN specimenGroup = omlMessage.getSPECIMEN();

            // Log and check number of ORDER groups in SPECIMEN
            int orderCount = specimenGroup.getORDERReps();
            logger.info("Lab28 GeneXpert : Number of ORDER groups in SPECIMEN = {}", orderCount);

            if (orderCount == 0) {
                logger.error("Lab28 GeneXpert : Error - No ORDER group found in SPECIMEN");
                return "ERROR Lab28 GeneXpert : No ORDER group found.";
            }

            // Proceed with conversion using the complete HL7 message
            String[] astmLines = convertOML_O33ToASTM(str_OML_O33);
            if (astmLines.length == 1 && astmLines[0].startsWith("ERROR")) {
                logger.error("Lab28 GeneXpert : Error during conversion to ASTM : " + astmLines[0]);
                return "ERROR Lab28 GeneXpert : Invalid OML_O33 message";
            }

            logger.info("Lab28 GeneXpert : Converted ASTM message\n" + String.join("\n", astmLines));

            String result = sendASTMMessage(astmLines);

            String ackCode = "AA"; // Default HL7 ACK = accepted
            if (!"ACK".equals(result)) {
                ackCode = "AE"; // Application Error if analyzer rejected the message
            }

            String hl7Ack = generateAckR22(str_OML_O33, ackCode);
            if (hl7Ack != null) {
                logger.info("Lab28 GeneXpert : Returning HL7 ACK^R22 to LabBook");
                return hl7Ack;
            } else {
                logger.error("Lab28 GeneXpert : Failed to generate HL7 ACK^R22");
                return "ERROR Lab28 GeneXpert : Failed to generate HL7 ACK";
            }

        } catch (HL7Exception e) {
            logger.error("Lab28 GeneXpert : HL7Exception while processing OML^O33 - " + e.getMessage());
            return "ERROR Lab28 GeneXpert : Failed to process OML^O33 message";
        } catch (Exception e) {
            logger.error("Lab28 GeneXpert : Unexpected exception - " + e.getMessage(), e);
            return "ERROR Lab28 GeneXpert : Unexpected error occurred";
        }
    }

    /**
     * Handles a LAB-29 transaction (ASTM results from analyzer).
     * Parses ASTM result lines into HL7 OUL^R22, forwards to LabBook,
     * receives HL7 ACK, and returns an ASTM L|1|Y or L|1|N acknowledgement.
     *
     * @param msg ASTM message sent by GeneXpert (results)
     * @return Minimal ASTM ACK segment or fallback error response
     */
    @Override
    public String lab29(final String msg) {
        logger.info("Lab29 GeneXpert : Received ASTM message\n" + msg);

        try {
            Connect_util.archiveMessage(this.getId_analyzer(), this.archive_msg, msg, "LAB-29", "Analyzer");

            // Split the ASTM message into individual lines
            String[] astmLines = logAndSplitAstm(msg);

            // Convert ASTM to HL7 OUL^R22
            String hl7Message = convertASTMtoOUL_R22(astmLines);
            if (hl7Message == null || hl7Message.isEmpty()) {
                logger.error("Lab29 GeneXpert : Error during conversion to HL7 OUL^R22.");
                return "L|1|N"; // ASTM error response
            }

            logger.info("Lab29 GeneXpert : Converted HL7 OUL^R22:\n" + hl7Message.replace("\r", "\n"));

            // Send HL7 message to LabBook and get the HL7 ACK response
            String hl7Ack = Connect_util.send_hl7_msg(this, this.url_upstream_lab29, hl7Message);
            logger.info("Lab29 GeneXpert : HL7 ACK from LabBook:\n" + hl7Ack.replace("\r", "\n"));

            // Convert HL7 ACK back to a minimal ASTM acknowledgment
            String astmAck = convertACKtoASTM(hl7Ack);
            logger.info("Lab29 GeneXpert : Converted ASTM ACK to return:\n" + astmAck);

            return astmAck;

        } catch (Exception e) {
            logger.error("Lab29 GeneXpert : Unexpected error - " + e.getMessage(), e);
            return "L|1|N"; // ASTM fallback error response
        }
    }
    
    // === Conversions HL7 <=> ASTM ===
    
    private String[] stripASTMPrefixNumbers(String[] lines) {
        return Arrays.stream(lines)
            .map(line -> line.replaceFirst("^[0-7](?=[A-Z]\\|)", ""))
            .toArray(String[]::new);
    }
    
    /**
     * Converts an HL7 OML^O33 order message into a set of ASTM lines compatible with GeneXpert.
     * Extracts patient identifiers, demographic info, specimen ID/type, and order details (OBR).
     * 
     * If the message lacks necessary segments (e.g., SPM or OBR), returns an error message.
     *
     * @param oml HL7 OML^O33 message string (pipe-delimited format)
     * @return Array of ASTM-formatted lines to be sent to the analyzer
     */
    public String[] convertOML_O33ToASTM(String oml) {
        List<String> lines = new ArrayList<>();
        String now = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

        try {
            PipeParser parser = new PipeParser();
            OML_O33 message = (OML_O33) parser.parse(oml);

            PID pid = message.getPATIENT().getPID();

            // Patient identifiers
            String patId    = safeCX(pid.getPatientIdentifierList(), 0);
            String patAltId = safeCX(pid.getPatientIdentifierList(), 1);

            // Patient name
            String lastName  = safeXPN(pid.getPatientName(), 0, true);
            String firstName = safeXPN(pid.getPatientName(), 0, false);

            // Sex / birth date
            String sex = safe(pid.getAdministrativeSex());
            String dob = safe(pid.getDateTimeOfBirth());

            // Phone number / address
            String phone   = safeXTN(pid.getPhoneNumberHome(), 0);
            String address = safeXAD(pid.getPatientAddress(), 0, "street");
            String city    = safeXAD(pid.getPatientAddress(), 0, "city");
            String zip     = safeXAD(pid.getPatientAddress(), 0, "zip");

            // Get first specimen group
            OML_O33_SPECIMEN specimenGroup = message.getSPECIMEN();

            // Extract specimen segment
            SPM spm = specimenGroup.getSPM();
            String specimenId = safe(spm.getSpecimenID().getPlacerAssignedIdentifier().getEntityIdentifier());
            String specimenType = safe(spm.getSpecimenType().getIdentifier());

            // Inside convertOML_O33ToASTM after parsing message and getting specimenGroup

            Structure[] orderGroups = specimenGroup.getAll("ORDER");

            OBR obr = null;

            for (Structure orderStruct : orderGroups) {
                Group orderGroup = (Group) orderStruct;
                try {
                    // Get all OBSERVATION_REQUEST groups inside this ORDER group
                    Structure[] obsReqGroups = orderGroup.getAll("OBSERVATION_REQUEST");
                    for (Structure obsReqStruct : obsReqGroups) {
                        Group obsReqGroup = (Group) obsReqStruct;
                        try {
                            // Try to get OBR segment inside OBSERVATION_REQUEST group
                            obr = (OBR) obsReqGroup.get("OBR");
                            if (obr != null) break;
                        } catch (HL7Exception e) {
                            // Not found, continue
                        }
                    }
                    if (obr != null) break;
                } catch (HL7Exception e) {
                    // Could not get OBSERVATION_REQUEST groups, continue
                }
            }

            if (obr == null) {
                logger.error("No OBR segment found in any OBSERVATION_REQUEST group");
                return new String[] { "ERROR: No order found" };
            }

            // Extract test code and name from OBR
            String testCode = safe(obr.getUniversalServiceIdentifier().getIdentifier());
            String assayName = safe(obr.getUniversalServiceIdentifier().getText());
            String assayVersion = "4.7";

            // Build ASTM message lines
            lines.add("H|\\^&|||INST^GeneXpert^" + assayVersion + "||||||P|1394-97|" + now);
            lines.add("P|1|" + patId + "|" + patAltId + "|" + lastName + "^" + firstName + "||" +
                      dob + "|" + sex + "||||" + address + "^^" + city + "^" + zip + "||" + phone);
            lines.add("O|1|" + specimenId + "||^^^" + testCode + "^" + assayName + "^" + assayVersion +
                      "^^|" + specimenType + "|" + now + "||||||||||||||||||F");
            lines.add("L|1|N");

            return lines.toArray(new String[0]);

        } catch (Exception e) {
            logger.error("ERROR while converting OML_O33 to ASTM: " + e.getMessage(), e);
            return new String[] { "ERROR: Failed to convert HL7 to ASTM" };
        }
    }
    
    /**
     * Generates an HL7 ACK^R22 message in response to a received OML^O33 order.
     * The ACK reuses key fields (e.g., message control ID, sender/receiver IDs) from the original message.
     *
     * @param originalOML The original HL7 OML^O33 message string
     * @param ackCode The acknowledgment code to return: "AA" (Accept) or "AE" (Error)
     * @return The generated HL7 ACK^R22 message in ER7 format, or null if generation failed
     */
    public String generateAckR22(String originalOML, String ackCode) {
        try {
            PipeParser parser = new PipeParser();
            OML_O33 originalMsg = (OML_O33) parser.parse(originalOML);

            ACK ack = new ACK();
            ack.initQuickstart("ACK", "R22", "P");

            ack.getMSH().getDateTimeOfMessage().getTime().setValue(new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
            ack.getMSH().getMessageControlID().setValue(originalMsg.getMSH().getMessageControlID().getValue());
            ack.getMSH().getSendingApplication().parse("GeneXpert");
            ack.getMSH().getSendingFacility().parse("Analyzer");
            ack.getMSH().getReceivingApplication().parse(originalMsg.getMSH().getSendingApplication().encode());
            ack.getMSH().getReceivingFacility().parse(originalMsg.getMSH().getSendingFacility().encode());

            ack.getMSA().getAcknowledgmentCode().setValue(ackCode); // "AA" or "AE"
            ack.getMSA().getMessageControlID().setValue(originalMsg.getMSH().getMessageControlID().getValue());

            return parser.encode(ack);
        } catch (Exception e) {
            logger.error("Failed to generate HL7 ACK^R22: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Converts ASTM-formatted GeneXpert result lines into an HL7 OUL^R22 message.
     * @param lines An array of ASTM lines (e.g., H|..., P|..., O|..., R|..., etc.)
     * @return The HL7 OUL^R22 message in ER7 format or null if conversion fails.
     */
    public String convertASTMtoOUL_R22(String[] lines) {
        try {
        	lines = stripASTMPrefixNumbers(lines);
        	
            StringBuilder hl7 = new StringBuilder();

            // === MSH (construit manuellement) ===
            String sendingApp = "GeneXpert";
            String sendingFacility = "Analyzer";
            String receivingApp = "LabBook";
            String receivingFacility = "LIS";
            String datetime = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String controlId = "MSG" + System.currentTimeMillis();

            hl7.append("MSH|^~\\&|")
                .append(sendingApp).append("|")
                .append(sendingFacility).append("|")
                .append(receivingApp).append("|")
                .append(receivingFacility).append("|")
                .append(datetime).append("||")
                .append("OUL^R22|").append(controlId).append("|P|2.5.1\r");

            String patientId = null;
            String specimenId = null;
            int obxIndex = 1;

            for (String line : lines) {
            	String[] fields = line.split("\\|", -1);

            	switch (fields[0]) {
            	case "P":
            		patientId = (fields.length > 2) ? fields[2] : null;
            		hl7.append("PID|||").append(patientId != null ? patientId : "")
            		.append("||").append("\r");
            		break;

            	case "O":
                    // O: order/specimen identifier
                    specimenId = (fields.length > 2) ? fields[2] : null;

                    // SPM should precede ORC/OBR in OUL^R22
                    hl7.append("SPM|1|")
                       .append(specimenId != null ? specimenId : "")
                       .append("\r");

                    // ORC with placer order number = specimenId
                    hl7.append("ORC|RE|")
                       .append(specimenId != null ? specimenId : "")
                       .append("\r");

                    // OBR with same placer order number; test code from O|5 if present
                    hl7.append("OBR|1|")
                       .append(specimenId != null ? specimenId : "")
                       .append("||");
                    if (fields.length > 4) hl7.append(fields[4]); // ^^^code^text^ver
                    hl7.append("\r");
                    break;

            	case "R":
            		hl7.append("OBX|").append(obxIndex).append("|TX|");
            		if (fields.length > 2) hl7.append(fields[2]); // observation identifier
            		hl7.append("|");
            		if (fields.length > 3) hl7.append(fields[3]); // value
            		hl7.append("|||||||");
            		hl7.append(fields.length > 11 ? fields[11] : "F").append("\r"); // status
            		obxIndex++;
            		break;

            	case "C":
            		hl7.append("NTE|1|L|").append(
            				String.join(" ", Arrays.copyOfRange(fields, 1, fields.length))
            				).append("\r");
            		break;
            	}
            }

            return hl7.toString();

        } catch (Exception e) {
            logger.error("GeneXpert: Failed to convert ASTM to HL7 OUL_R22", e);
            return null;
        }
    }
    
    /**
     * Converts an HL7 ACK message (typically from LabBook) into a minimal ASTM acknowledgment.
     * 
     * If the ACK contains MSA-1 = "AA" (Application Accept), returns "L|1|Y" (success).
     * For any other acknowledgment code, returns "L|1|N" (failure).
     *
     * @param hl7Ack HL7 ACK message as an ER7-formatted string
     * @return ASTM acknowledgment line (e.g., "L|1|Y" or "L|1|N")
     */
    public String convertACKtoASTM(String hl7Ack) {
        try {
            PipeParser parser = new PipeParser();
            Message ackMsg = parser.parse(hl7Ack);

            if (!(ackMsg instanceof ACK)) {
                logger.error("convertACKtoASTM: Not an ACK message");
                return "L|1|N";
            }

            ACK ack = (ACK) ackMsg;
            String code = ack.getMSA().getAcknowledgmentCode().getValue();

            // ASTM equivalent: "L|1|Y" if ACK=AA, otherwise "L|1|N"
            return "AA".equals(code) ? "L|1|Y" : "L|1|N";

        } catch (Exception e) {
            logger.error("convertACKtoASTM: Error converting HL7 to ASTM ACK - " + e.getMessage(), e);
            return "L|1|N";
        }
    }
    
    /**
     * Converts ASTM-formatted GeneXpert query (e.g., Q line) into an HL7 QBP^Q11 message.
     * @param lines An array of ASTM lines (e.g., starting with Q|...)
     * @return HL7 QBP^Q11 message in ER7 format or null if conversion fails.
     */
    public String convertASTMQueryToQBP_Q11(String[] lines) {
        try {
        	lines = stripASTMPrefixNumbers(lines);
        	
            // Find the line starting with Q| (query block)
            String queryLine = Arrays.stream(lines)
                    .filter(line -> line.startsWith("Q|"))
                    .findFirst()
                    .orElse(null);

            if (queryLine == null) {
                logger.error("convertASTMQueryToQBP_Q11: No Q line found in ASTM input.");
                return null;
            }

            // Split ASTM Q line into fields
            String[] fields = queryLine.split("\\|", -1);

            // Prepare HL7 QBP_Q11 message (HL7 v2.5.1)
            QBP_Q11 qbp = new QBP_Q11();
            qbp.initQuickstart("QBP", "Q11", "P");

            // Fill MSH (standard HL7 header)
            MSH msh = qbp.getMSH();
            msh.getSendingApplication().getNamespaceID().setValue("GeneXpert");
            msh.getSendingFacility().getNamespaceID().setValue("Analyzer");
            msh.getReceivingApplication().getNamespaceID().setValue("LabBook");
            msh.getReceivingFacility().getNamespaceID().setValue("LIS");
            msh.getDateTimeOfMessage().getTime().setValue(new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
            msh.getMessageControlID().setValue("MSG" + System.currentTimeMillis());
            msh.getVersionID().getVersionID().setValue("2.5.1");

            // Fill QPD segment (Query Parameter Definition)
            QPD qpd = qbp.getQPD();
            qpd.getMessageQueryName().getIdentifier().setValue("LAB-27^IHE");
            qpd.getQueryTag().setValue("GENEXPERT");

            // Use ASTM field[2] as specimen ID if available
            if (fields.length > 2) {
                qpd.getField(3, 0).parse(fields[2]);
            }

            // Fill RCP (response control parameters)
            RCP rcp = qbp.getRCP();
            rcp.getQueryPriority().setValue("I");  // I = Immediate

            // Encode to HL7 string
            PipeParser parser = new PipeParser();
            return parser.encode(qbp);

        } catch (Exception e) {
            logger.error("convertASTMQueryToQBP_Q11: Failed to convert ASTM to QBP^Q11: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Converts an HL7 RSP^K11 response from LabBook into a set of ASTM lines.
     * Each patient block is mapped to P| and O| segments. Handles missing fields gracefully.
     * 
     * @param hl7Message HL7 message string in ER7 format
     * @return Array of ASTM-formatted lines to return to GeneXpert
     */
    public static String[] convertRSP_K11toASTM(String hl7Message) {
        StringBuilder astm = new StringBuilder();
        astm.append("H|\\^&|||INST^GeneXpert^4.7||||||P|1394-97|").append(getCurrentDateTime()).append("\r");

        String[] segments = hl7Message.split("\r");
        
        String patientId = "";
        String patientName = "";
        String birthDate = "";
        String sex = "";
        String spmId = "";
        String obrCode = "";
        String obrName = "";

        for (String segment : segments) {
            if (segment.startsWith("PID|")) {
            	if (!isBlank(patientId)) {
            	    astm.append("P|1|").append(patientId).append("||").append(patientName).append("||")
            	        .append(birthDate).append("|").append(sex).append("\r");

            	    if (!isBlank(spmId) && !isBlank(obrCode)) {
            	        astm.append("O|1|").append(spmId).append("||^^^").append(obrCode).append("^").append(obrName)
            	            .append("^4.7^^||").append(getCurrentDateTime()).append("||||||||||||||||||F\r");
            	    } else {
            	        logger.warn("RSP^K11 incomplet : SPM ou OBR manquant");
            	    }
            	}

                String[] fields = segment.split("\\|");
                patientId = fields.length > 3 ? fields[3] : "";
                patientName = fields.length > 5 ? fields[5] : "";
                birthDate = fields.length > 7 ? fields[7] : "";
                sex = fields.length > 8 ? fields[8] : "";

                spmId = "";
                obrCode = "";
                obrName = "";

            } else if (segment.startsWith("SPM|")) {
                String[] fields = segment.split("\\|");
                spmId = fields.length > 2 ? fields[2] : "";

            } else if (segment.startsWith("OBR|")) {
                String[] fields = segment.split("\\|");
                if (fields.length > 4) {
                    String[] testInfo = fields[4].split("\\^");
                    obrCode = testInfo.length > 0 ? testInfo[0] : "";
                    obrName = testInfo.length > 1 ? testInfo[1] : "";
                }
            }
        }

        // Flush last block if present
        if (!isBlank(patientId)) {
            astm.append("P|1|").append(patientId).append("||").append(patientName).append("||")
                .append(birthDate).append("|").append(sex).append("\r");

            if (!isBlank(spmId) && !isBlank(obrCode)) {
                astm.append("O|1|").append(spmId).append("||^^^").append(obrCode).append("^").append(obrName)
                    .append("^4.7^^||").append(getCurrentDateTime()).append("||||||||||||||||||F\r");
            } else {
                logger.warn("RSP^K11 incomplet : SPM ou OBR manquant");
            }
        }

        astm.append("L|1|N\r");
        return astm.toString().split("\r");
    }

    // === Communication Management ===
    
    /**
     * Sends an ASTM message (line by line) to the analyzer over the active socket.
     * Each line is framed using ASTM E1381 protocol (STX, ETX, checksum).
     * Responds to each frame with ACK/NAK logic, and completes with EOT.
     *
     * @param lines ASTM message split into lines (e.g., H|..., P|..., O|..., L|...)
     * @return "ACK" if all frames were accepted, "NAK"/"UNKNOWN"/"ERROR" otherwise
     */
    public String sendASTMMessage(String[] lines) {
        try {
            logger.info(">>> Sending ENQ");
            outputStream.write(ENQ);
            outputStream.flush();

            socket.setSoTimeout(10000);
            int response;
            try {
                socket.setSoTimeout(10000);
                response = inputStream.read();
            } catch (SocketTimeoutException e) {
                logger.warn("Timeout waiting for ACK after ENQ (10s)");
                return "ERROR";
            }
            
            if (response == ACK) {
                logger.info("<<< Response: ACK");
            } else if (response == NAK) {
                logger.warn("<<< Response: NAK");
                return "NAK";
            } else {
                logger.warn("<<< Response: Unexpected byte: " + response);
                return "UNKNOWN";
            }

            for (int i = 0; i < lines.length; i++) {
                String body = ((i + 1) % 8) + lines[i];
                byte[] bodyBytes = body.getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(STX);
                frame.write(bodyBytes);
                frame.write(ETX);

                int checksum = 0;
                for (byte b : bodyBytes) checksum += (b & 0xFF);
                checksum += ETX;
                checksum &= 0xFF;
                String checksumStr = String.format("%02X", checksum);

                frame.write(checksumStr.getBytes(StandardCharsets.US_ASCII));
                frame.write(CR);
                frame.write(LF);

                logger.info(">>> Sending frame " + (i + 1) + ": " + lines[i]);
                outputStream.write(frame.toByteArray());
                outputStream.flush();

                socket.setSoTimeout(10000);
                int frameResp;
                try {
                    socket.setSoTimeout(10000);
                    frameResp = inputStream.read();
                } catch (SocketTimeoutException e) {
                    logger.warn("Timeout waiting for ACK after frame " + (i + 1) + " (10s)");
                    return "ERROR";
                }
                
                if (frameResp == ACK) {
                    logger.info("<<< Response: ACK");
                } else if (frameResp == NAK) {
                    logger.warn("<<< Response: NAK");
                    return "NAK";
                } else {
                    logger.warn("<<< Response: Unexpected byte: " + frameResp);
                    return "UNKNOWN";
                }
            }

            logger.info(">>> Sending EOT");
            outputStream.write(EOT);
            outputStream.flush();

            return "ACK";

        } catch (IOException e) {
            logger.error("ASTM send error: " + e.getMessage());
            return "ERROR";
        }
    }

    
    /**
     * Starts the communication listener thread for the analyzer device.
     * <p>
     * Depending on the configured connection type, this method initializes a socket connection in client mode
     * or logs an unsupported configuration message. The connection attempt utilizes exponential backoff
     * for reconnection attempts, starting with a 5-second delay and doubling the wait time after each failed attempt,
     * up to a maximum of 1 minute.
     * <p>
     * Once connected, the method continuously listens for incoming messages from the analyzer, setting
     * the internal `listening` state to true upon successful connection. It resets the backoff timer after every successful
     * connection. In case of connection errors or interruptions, the socket connection will be retried automatically.
     * <p>
     * This method runs continuously in a separate thread to avoid blocking the main application flow.
     */
    @Override
    public void listenDevice() {
    	logger.info("DEBUG: this.type_cnx = " + this.type_cnx);
    	logger.info("DEBUG: this.mode = " + this.mode);
    	logger.info("Connecting to analyzer at " + ip_analyzer + ":" + port_analyzer);

    	if (!"socket_E1381".equalsIgnoreCase(this.type_cnx) && !"socket".equalsIgnoreCase(this.type_cnx)) {
    		logger.info("Unsupported connection type: " + type_cnx);
    		this.listening.set(false);
    		return;
    	}

    	Thread mainListener = new Thread(() -> {
    		if ("client".equalsIgnoreCase(this.mode)) {
    			logger.info("Starting ASTM client mode...");

    			int backoffDelayMs = 5000;   // initial 5s
    			final int backoffMaxMs = 60000;  // cap 60s

    			while (true) {
    				try {
    					// Step 3: open socket
    					connectAsClient();

    					// >>> reset backoff on successful (re)connect
    					backoffDelayMs = 5000;

    					// Step 4: run E1381 FSM (blocks until socket closed or I/O error)
    					this.listening.set(true);
    					listenForIncomingMessages();

    					// Step 5: FSM returned => we'll try to reconnect
    					logger.warn("Client FSM ended; will attempt to reconnect.");

    				} catch (IOException ioEx) {
    					// Step 6: connection/open failure
    					logger.error("Client I/O error: " + ioEx.getMessage(), ioEx);

    				} finally {
    					// Step 7: ensure socket is closed and clear state
    					this.listening.set(false);
    					try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignore) {}
    					socket = null;
    					inputStream = null;
    					outputStream = null;
    				}

    				// Step 8: wait before next attempt (exponential backoff)
    				try { Thread.sleep(backoffDelayMs); } catch (InterruptedException ie) {
    					Thread.currentThread().interrupt();
    					logger.warn("Reconnect loop interrupted; stopping client mode.");
    					break;
    				}

    				// >>> No Step 9: reconnectSocket();  // not needed, Step 3 will (re)connect
    				backoffDelayMs = Math.min(backoffDelayMs * 2, backoffMaxMs);
    			}
    		} else {
    			// Step 1: Start ASTM server (blocking accept loop; per-connection threads run the FSM)
    			logger.info("Starting ASTM server mode...");
    			startASTMServer(); // never returns
    		}
    	});
    	mainListener.setName("AnalyzerGeneXpert-MainListener");
    	mainListener.setDaemon(true); // "daemon"
    	mainListener.start();
    }

    /**
     * Establishes a connection to the analyzer in CLIENT mode.
     * <p>
     * This method initializes the socket connection using the configured IP address and port of the analyzer.
     * It sets up input and output streams for subsequent message exchanges (e.g., ASTM transactions).
     * <p>
     * If a connection already exists and is open, no action is performed.
     *
     * @throws IOException if the connection attempt fails due to network errors or invalid connection parameters.
     */
    public void connectAsClient() throws IOException {
        if (socket != null && !socket.isClosed()) return;
        socket = new Socket(ip_analyzer, port_analyzer);
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
    }
    
    /**
     * Starts an ASTM server that listens for incoming ASTM messages.
     */
    private void startASTMServer() {
        while (true) {
            try (ServerSocket serverSocket = new ServerSocket(port_analyzer)) {
                logger.info("ASTM Server started on port {}", port_analyzer);

                while (true) {
                    try {
                        final Socket acceptedSocket = serverSocket.accept();
                        logger.info("Accepted connection from {}", acceptedSocket.getInetAddress());

                        try (Socket clientSocket = serverSocket.accept()) {
                            logger.info("Accepted connection from {}", clientSocket.getInetAddress());
                            this.socket = clientSocket;
                            this.inputStream = clientSocket.getInputStream();
                            this.outputStream = clientSocket.getOutputStream();
                            this.listening.set(true);
                            listenForIncomingMessages();
                        } catch (IOException ioEx) {
                            logger.error("ERROR: Client handling failed: {}", ioEx.getMessage(), ioEx);
                        } finally {
                            this.listening.set(false);
                            this.socket = null; this.inputStream = null; this.outputStream = null;
                            logger.info("Client connection closed.");
                        }                       

                    } catch (IOException acceptEx) {
                        logger.error("ERROR: Failed to accept client connection: {}", acceptEx.getMessage());
                    }
                }
            } catch (IOException startEx) {
                logger.error("ERROR: Failed to start ASTM server on port {}: {}", port_analyzer, startEx.getMessage());
                logger.info("Retrying in 10000 ms...");
                try { Thread.sleep(10000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.error("ERROR: Server retry interrupted.");
                    break;
                }
            }
        }
    }
    
    /**
     * Returns a printable representation of a control or ASCII byte.
     * Used for logging/debugging low-level byte traffic on the socket.
     *
     * @param b Byte value to convert
     * @return String description (e.g., "ACK", "CR", "LF", or character literal)
     */
    private String printable(int b) {
        if (b >= 32 && b <= 126) return "'" + (char) b + "'";
        switch (b) {
            case 0x02: return "STX";
            case 0x03: return "ETX";
            case 0x04: return "EOT";
            case 0x05: return "ENQ";
            case 0x06: return "ACK";
            case 0x15: return "NAK";
            case 0x0D: return "CR";
            case 0x0A: return "LF";
            default: return ".";
        }
    }

    /**
     * Listens for incoming ASTM messages from the analyzer using ASTM E1381 framing.
     * Handles ENQ handshake, receives frames until EOT, validates checksums,
     * and sends ACK/NAK responses. Automatically dispatches messages to lab27/lab29 logic.
     *
     * This method is blocking and runs in a dedicated thread while `listening` is true.
     */
    private void listenForIncomingMessages() {
    	while (this.listening.get()) {
            try {
            	// Step 1: Blocking wait for ENQ (with socket timeout)
                socket.setSoTimeout(15000); // 15 seconds
                int firstByte = inputStream.read();
                if (firstByte == -1) {
                    logger.info("Stream closed by peer during ENQ wait. Exiting listener.");
                    this.listening.set(false);
                    break;
                }

                logger.info("<<< DEBUG BYTE 0x{} ({})", String.format("%02X", firstByte), printable(firstByte));
                if (firstByte != ENQ) {
                    logger.warn("Expected ENQ but received: {}", printable(firstByte));
                    continue; // keep waiting for a proper ENQ
                }

                // Step 2: Send ACK to ENQ to start frame reception
                outputStream.write(ACK);
                outputStream.flush();
                logger.info(">>> Sent ACK [0x06] in response to ENQ");

                // Step 3: Receive frames until EOT (validate checksum and ACK/NAK each frame)
                ByteArrayOutputStream assembledMessage = new ByteArrayOutputStream();

                while (true) {
                    int frameStartByte = inputStream.read();
                    if (frameStartByte == -1) {
                        throw new IOException("Stream closed while waiting for STX/EOT");
                    }

                    logger.info("<<< DEBUG BYTE 0x{} ({})", String.format("%02X", frameStartByte), printable(frameStartByte));

                    // Step 3.1: End of transmission?
                    if (frameStartByte == EOT) {
                        logger.info("<<< Received EOT — message transmission complete");
                        break;
                    }

                    // Step 3.2: Expect STX to start a frame
                    if (frameStartByte != STX) {
                        logger.warn("Expected STX but got: {}", printable(frameStartByte));
                        continue; // ignore noise and keep reading
                    }

                    // Step 3.3: Read frame payload until ETX
                    ByteArrayOutputStream framePayload = new ByteArrayOutputStream();
                    int byteReadInFrame;
                    while ((byteReadInFrame = inputStream.read()) != ETX) {
                        if (byteReadInFrame == -1) {
                            throw new IOException("Stream closed inside frame before ETX");
                        }
                        framePayload.write(byteReadInFrame);
                    }

                    // Step 3.4: Read checksum (2 ASCII hex), then CR LF
                    int checksumHigh = inputStream.read();
                    int checksumLow  = inputStream.read();
                    int crByte       = inputStream.read();
                    int lfByte       = inputStream.read();

                    if (checksumHigh < 0 || checksumLow < 0 || crByte < 0 || lfByte < 0) {
                        throw new IOException("Incomplete trailer after ETX (checksum/CR/LF missing)");
                    }

                    String receivedChecksum = "" + (char) checksumHigh + (char) checksumLow;

                    // Step 3.5: Compute expected checksum (sum of all bytes from payload + ETX, modulo 256)
                    int checksumAccumulator = 0;
                    byte[] payloadBytes = framePayload.toByteArray();
                    for (byte pb : payloadBytes) checksumAccumulator += pb;
                    checksumAccumulator += ETX; // ETX included in checksum per E1381
                    checksumAccumulator &= 0xFF;
                    String expectedChecksum = String.format("%02X", checksumAccumulator);

                    // Step 3.6: ACK/NAK depending on checksum
                    if (!receivedChecksum.equalsIgnoreCase(expectedChecksum)) {
                        logger.warn("Checksum mismatch: expected {} but got {}", expectedChecksum, receivedChecksum);
                        outputStream.write(NAK);
                        outputStream.flush();
                        // continue waiting for a retransmission of the same frame
                        continue;
                    } else {
                        outputStream.write(ACK);
                        outputStream.flush();
                    }

                    // Step 3.7: Append frame payload to the complete ASTM message (CR as segment delimiter)
                    assembledMessage.write(payloadBytes);
                    if (payloadBytes.length == 0 || payloadBytes[payloadBytes.length - 1] != '\r') {
                        assembledMessage.write('\r'); // ensure single CR delimiter
                    }
                }

                // Step 4: Build the multi-line ASTM message and route to LAB-27/LAB-29 logic
                String astmMessage = assembledMessage.toString(StandardCharsets.US_ASCII).trim();
                if (astmMessage.isEmpty()) {
                    logger.warn("Empty ASTM message received — ignored.");
                    continue;
                }

                logger.info("DEBUG: Complete ASTM message:\n{}", astmMessage.replace("\r", "\n"));

                // Step 5: Process message; if a response is produced, perform ASTM turnaround (sendASTMMessage)
                String responseMessage = processAnalyzerMsg(astmMessage);
                if (responseMessage != null && !responseMessage.isEmpty()) {
                    logger.info(">>> Sending ASTM response (turnaround):\n{}", responseMessage.replace("\r", "\n"));
                    String[] responseLines = responseMessage.replaceAll("[\\u000d\\u000a]+", "\n").split("\n");
                    sendASTMMessage(responseLines); // ENQ → ACK → frames → EOT
                } else {
                    logger.warn("No response generated for received ASTM message.");
                }

            } catch (SocketTimeoutException timeoutEx) {
                // Step 6: Timeout waiting for bytes — keep the listener alive
                logger.warn("No data received within 15000 ms — continuing to wait...");
                continue;
            } catch (IOException ioEx) {
                // Step 7: Fatal I/O error — stop listening on this socket
                this.listening.set(false);
                logger.error("Exception in listenForIncomingMessages (ASTM): {}", ioEx.getMessage(), ioEx);
            }
        }
    }

    /**
     * Dispatches the received ASTM message to the appropriate LAB transaction handler.
     * Identifies the type of message by detecting H| (result) or Q| (query) segments.
     *
     * @param receivedMessage Raw ASTM message (decoded, multi-line string)
     * @return Response message (ASTM or HL7), or null if unrecognized or invalid
     */
    private String processAnalyzerMsg(String receivedMessage) {
        try {
            // Normalize to lines
            String[] lines = receivedMessage.replaceAll("[\\u000d\\u000a]+", "\n").split("\n");

            boolean hasH = Arrays.stream(lines).anyMatch(l -> l.matches("^\\d*H\\|.*"));
            boolean hasQ = Arrays.stream(lines).anyMatch(l -> l.matches("^\\d*Q\\|.*"));

            if (hasQ) {
                logger.info("Detected ASTM query message with Q| segment, routing to lab27...");
                return lab27(receivedMessage);
            } else if (hasH) {
                logger.info("Detected ASTM result message with H| segment, routing to lab29...");
                return lab29(receivedMessage);
            } else {
                logger.warn("Received message without recognizable H| or Q| segment, ignored.");
                return null;
            }

        } catch (Exception e) {
            logger.error("ERROR: Exception in processAnalyzerMsg: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Reads an ASTM message from the input stream using ASTM E1381 framing.
     * Waits for STX...ETX frames, extracts the message body, and strips framing bytes.
     * 
     * Note: This method does not validate checksums.
     *
     * @param inputStream Input stream from the socket (e.g., analyzer connection)
     * @return Full ASTM message as a string (CR-delimited segments), or empty string if none
     * @throws IOException If socket read fails or stream is closed
     */
    public static String readASTMMessage(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int byteRead;
        int lastPayloadByte = -1;

        boolean inFrame = false;

        while ((byteRead = inputStream.read()) != -1) {
            if (byteRead == 0x04) { // EOT = End Of Transmission
                break;
            }
            if (byteRead == 0x02) { // STX = Start of Text
                inFrame = true;
                lastPayloadByte = -1;
                continue;
            }
            if (byteRead == 0x03) { // ETX = End of Text
                // Frame done – discard following 2 checksum bytes + CR + LF
                inputStream.read(); // Checksum byte 1
                inputStream.read(); // Checksum byte 2
                inputStream.read(); // CR
                inputStream.read(); // LF
                if (lastPayloadByte != '\r') { // ensure single CR delimiter
                    buffer.write('\r');
                }
                inFrame = false;
                lastPayloadByte = -1; // reset for next frame
                continue;
            }
            if (inFrame) {
                buffer.write(byteRead);
                lastPayloadByte = byteRead;
            }
        }

        String msg = buffer.toString(StandardCharsets.US_ASCII).trim();

        if (!msg.isEmpty()) {
            logger.info("Complete ASTM message received:\n{}", msg.replace("\r", "\n"));
        }

        return msg;
    }
    
    // === utility function ===
    
    /**
     * Safely extracts a timestamp value from a TS field (e.g., PID-7 birth date).
     * Returns empty string if null or invalid.
     */
    private String safe(TS ts) {
        try {
            return ts != null && ts.getTime() != null ? ts.getTime().getValue() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Safely extracts patient name components from an XPN list (e.g., PID-5).
     * If lastName=true → returns family name.
     * If lastName=false → returns given name.
     */
    private String safeXPN(XPN[] list, int index, boolean lastName) {
        try {
            if (list != null && list.length > index && list[index] != null) {
                return lastName
                    ? list[index].getFamilyName().getSurname().getValue()
                    : list[index].getGivenName().getValue();
            }
        } catch (Exception e) {}
        return "";
    }

    /**
     * Safely extracts IDNumber from a CX field (e.g., patient identifiers PID-3).
     * Index allows retrieving primary or alternate patient ID.
     */
    private String safeCX(CX[] list, int index) {
        try {
            if (list != null && list.length > index && list[index] != null) {
                return list[index].getIDNumber().getValue();
            }
        } catch (Exception e) {}
        return "";
    }

    /**
     * Safely extracts telephone number from an XTN list (e.g., PID-13).
     */
    private String safeXTN(XTN[] list, int index) {
        try {
            if (list != null && list.length > index && list[index] != null) {
                return list[index].getTelephoneNumber().getValue();
            }
        } catch (Exception e) {}
        return "";
    }

    /**
     * Safely extracts address components from an XAD list (e.g., PID-11).
     * `part` must be "street", "city", or "zip".
     */
    private String safeXAD(XAD[] list, int index, String part) {
        try {
            if (list != null && list.length > index && list[index] != null) {
                switch (part) {
                    case "street":
                        return list[index].getStreetAddress().getStreetOrMailingAddress().getValue();
                    case "city":
                        return list[index].getCity().getValue();
                    case "zip":
                        return list[index].getZipOrPostalCode().getValue();
                }
            }
        } catch (Exception e) {}
        return "";
    }

    /**
     * Safely extracts value from a primitive IS field (e.g., AdministrativeSex).
     */
    private String safe(IS is) {
        try {
            return is != null ? is.getValue() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Safely extracts value from a primitive ST field (e.g., OBR-4 test name).
     */
    private String safe(ST st) {
        try {
            return st != null ? st.getValue() : "";
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Returns the current date and time formatted as yyyyMMddHHmmss.
     * Used in ASTM messages where a timestamp is required (e.g., header, order).
     *
     * @return Formatted current timestamp (e.g., "20250722143000")
     */
    private static String getCurrentDateTime() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now());
    }
    
    /**
     * Splits a raw ASTM message into lines using CR/LF normalization,
     * and logs each individual line for debugging purposes.
     *
     * @param msg Raw ASTM message as a single string (may include CR/LF or LF)
     * @return Array of message lines (e.g., H|..., P|..., O|..., etc.)
     */
    private String[] logAndSplitAstm(String msg) {
        String[] lines = msg.replaceAll("[\\u000d\\u000a]+", "\n").split("\n");
        for (String l : lines) {
            logger.info("ASTM line: " + l);
        }
        return lines;
    }
    
    /**
     * Checks whether a string is null, empty, or contains only whitespace.
     * Replacement for StringUtils.isBlank() to avoid external dependencies.
     *
     * @param s String to check
     * @return true if the string is null, empty, or whitespace only
     */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

}