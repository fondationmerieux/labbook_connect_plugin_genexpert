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

import com.moandjiezana.toml.Toml;

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
	
	private final String jar_version = "1.0.15";

    // === General Configuration ===
    protected String version = "";
    protected String id_analyzer = "";
    protected String url_upstream_lab27 = "";
    protected String url_upstream_lab29 = "";

    // === Connection Configuration ===
    protected String type_cnx = "";
    protected String type_msg = "";
    protected String archive_msg = "";
    protected String operation_mode = "query";
    protected String mode = "";
    protected String ip_analyzer = "";
    protected int port_analyzer = 0;
    protected String mappingPath = "";
    protected Toml mappingToml = new Toml();

    // === Runtime State ===
    protected AtomicBoolean listening = new AtomicBoolean(false);
    private ServerSocket serverSocket;
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
    private static final byte ETB = 0x17; // End of Transmission Block (multi-frame continuation)

    // Constants mandated by the GeneXpert LIS Interface Protocol Specification, 302-2261 Rev. F (2023-06).
    // Grouped here so that the values granted to the instrument stay consistent across the class.

    /** Maximum text length of a single frame, in bytes (section 3.2.3.1). */
    private static final int MAX_FRAME_TEXT = 240;

    /**
     * Maximum number of times the same frame is sent before the message is
     * aborted (section 3.2.5.1: abort after 6 consecutive rejections).
     */
    private static final int MAX_FRAME_ATTEMPTS = 6;

    /**
     * Time to wait for a reply from the instrument, in milliseconds
     * (section 3.2.5.2). The specification grants the receiver 15 s to reply
     * to a frame or an ENQ. The previous value (10 s) aborted the transfer
     * while the instrument was still within its allowance.
     */
    private static final int REPLY_TIMEOUT_MS = 15000;

    /**
     * Time the receiver waits for a frame or an EOT (section 3.2.5.2).
     * After 30 s the link is considered back in the Neutral State and the
     * incomplete message is discarded.
     */
    private static final int RECEIVE_TIMEOUT_MS = 30000;

    /** Delay required before re-sending an ENQ after a NAK (section 3.2.2.1). */
    private static final int ENQ_RETRY_DELAY_MS = 10000;

    /** Number of link establishment attempts before giving up. */
    private static final int ENQ_MAX_ATTEMPTS = 3;

    private volatile String lastReplyHeader = "";

    /**
     * Number of the last accepted inbound frame, used by the sequence check of section 3.2.5.1: a frame must carry
     * either the number of the last accepted frame, or the next one modulo 8.
     * Holds -1 until the first frame of a transfer has been accepted.
     */
    private int lastAcceptedFrameNo = -1;
    
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
        newAnalyzer.setMappingPath(this.mappingPath);
        return newAnalyzer;
    }

    @Override
    public String test() {
        return this.getClass().getSimpleName();
    }
    
    @Override
    public String info() {
    	return String.format(
    			"Analyzer Info: [Jar=%s, Version=%s, ID=%s, Lab27=%s, Lab29=%s, TypeCnx=%s, TypeMsg=%s, ArchiveMsg=%s, MappingPath=%s, OperationMode=%s, Mode=%s, IP=%s, Port=%d]",
    			this.jar_version, this.version, this.id_analyzer, this.url_upstream_lab27, this.url_upstream_lab29,
    			this.type_cnx, this.type_msg, this.archive_msg, this.mappingPath, this.operation_mode, this.mode, this.ip_analyzer, this.port_analyzer
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

            // A cancellation carries no order request (section 6.3.1.2). The host only acknowledges at frame level,
            // so returning null here leaves the instrument without a download message.
            if (isQueryCancellation(astmLines)) {
                logger.info("Lab27 GeneXpert : query cancellation received (Q-13 = A), no order returned");
                return null;
            }

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

            if (hl7Ack == null || !hl7Ack.startsWith("MSH|")) {
                logger.error("Lab29 GeneXpert : upstream returned non-HL7 or null; returning ASTM NACK. First 80 chars: {}",
                             hl7Ack != null ? hl7Ack.substring(0, Math.min(80, hl7Ack.length())) : "null");
                return "L|1|N";
            }
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
            
            String[] orderFields = new String[26];
            Arrays.fill(orderFields, "");

            orderFields[0] = "O";
            orderFields[1] = "1";
            orderFields[2] = specimenId;
            orderFields[4] = "^^^" + testCode + "^" + assayName + "^" + assayVersion + "^^";
            orderFields[5] = specimenType;
            orderFields[7] = now;
            orderFields[12] = "A";
            orderFields[15] = "ORH";
            orderFields[25] = "Q";

            lines.add(String.join("|", orderFields));
            lines.add("L|1|F");

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

            // === MSH (built manually) ===
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

            // Mapping context for the current order (O) to map subsequent results (R)
            String currentTestName = "";
            String currentLisTestCode = "";

            for (String line : lines) {
                String[] fields = line.split("\\|", -1);

                switch (fields[0]) {
                case "P":
                    patientId = (fields.length > 2) ? fields[2] : null;
                    hl7.append("PID|||").append(patientId != null ? patientId : "")
                       .append("||").append("\r");
                    break;

                case "O":
                    // O: order/specimen identifier from ASTM
                    specimenId = (fields.length > 2 && fields[2] != null) ? fields[2].trim() : "";
                    logger.info("convertASTMtoOUL_R22: specimenId from ASTM O segment = '{}'", specimenId);

                    // Resolve test from mapping using O|5 (fields[4]) vendor test code
                    String vendorTestCode = "";
                    if (fields.length > 4 && fields[4] != null) {
                        String[] comps = fields[4].split("\\^", -1);
                        for (int i = comps.length - 1; i >= 0; i--) {
                            String s = (comps[i] == null) ? "" : comps[i].trim();
                            if (!s.isEmpty()) {
                                vendorTestCode = s;
                                break;
                            }
                        }
                    }

                    currentTestName = "";
                    currentLisTestCode = "";
                    List<Toml> tests = mappingToml.getTables("ivd_test");
                    if (tests != null && !vendorTestCode.isEmpty()) {
                        for (Toml t : tests) {
                            String v = t.getString("vendor_test_code");
                            if (v != null && v.trim().equals(vendorTestCode)) {
                                String n = t.getString("name");
                                String lis = t.getString("lis_test_code");
                                currentTestName = (n == null) ? "" : n.trim();
                                currentLisTestCode = (lis == null) ? "" : lis.trim();
                                break;
                            }
                        }
                    }

                    // SPM must carry the specimen ID in SPM-2 so LabBook can resolve the sample
                    hl7.append("SPM|1|")
                       .append(specimenId)
                       .append("\r");

                    // ORC with placer order number = specimenId
                    hl7.append("ORC|RE|")
                       .append(specimenId)
                       .append("\r");

                    // OBR with same placer order number; test code from mapping if found, otherwise from O|5
                    hl7.append("OBR|1|")
                       .append(specimenId)
                       .append("||");
                    if (!currentLisTestCode.isEmpty()) {
                        hl7.append("^^^").append(currentLisTestCode);
                    } else if (fields.length > 4 && fields[4] != null) {
                        hl7.append(fields[4]); // ^^^code^text^ver
                    }
                    hl7.append("\r");
                    break;

                case "R":
                    // ASTM R fields:
                    // 0: "R"
                    // 1: sequence
                    // 2: test id (maps to OBX-3)
                    // 3: result value (may start with '^')
                    // 4: units
                    // 5: reference range
                    // 6: abnormal flags (ignored)
                    // 7: nature of abnormal test (ignored)
                    // 8: status (F, P, etc.)

                	String vendorResultCode = (fields.length > 2 && fields[2] != null) ? fields[2].trim() : "";

                    String lisResultCode = "";
                    String lisUnit = "";
                    String convert = "none";
                    double factor = 0.0;

                    List<Toml> maps = mappingToml.getTables("ivd_mapping");
                    if (maps != null && !currentTestName.isEmpty() && !vendorResultCode.isEmpty()) {
                        for (Toml m : maps) {
                            String t = m.getString("test");
                            String vrc = m.getString("vendor_result_code");
                            if (t != null && vrc != null
                                    && t.trim().equals(currentTestName)
                                    && vrc.trim().equals(vendorResultCode)) {

                                String lrc = m.getString("lis_result_code");
                                lisResultCode = (lrc == null) ? "" : lrc.trim();

                                String lu = m.getString("lis_unit");
                                lisUnit = (lu == null) ? "" : lu.trim();

                                String cv = m.getString("convert");
                                convert = (cv == null) ? "none" : cv.trim();

                                factor = 0.0;
                                try {
                                    Object factorObj = m.toMap().get("factor");
                                    if (factorObj instanceof Number) {
                                        factor = ((Number) factorObj).doubleValue();
                                    } else if (factorObj instanceof String) {
                                        String s = ((String) factorObj).trim();
                                        if (!s.isEmpty()) {
                                            factor = Double.parseDouble(s.replace(",", "."));
                                        }
                                    }
                                } catch (Exception ignore) {
                                    factor = 0.0;
                                }

                                break;
                            }
                        }
                    }

                    hl7.append("OBX|").append(obxIndex).append("|TX|");
                    if (!lisResultCode.isEmpty()) {
                        hl7.append(lisResultCode);
                    } else if (fields.length > 2) {
                        hl7.append(fields[2]); // OBX-3 (fallback)
                    }
                    hl7.append("||"); // OBX-4 empty

                    // OBX-5: value
                    String value = "";
                    if (fields.length > 3 && fields[3] != null) {
                        String raw = fields[3];
                        String[] comps = raw.split("\\^", -1);
                        for (String c : comps) {
                            if (c != null && !c.isEmpty()) {
                                value = c;
                                break;
                            }
                        }
                    }
                    value = value.trim();

                    // OBX-6: units
                    String units = "";
                    if (fields.length > 4 && fields[4] != null) {
                        units = fields[4].trim();
                    }

                    // Override units from mapping if provided
                    if (!lisUnit.isEmpty()) {
                        units = lisUnit;
                    }

                    // Apply conversion if configured and value is numeric
                    if (value != null) {
                        String vtrim = value.trim();
                        if (!vtrim.isEmpty() && !"none".equalsIgnoreCase(convert)) {
                            try {
                                double num = Double.parseDouble(vtrim.replace(",", "."));

                                if ("multiply".equalsIgnoreCase(convert)) {
                                    num = num * factor;
                                    value = String.valueOf(num);
                                } else if ("divide".equalsIgnoreCase(convert)) {
                                    if (factor != 0.0) {
                                        num = num / factor;
                                        value = String.valueOf(num);
                                    }
                                } else if ("add".equalsIgnoreCase(convert)) {
                                    num = num + factor;
                                    value = String.valueOf(num);
                                } else if ("subtract".equalsIgnoreCase(convert)) {
                                    num = num - factor;
                                    value = String.valueOf(num);
                                } else if ("log10".equalsIgnoreCase(convert)) {
                                    if (num > 0.0) {
                                        num = Math.log10(num);
                                        value = String.valueOf(num);
                                    }
                                }

                            } catch (Exception e) {
                                logger.info("convertASTMtoOUL_R22: non numeric value for conversion vendorResultCode={} value={}", vendorResultCode, vtrim);
                            }
                        }
                    }

                    // OBX-7: reference range
                    String refRange = "";
                    if (fields.length > 5 && fields[5] != null) {
                        refRange = fields[5].trim();
                    }
                    
                    // Build "< value" from reference range lower bound
                    if (!value.isEmpty() && !refRange.isEmpty() && refRange.contains("to")) {
                        String[] parts = refRange.split("to");
                        if (parts.length == 2) {
                            value = value + " < " + parts[0].trim();
                        }
                    }

                    // OBX-11: status (we will place it in field 11)
                    String status = "F";
                    if (fields.length > 8 && fields[8] != null && !fields[8].trim().isEmpty()) {
                        status = fields[8].trim();
                    }

                 // Build OBX segment fields (HL7 v2.5.1):
                 // OBX-5 = observation value
                 // OBX-6 = units
                 // OBX-7 = reference range
                 // OBX-8..10 = intentionally left empty (no abnormal flags / probability / nature)
                 // OBX-11 = observation result status (F, P, etc.)
                 hl7.append(value)
                     .append("|").append(units)
                     .append("|").append(refRange)
                     .append("|")   // OBX-8 (abnormal flags - not used)
                     .append("|")   // OBX-9 (probability - not used)
                     .append("|")   // OBX-10 (nature of abnormal test - not used)
                     .append("|").append(status) // OBX-11 (result status)
                     .append("\r");

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
        	if (hl7Ack == null || !hl7Ack.startsWith("MSH|")) {
                logger.error("convertACKtoASTM: Non-HL7 response (no MSH).");
                return "L|1|N";
            }
        	
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
     * Tells whether an ASTM query record cancels the previous request.
     * <p>
     * Two Q records look alike but carry opposite meanings:
     * <pre>
     *   Q|1|ALL||||||||||O@N     query for all pending test orders
     *   Q|1|||||||||||A          cancellation of the previous query
     * </pre>
     * Q-13 holds the request information status code. "A" means cancellation (sections 6.3.1.2 and 6.3.2.2), and
     * the accompanying C record states it in plain text.
     *
     * @param lines ASTM records received from the instrument.
     * @return true when the message cancels the previous request.
     */
    private boolean isQueryCancellation(String[] lines) {
        if (lines == null) {
            return false;
        }

        for (String line : lines) {
            if (line == null || !line.matches("^\\d*Q\\|.*")) {
                continue;
            }

            String[] fields = line.split("\\|", -1);

            // Q-13, request information status code
            if (fields.length > 12 && fields[12] != null
                    && "A".equalsIgnoreCase(fields[12].trim())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Converts an ASTM query (Q record) into an HL7 QBP^Q11 message.
     *
     * @param lines ASTM records received from the instrument.
     * @return HL7 QBP^Q11 message in ER7 format, or null when the conversion fails.
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
            qpd.getMessageQueryName().getIdentifier().setValue("LAB-27");
            qpd.getMessageQueryName().getText().setValue("IHE");
            qpd.getQueryTag().setValue("GENEXPERT");

            // Q-3 is a composite field (section 6.3.2):
            //     Patient ID 1 ^ Specimen ID ^ Patient ID 2
            // The specimen identifier is component 2. "ALL" in place of the whole field is a query for every pending order (section 1.8.1).
            String specimenId = "";
            String startingRange = (fields.length > 2 && fields[2] != null) ? fields[2].trim() : "";

            boolean isQueryAll = "ALL".equalsIgnoreCase(startingRange);

            if (!isQueryAll && !startingRange.isEmpty()) {
                String[] comps = startingRange.split("\\^", -1);
                if (comps.length > 1) {
                    specimenId = (comps[1] == null) ? "" : comps[1].trim();
                } else {
                    // No separator: section 1.9.4 states the instrument never
                    // issues a patient-only query, so treat the value as a
                    // specimen identifier.
                    specimenId = comps[0].trim();
                    logger.warn("convertASTMQueryToQBP_Q11: Q-3 has no '^' separator ('{}'), "
                              + "treating it as a specimen identifier", startingRange);
                }
            }


            if (!specimenId.isEmpty()) {
                qpd.getField(3, 0).parse(specimenId);
            } else if (isQueryAll) {
                qpd.getField(3, 0).parse("ALL");
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
     * Converts an HL7 RSP^K11 response into an ASTM reply for GeneXpert.
     *
     * Rule:
     * - Return L|1|Y when the RSP^K11 was processed successfully (even if no orders are present).
     * - Return L|1|N only on technical/parsing errors.
     *
     * @param hl7Message HL7 RSP^K11 message in ER7 format
     * @return Array of ASTM-formatted lines to return to the analyzer
     */
    public String[] convertRSP_K11toASTM(String hl7Message) {
        StringBuilder astm = new StringBuilder();

        if (hl7Message == null || hl7Message.trim().isEmpty() || !hl7Message.startsWith("MSH|")) {
            logger.warn("convertRSP_K11toASTM: invalid HL7 input (null/empty/no MSH)");
            astm.append(isBlank(this.lastReplyHeader) ? buildReplyHeader(null) : this.lastReplyHeader).append("\r");
            astm.append("L|1|N");
            return astm.toString().split("\r");
        }

        try {
        	astm.append(isBlank(this.lastReplyHeader) ? buildReplyHeader(null) : this.lastReplyHeader).append("\r");

            String[] segments = hl7Message.split("\r");

            // Current patient context
            String patientId = "";
            String patientName = "";
            String birthDate = "";
            String sex = "";
            boolean patientHeaderEmitted = false;

            // Current order context (within patient)
            String spmId = "";

            boolean hasAnyOrder = false;

            for (String segment : segments) {
                if (segment.startsWith("PID|")) {
                    // Switch patient context (do NOT emit P here)
                    String[] fields = segment.split("\\|", -1);
                    patientId = (fields.length > 3) ? fields[3] : "";
                    patientName = (fields.length > 5) ? fields[5] : "";
                    birthDate = (fields.length > 7) ? fields[7] : "";
                    sex = (fields.length > 8) ? fields[8] : "";

                    patientHeaderEmitted = false;
                    spmId = "";
                    continue;
                }

                if (segment.startsWith("SPM|")) {
                    String[] fields = segment.split("\\|", -1);
                    spmId = (fields.length > 2) ? fields[2] : "";
                    continue;
                }

                if (segment.startsWith("OBR|")) {
                    String[] fields = segment.split("\\|", -1);

                    //String obrName = "";
                    String lisTestCode = "";
                    if (fields.length > 4) {
                        String[] testInfo = fields[4].split("\\^", -1);

                        // Prefer component 4 (^^^GX01) but accept component 1 (89371-9)
                        if (testInfo.length > 3 && testInfo[3] != null && !testInfo[3].trim().isEmpty()) {
                            lisTestCode = testInfo[3].trim();
                        } else if (testInfo.length > 0 && testInfo[0] != null) {
                            lisTestCode = testInfo[0].trim();
                        }

                        //obrName = (testInfo.length > 1 && testInfo[1] != null) ? testInfo[1].trim() : "";
                    }

                    String obrCode = mapLisTestCodeToVendorTestCode(lisTestCode);

                    if (isBlank(spmId)) {
                        logger.warn("convertRSP_K11toASTM: OBR received but spmId is empty (OBR-4='{}')", (fields.length > 4 ? fields[4] : ""));
                        continue;
                    }

                    if (isBlank(obrCode)) {
                        logger.warn("convertRSP_K11toASTM: unmapped lisTestCode='{}' (OBR-4='{}')", lisTestCode, (fields.length > 4 ? fields[4] : ""));
                        continue;
                    }

                    // Emit patient only when we actually have a mapped order to send
                    if (!patientHeaderEmitted) {
                        if (isBlank(patientId)) {
                            logger.warn("convertRSP_K11toASTM: mapped order found but patientId is empty; skipping patient block");
                            continue;
                        }

                        // P record field positions (section 6.4.2):
                        //   P-3 = Patient ID 2 (practice-assigned identifier)
                        //   P-5 = Patient ID 1 (patient identification)
                        //   P-6 = Patient Name (family^given^...)
                        //   P-8 = Birthdate
                        //   P-9 = Sex
                        // Resulting layout: [P][1][id][][][name][][birth][sex]
                        astm.append("P|1|").append(patientId).append("|||").append(patientName).append("||")
                            .append(birthDate).append("|").append(sex).append("\r");
                        patientHeaderEmitted = true;
                    }

                    String[] orderFields = new String[26];
                    Arrays.fill(orderFields, "");

                    orderFields[0] = "O";
                    orderFields[1] = "1";
                    orderFields[2] = spmId;
                    orderFields[4] = "^^^" + obrCode;
                    orderFields[5] = "R"; // Priority
                    orderFields[6] = getCurrentDateTime();
                    orderFields[11] = "A";
                    orderFields[15] = "ORH";
                    orderFields[25] = "Q";

                    astm.append(String.join("|", orderFields)).append("\r");

                    hasAnyOrder = true;
                }
            }

            // L-3 termination code (section 6.3.1):
            //   orders are provided -> F
            //   no order available  -> I, with no P and no O record
            if (hasAnyOrder) {
                astm.append("L|1|F");
            } else {
                logger.info("convertRSP_K11toASTM: no usable order in the RSP^K11 - emitting L|1|I");
                astm.append("L|1|I");
            }

            return astm.toString().split("\r");

        } catch (Exception e) {
            logger.error("convertRSP_K11toASTM: exception - " + e.getMessage(), e);
            astm.setLength(0);
            astm.append(isBlank(this.lastReplyHeader) ? buildReplyHeader(null) : this.lastReplyHeader).append("\r");
            astm.append("L|1|N");
            return astm.toString().split("\r");
        }
    }

    // === Communication Management ===
    
    /**
     * Sends an ASTM message to the instrument over the active socket.
     * <p>
     * The whole message is assembled first, then split into frames of at most {@link #MAX_FRAME_TEXT} bytes, per section 3.2.3.1:
     * <pre>
     *   intermediate frame : &lt;STX&gt; FN Text &lt;ETB&gt; C1 C2 &lt;CR&gt; &lt;LF&gt;
     *   end frame          : &lt;STX&gt; FN Text &lt;ETX&gt; C1 C2 &lt;CR&gt; &lt;LF&gt;
     * </pre>
     * Every record inside Text is terminated by &lt;CR&gt;.
     * <p>
     * A rejected frame is re-sent up to {@link #MAX_FRAME_ATTEMPTS} times before the message is aborted
     * (section 3.2.5.1). Every abort path goes through the Termination Phase.
     *
     * @param lines ASTM records (H|..., P|..., O|..., L|...)
     * @return "ACK" if the whole message was accepted; otherwise "NAK",
     *         "CONTENTION" (instrument has priority), "INTERRUPTED"
     *         (instrument requested the sender to stop), "UNKNOWN" or "ERROR".
     */
    public String sendASTMMessage(String[] lines) {
        // Section 3.2.3.1 defines a frame as a slice of at most 240 bytes of the MESSAGE TEXT, not as an ASTM record:
        //   - every record inside the text is terminated by <CR>
        //   - all frames but the last one end with <ETB>
        //   - only the last one ends with <ETX>
        // The worked example of section 3.2.7 shows frame boundaries falling in the middle of a record.
        //
        // Checksum: sum of frame number, text and terminator, modulo 256.
        try {
            // STEP 1: Rebuild the message text. Every record is CR-terminated
            //    (section 3.2.3.1).
            StringBuilder messageText = new StringBuilder();
            for (String line : lines) {
                if (line == null || line.isEmpty()) {
                    continue;
                }
                messageText.append(line).append((char) CR);
            }

            byte[] textBytes = messageText.toString().getBytes(StandardCharsets.US_ASCII);
            if (textBytes.length == 0) {
                logger.warn("sendASTMMessage: empty message, nothing to send");
                return "ERROR";
            }

            // STEP 2: Establishment Phase (section 3.2.2).
            String establish = establishLink();
            if (!"ACK".equals(establish)) {
                return establish;
            }

            // STEP 3: Transfer Phase, split into MAX_FRAME_TEXT slices.
            //    Section 3.2.3.1: numbering starts at 1, increments by one per
            //    frame, and wraps back to 0 after 7.
            int frameNo = 1;
            int frameCount = (textBytes.length + MAX_FRAME_TEXT - 1) / MAX_FRAME_TEXT;
            logger.info(">>> Sending {} bytes in {} frame(s)", textBytes.length, frameCount);

            for (int offset = 0; offset < textBytes.length; offset += MAX_FRAME_TEXT) {
                int len = Math.min(MAX_FRAME_TEXT, textBytes.length - offset);
                boolean isLast = (offset + len) >= textBytes.length;
                byte terminator = isLast ? ETX : ETB;

                String status = sendFrame(frameNo, textBytes, offset, len, terminator);
                if (!"ACK".equals(status)) {
                    // Section 3.2.5.1: any aborted message must go through the
                    // Termination Phase to return the link to Neutral State.
                    terminateLink();
                    return status;
                }
                frameNo = (frameNo + 1) % 8;
            }

            // STEP 4: Termination Phase (section 3.2.4).
            terminateLink();
            return "ACK";

        } catch (IOException e) {
            logger.error("ASTM send error: " + e.getMessage());
            return "ERROR";
        }
    }

    /**
     * Establishment Phase (section 3.2.2).
     * <p>
     * Waits {@link #REPLY_TIMEOUT_MS} for the instrument to answer the ENQ. On NAK, waits at least 10 s and sends a
     * new ENQ (section 3.2.2.1). An ENQ received in reply to an ENQ signals contention (section 3.2.2.2): the
     * instrument has priority, the host must stop transmitting and prepare to receive.
     *
     * @return "ACK" once the link is established, otherwise a failure label.
     * @throws IOException on network error.
     */
    private String establishLink() throws IOException {
        for (int attempt = 1; attempt <= ENQ_MAX_ATTEMPTS; attempt++) {
            logger.info(">>> Sending ENQ (attempt {}/{})", attempt, ENQ_MAX_ATTEMPTS);
            outputStream.write(ENQ);
            outputStream.flush();

            int response;
            try {
                socket.setSoTimeout(REPLY_TIMEOUT_MS);
                response = inputStream.read();
            } catch (SocketTimeoutException e) {
                logger.warn("No reply to ENQ within {} ms - entering Termination Phase", REPLY_TIMEOUT_MS);
                terminateLink();
                return "ERROR";
            }

            if (response == ACK) {
                logger.info("<<< ACK - link established");
                return "ACK";
            }

            if (response == NAK) {
                logger.warn("<<< NAK on ENQ - instrument not ready, retrying in {} ms", ENQ_RETRY_DELAY_MS);
                sleepQuietly(ENQ_RETRY_DELAY_MS);
                continue;
            }

            if (response == ENQ) {
                // Section 3.2.2.2: the instrument has priority. The host gives
                // up sending and goes back to listening; the receive loop will
                // handle the inbound message.
                logger.warn("<<< ENQ in reply to ENQ - contention, instrument has priority");
                return "CONTENTION";
            }

            logger.warn("<<< Unexpected byte in reply to ENQ: {}", response);
            terminateLink();
            return "UNKNOWN";
        }

        logger.error("Link establishment abandoned after {} attempts", ENQ_MAX_ATTEMPTS);
        return "NAK";
    }

    /**
     * Sends one frame and handles its retransmission (section 3.2.5.1).
     * <p>
     * A rejected frame is re-sent with the same frame number, and the transfer is aborted only
     * after {@link #MAX_FRAME_ATTEMPTS} consecutive rejections.
     * <p>
     * An EOT received in reply is a positive acknowledgment carrying an interrupt request (section 3.2.3.1,
     * "Receiver Interrupts"): the frame is accepted, but the instrument asks the sender to stop.
     *
     * @param frameNo    frame number (0-7).
     * @param text       full message text.
     * @param offset     start of the slice within {@code text}.
     * @param len        slice length.
     * @param terminator {@code ETB} (intermediate frame) or {@code ETX} (end frame).
     * @return "ACK" if the frame was accepted, otherwise a failure label.
     * @throws IOException on network error.
     */
    private String sendFrame(int frameNo, byte[] text, int offset, int len, byte terminator)
            throws IOException {

        for (int attempt = 1; attempt <= MAX_FRAME_ATTEMPTS; attempt++) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            int frameDigit = '0' + frameNo;

            frame.write(STX);
            frame.write(frameDigit);
            frame.write(text, offset, len);
            frame.write(terminator);

            // Checksum: frame number + text + terminator, modulo 256
            // (section 3.2.3.1). STX, the checksum characters and the trailing
            // CR/LF are excluded.
            int checksum = frameDigit;
            for (int i = offset; i < offset + len; i++) {
                checksum += (text[i] & 0xFF);
            }
            checksum += (terminator & 0xFF);
            checksum &= 0xFF;

            frame.write(String.format("%02X", checksum).getBytes(StandardCharsets.US_ASCII));
            frame.write(CR);
            frame.write(LF);

            logger.info(">>> Frame {} ({} bytes, {}) attempt {}/{}",
                        frameNo, len, (terminator == ETX ? "ETX" : "ETB"), attempt, MAX_FRAME_ATTEMPTS);
            outputStream.write(frame.toByteArray());
            outputStream.flush();

            int reply;
            try {
                socket.setSoTimeout(REPLY_TIMEOUT_MS);
                reply = inputStream.read();
            } catch (SocketTimeoutException e) {
                logger.warn("No reply to frame {} within {} ms - aborting message",
                            frameNo, REPLY_TIMEOUT_MS);
                return "ERROR";
            }

            if (reply == ACK) {
                return "ACK";
            }

            if (reply == EOT) {
                logger.info("<<< EOT - frame accepted, instrument requests the sender to stop");
                return "INTERRUPTED";
            }

            if (reply == NAK) {
                logger.warn("<<< NAK on frame {} - retransmitting", frameNo);
            } else {
                logger.warn("<<< Unexpected byte after frame {}: {} - retransmitting", frameNo, reply);
            }
            // Section 3.2.5.1: any byte other than ACK or EOT counts as a
            // rejection and increments the retransmission counter.
        }

        logger.error("Frame {} rejected {} times - aborting message", frameNo, MAX_FRAME_ATTEMPTS);
        return "NAK";
    }

    /**
     * Termination Phase (section 3.2.4). Sends EOT to return the link to the Neutral State, where either side may
     * take the initiative again. Called from every abort path so that the link is never left taken.
     *
     * @throws IOException on network error.
     */
    private void terminateLink() throws IOException {
        logger.info(">>> Sending EOT");
        outputStream.write(EOT);
        outputStream.flush();
    }

    /**
     * Sleeps without propagating the interruption as an exception, for the delays mandated by the specification.
     */
    private void sleepQuietly(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Gets the mapping configuration path.
     * @return The mapping configuration path.
     */
    @Override
    public String getMappingPath() {
        return this.mappingPath;
    }

    /**
     * Sets the mapping configuration path.
     * @param mappingPath The mapping configuration path.
     */
    @Override
    public void setMappingPath(String mappingPath) {
        this.mappingPath = (mappingPath == null) ? "" : mappingPath.trim();
    }
    
    /**
     * Starts the analyzer communication thread.
     *
     * SERVER mode is the validated deployment mode (socket accept loop + ASTM E1381 FSM per connection).
     * CLIENT mode exists for compatibility tests and is considered experimental.
     */
    @Override
    public void listenDevice() {
    	logger.info("DEBUG: this.type_cnx = " + this.type_cnx);
    	logger.info("DEBUG: this.mode = " + this.mode);
    	logger.info("Connecting to analyzer at " + ip_analyzer + ":" + port_analyzer);
    	
    	this.mappingToml = Connect_util.loadMappingToml(this.getMappingPath());

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

    			this.listening.set(true);
    			while (this.listening.get()) {
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
    	this.listening.set(true);
        while (this.listening.get()) {
            try {
            	this.serverSocket = new ServerSocket(this.port_analyzer);
                logger.info("ASTM Server started on port {}", this.port_analyzer);

                while (true) {
                    try (Socket clientSocket = serverSocket.accept()) {
                        logger.info("Accepted connection from {}", clientSocket.getInetAddress());
                        this.socket = clientSocket;
                        this.inputStream = clientSocket.getInputStream();
                        this.outputStream = clientSocket.getOutputStream();
                        listenForIncomingMessages();
                    } catch (IOException ioEx) {
                        logger.error("ERROR: Client handling failed: {}", ioEx.getMessage(), ioEx);
                    } finally {
                        this.socket = null; 
                        this.inputStream = null; 
                        this.outputStream = null;                        
                        logger.info("Client connection closed.");
                    }
                }
            } catch (IOException startEx) {
                this.listening.set(false);
                try { if (this.socket != null) this.socket.close(); } catch (IOException ignore) {}
                this.socket = null;
                logger.error("ERROR: Failed to start ASTM server on port {}: {}", this.port_analyzer, startEx.getMessage());
                break;
            } finally {
                try {
                    if (this.serverSocket != null && !this.serverSocket.isClosed()) {
                        this.serverSocket.close();
                    }
                } catch (IOException e) {
                    logger.warn("Error while closing serverSocket in finally: " + e.getMessage(), e);
                } finally {
                    this.serverSocket = null;
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
            case 0x17: return "ETB";
            default: return ".";
        }
    }

    /**
     * Listens for incoming ASTM messages using ASTM E1381 framing.
     *
     * STEP 1: Wait for ENQ from the analyzer (15s timeout) and reply with ACK.
     * STEP 2: Receive one or more frames:
     *         STX + frame number + payload + ETX/ETB + checksum + CR + LF.
     * STEP 3: Validate checksum for each frame and reply ACK or NAK
     *         (ETB = continuation frame, ETX = final frame).
     * STEP 4: Assemble the complete ASTM message until EOT is received.
     * STEP 5: Dispatch the message to LAB-27 or LAB-29 depending on content.
     * STEP 6: If a response is produced, send it back to the analyzer on the same connection.
     *
     * This method is blocking and runs while the socket is open and listening is enabled.
     */
    private void listenForIncomingMessages() {
    	// Loop while the socket is alive; per-connection FSM
        while (socket != null && !socket.isClosed()) {
            try {
                // STEP 1: Wait for ENQ
                // Section 3.2.5.2 grants the receiver 30 s before the link is considered back in the Neutral State.
                socket.setSoTimeout(RECEIVE_TIMEOUT_MS);

                // Frame numbering restarts at 1 for each transfer (section 3.2.3.1), so the sequence
                // check is reset at every Establishment Phase.
                this.lastAcceptedFrameNo = -1;

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

                // STEP 2: ACK the ENQ to start the transfer
                outputStream.write(ACK);
                outputStream.flush();
                logger.info(">>> Sent ACK [0x06] in response to ENQ");

                // STEP 3: Receive frames until EOT
                ByteArrayOutputStream assembledMessage = new ByteArrayOutputStream();

                framesLoop:
                while (true) {
                    int b = inputStream.read();
                    if (b == -1) throw new IOException("Stream closed while waiting for STX/EOT");
                    logger.info("<<< DEBUG BYTE 0x{} ({})", String.format("%02X", b), printable(b));

                    // STEP 3.1: End of transmission?
                    if (b == EOT) {
                        logger.info("<<< Received EOT — message transmission complete");
                        break framesLoop;
                    }

                    // STEP 3.2: Expect STX to begin a frame
                    if (b != STX) {
                        logger.warn("Expected STX or EOT, got: {}", printable(b));
                        continue; // ignore noise and keep reading
                    }

                    // STEP 3.3: Read frame number (ASCII '0'..'7' typically)
                    int frameNo = inputStream.read();
                    if (frameNo < 0) throw new IOException("Frame aborted: missing frame number after STX");

                    // STEP 3.4: Read payload up to ETX or ETB (terminator not included in payload)
                    ByteArrayOutputStream frameContent = new ByteArrayOutputStream();
                    int c;
                    while (true) {
                        c = inputStream.read();
                        if (c < 0) throw new IOException("Frame aborted: stream closed before ETX/ETB");
                        if (c == ETX || c == ETB) break; // end of text for this frame
                        frameContent.write(c);
                    }
                    byte terminator = (byte) c; // ETX (final) or ETB (more frames follow)

                    // STEP 3.5: Read checksum (2 ASCII hex) + CR + LF
                    int c1 = inputStream.read();
                    int c2 = inputStream.read();
                    int cr = inputStream.read();
                    int lf = inputStream.read();
                    if (c1 < 0 || c2 < 0 || cr < 0 || lf < 0) {
                        throw new IOException("Incomplete trailer after ETX/ETB (checksum/CR/LF missing)");
                    }
                    if (cr != CR || lf != LF) {
                        throw new IOException(String.format("Invalid trailer bytes: CR=0x%02X LF=0x%02X", cr, lf));
                    }
                    String receivedChecksum = "" + (char) c1 + (char) c2;

                    // STEP 3.6: Compute checksum over [frameNo + payload + terminator]
                    int sum = (frameNo & 0xFF);
                    byte[] payloadBytes = frameContent.toByteArray();
                    for (byte pb : payloadBytes) sum += (pb & 0xFF);
                    sum += (terminator & 0xFF);
                    sum &= 0xFF;
                    String expectedChecksum = String.format("%02X", sum);

                    // STEP 3.7: ACK/NAK the frame based on checksum validity
                    if (!receivedChecksum.equalsIgnoreCase(expectedChecksum)) {
                        logger.warn("Checksum mismatch: expected {} but got {}", expectedChecksum, receivedChecksum);
                        outputStream.write(NAK);
                        outputStream.flush();
                        // Wait for retransmission of the same frame; do not append to assembly
                        continue;
                    }

                    // Frame number check (section 3.2.5.1). A frame is accepted only when it carries the number of
                    // the last accepted frame (a retransmission after a NAK) or the next one modulo 8.
                    //
                    // A retransmission is acknowledged but not appended again, otherwise the reassembled message
                    // would silently contain a duplicated fragment.
                    int frameDigit = frameNo - '0';
                    boolean isRetransmission = (frameDigit == this.lastAcceptedFrameNo);
                    boolean isNext = (this.lastAcceptedFrameNo < 0)
                                   || (frameDigit == ((this.lastAcceptedFrameNo + 1) % 8));

                    if (!isRetransmission && !isNext) {
                        logger.warn("Out-of-sequence frame number: got {}, last accepted {} - NAK",
                                    frameDigit, this.lastAcceptedFrameNo);
                        outputStream.write(NAK);
                        outputStream.flush();
                        continue;
                    }

                    outputStream.write(ACK);
                    outputStream.flush();

                    if (isRetransmission) {
                        logger.info("Frame {} already accepted - retransmission acknowledged, not reassembled",
                                    frameDigit);
                        continue;
                    }

                    this.lastAcceptedFrameNo = frameDigit;

                    // STEP 3.8: Append frame payload into the assembled message (NO extra delimiter here)
                    // The payload already contains CR between ASTM records; frames can split a record arbitrarily.
                    // Do NOT inject CR here, or you will break records that continue in the next frame.
                    // Frames may split an ASTM record across multiple frames.
                    assembledMessage.write(payloadBytes);

                    // NOTE: If terminator == ETB, there will be continuation frames before EOT.
                    // We keep looping: next expected bytes are STX ... until EOT arrives.
                }
                
                // STEP 4: Build full ASTM message string
                // Normalize assembled bytes to String; collapse CRLF to CR defensively.
                byte[] assembled = assembledMessage.toByteArray();
                String astmMessage = new String(assembled, StandardCharsets.US_ASCII)
                        .replace("\r\n", "\r")
                        .trim();

                if (astmMessage.isEmpty()) {
                    logger.warn("Empty ASTM message received — ignored.");
                    continue;
                }
                logger.info("DEBUG: Complete ASTM message:\n{}", astmMessage.replace("\r", "\n"));
                
                this.lastReplyHeader = buildReplyHeader(astmMessage);

                // STEP 5: Dispatch to LAB-27/LAB-29; if response produced, do ASTM turnaround send
                String responseMessage = processAnalyzerMsg(astmMessage);
                if (responseMessage != null && !responseMessage.isEmpty()) {
                    logger.info(">>> Sending ASTM response (turnaround):\n{}", responseMessage.replace("\r", "\n"));
                    String[] responseLines = responseMessage.replaceAll("[\\u000d\\u000a]+", "\n").split("\n");
                    sendASTMMessage(responseLines); // ENQ → ACK → frames → EOT
                } else {
                    logger.warn("No response generated for received ASTM message.");
                }

            } catch (SocketTimeoutException timeoutEx) {
                // STEP 6: No byte received in the window - keep waiting
                // (RECEIVE_TIMEOUT_MS, section 3.2.5.2).
                logger.warn("No data received within {} ms - continuing to wait...", RECEIVE_TIMEOUT_MS);
                continue;

            } catch (IOException ioEx) {
                // STEP 7: Fatal I/O — stop listening on this socket
            	this.listening.set(false);
                logger.error("Exception in listenForIncomingMessages (ASTM): {}", ioEx.getMessage(), ioEx);
            }
        }
    }

    /**
     * Dispatches a decoded ASTM message to the appropriate LAB handler.
     *
     * Routing rules:
     * - presence of a Q| segment → LAB-27
     * - otherwise, presence of an H| segment → LAB-29
     * - otherwise, the message is ignored
     *
     * @param receivedMessage Raw ASTM message (CR-delimited)
     * @return ASTM response message or null if no response is required
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
    
    @Override
    public void stopListening() {
    	this.listening.set(false);

        try {
            if (this.socket != null && !this.socket.isClosed()) {
            	this.socket.close();
            }
        } catch (IOException e) {
            logger.warn("stopListening: error while closing client socket: " + e.getMessage(), e);
        } finally {
        	this.socket = null;
        	this.inputStream = null;
        	this.outputStream = null;
        }

        try {
            if (this.serverSocket != null && !this.serverSocket.isClosed()) {
            	this.serverSocket.close();
            }
        } catch (IOException e) {
            logger.warn("stopListening: error while closing server socket: " + e.getMessage(), e);
        } finally {
        	this.serverSocket = null;
        }
    }
    
    // === Utility methods ===
    
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
     * If lastName=true: returns family name.
     * If lastName=false: returns given name.
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
    
    private String mapLisTestCodeToVendorTestCode(String lisTestCode) {
        if (lisTestCode == null) return "";
        String key = lisTestCode.trim();
        if (key.isEmpty()) return "";

        List<Toml> tests = mappingToml.getTables("ivd_test");
        if (tests == null) return "";

        for (Toml t : tests) {
            String lis = t.getString("lis_test_code");
            if (lis != null && lis.trim().equalsIgnoreCase(key)) {
                String vendor = t.getString("vendor_test_code");
                return (vendor == null) ? "" : vendor.trim();
            }
        }
        return "";
    }
    
    /**
     * Builds an ASTM H| header for the reply message.
     *
     * If an inbound ASTM message is provided, this method:
     * - extracts the first H| line,
     * - removes any ASTM frame prefix number (0–7),
     * - keeps all original header fields,
     * - replaces only the last field (date/time) with the current timestamp.
     *
     * If no valid H| line is found, a default GeneXpert-compatible
     * header is generated.
     *
     * This ensures the reply header stays aligned with the analyzer
     * format (e.g. H|@^\ vs H|\^&) and avoids hard-coded assumptions.
     */
    private String buildReplyHeader(String inboundMessage) {
        String now = getCurrentDateTime();

        if (inboundMessage != null) {
            String[] lines = inboundMessage.replace("\r\n", "\r").split("\r");
            for (String line : lines) {
                if (line == null) continue;

                // Remove frame number if present (e.g. "1H|...")
                line = line.replaceFirst("^[0-7](?=[A-Z]\\|)", "").trim();

                if (line.startsWith("H|")) {
                    String[] fields = line.split("\\|", -1);
                    
                    fields[2] = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSS"));

                    if (fields.length > 13) {
                        String tmp = fields[4];   // H.5
                        fields[4] = fields[9];    // H.10
                        fields[9] = tmp;
                    }

                    fields[fields.length - 1] = now;

                    return String.join("|", fields);
                }
            }
        }

        // Fallback header, used when no usable H| record was found above. GeneXpert declares its own delimiters,
        // "@^\\" rather than the standard ASTM E1394 "\\^&" (sections 3.2.7 and 6.3):
        //     H|@^\\|<id>||GeneXpert PC^GeneXpert^6.1|||||LIS||P|1394-97|<date>
        return "H|@^\\|||INST^GeneXpert^4.7||||||P|1394-97|" + now;
    }
}
