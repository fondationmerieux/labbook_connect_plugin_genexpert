package plugin;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.moandjiezana.toml.Toml;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.model.v251.message.OML_O33;
import ca.uhn.hl7v2.model.v251.message.ORL_O34;
import ca.uhn.hl7v2.model.v251.message.OUL_R22;
import ca.uhn.hl7v2.model.v251.message.QBP_Q11;
import ca.uhn.hl7v2.model.v251.segment.MSH;


/**
 * This class defines how the targeted analyzer handles IHE-LAW, lab27, lab28 and lab29 transactions.
 */
public class AnalyzerDemo implements Analyzer {

	protected String id_analyzer = "";
	protected String url_lis_lab27 = "";
	protected String url_lis_lab29 = "";

	/**
	 * Returns value of id_analyzer.
	 * 
	 * @return id_analyzer
	 */
	@Override
	public String getId_analyzer() {
		return id_analyzer;
	}

	/**
	 * Defines the value of id_analyzer.
	 * 
	 * @param id_analyzer value for id_analyzer.
	 */
	@Override
	public void setId_analyzer(String id_analyzer) {
		this.id_analyzer = id_analyzer;
	}
	
	/**
	 * Returns value of url_upstream_lab27.
	 * 
	 * @return url_upstream_lab27
	 */
	@Override
	public String getUrl_upstream_lab27() {
		return this.url_lis_lab27;
	}

	/**
	 * Defines the value of url_upstream_lab27.
	 * 
	 * @param url value for url_upstream_lab27.
	 */
	@Override
	public void setUrl_upstream_lab27(String url) {
		this.url_lis_lab27 = url;
	}

	/**
	 * Returns value of url_upstream_lab29.
	 * 
	 * @return url_upstream_lab29
	 */
	@Override
	public String getUrl_upstream_lab29() {
		return this.url_lis_lab29;
	}

	/**
	 * Defines the value of url_upstream_lab29.
	 * 
	 * @param url value for url_upstream_lab29.
	 */
	@Override
	public void setUrl_upstream_lab29(String url) {
		this.url_lis_lab29 = url;
	}

	/**
	 * This method creates an instance of this class
	 */
	@Override
	public AnalyzerDemo copy() {
		AnalyzerDemo newAnalyzerDemo = new AnalyzerDemo();
		
		return newAnalyzerDemo;
	}
	
	/**
	 * Returns name of this plugin.
	 * 
	 * @return name of this plugin
	 */
	@Override
	public String test() {
		return this.getClass().getSimpleName();
	}

	/**
	 * This method monitors the arrival of files in the lab27 directory of this analyzer. 
	 * Reads the file and builds a response hl7 QPB_Q11, then sends it to url_upstream_lab27.
	 * Receives the ack in hl7 format of type RSP_K11.
	 * 
	 * @return empty String
	 */
	@Override
	public String lab27() {
		// For this plugin demo we have chosen to trigger with a file in TOML format 

		Path dir_lab27     = Paths.get("/storage/resource/connect/analyzer/" + this.getId_analyzer() + "/lab27");
		Path dir_archive27 = Paths.get("/storage/resource/connect/analyzer/" + this.getId_analyzer() + "/archive_lab27");

		// Monitors a repository
		try {
			WatchService watchService = FileSystems.getDefault().newWatchService();

			// Register directory with WatchService for creation events
			dir_lab27.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

			System.out.println("DEBUG LAB27 Watch directory : " + dir_lab27);
			
			Path file_detected = null;

			while (true) {
				// Wait for events
				WatchKey key = watchService.take();

				for (WatchEvent<?> event : key.pollEvents()) {
					WatchEvent.Kind<?> kind = event.kind();

					if (kind == StandardWatchEventKinds.OVERFLOW) {
						System.out.println("ERROR LAB27 overflow");
						continue;
					}

					// The event is a file creation
					if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
						file_detected = (Path) event.context();
						System.out.println("DEBUG LAB27 new file detected (absolutePath) : " + file_detected.toAbsolutePath());

						// Read file
						Toml file_lab27 = new Toml().read(dir_lab27.resolve(file_detected).toFile());

						// Variables
						String control_id  = file_lab27.getString("message.control_id") ;

						// Create an OUL_R22 message
						QBP_Q11 qbp_q11 = new QBP_Q11();

						Date currentTimestamp = new Date();
						SimpleDateFormat hl7DateFormat = new SimpleDateFormat("yyyyMMddHHmmss");

						// Format the timestamp according to HL7 TS format (yyyyMMddHHmmss)
						String hl7Timestamp = hl7DateFormat.format(currentTimestamp);

						// MSH segment
						MSH mshSegment = qbp_q11.getMSH();
						mshSegment.getMsh1_FieldSeparator().setValue("|");
						mshSegment.getMsh2_EncodingCharacters().setValue("^~\\&");
						mshSegment.getMsh3_SendingApplication().getNamespaceID().setValue("LabBook Connect");
						mshSegment.getMsh7_DateTimeOfMessage().getTime().setValue(hl7Timestamp);
						mshSegment.getMsh9_MessageType().getMsg1_MessageCode().setValue("QBP");
						mshSegment.getMsh9_MessageType().getMsg2_TriggerEvent().setValue("Q11");
						mshSegment.getMsh9_MessageType().getMsg3_MessageStructure().setValue("QBP_Q11"); // cf IHE_PaLM_TF_Vol2b.pdf
						mshSegment.getMsh10_MessageControlID().setValue(control_id);
						mshSegment.getMsh21_MessageProfileIdentifier(0).getEntityIdentifier().setValue("LAB-27");
						mshSegment.getMsh21_MessageProfileIdentifier(0).getNamespaceID().setValue("IHE");

						// SEND message QBP_Q11 to upstream
						try {
							String ret_rsp_k11 = Connect_util.send_hl7_msg(this, this.getUrl_upstream_lab27(), qbp_q11.toString());
							
							System.out.println("DEBUG LAB27 ret_rsp_k11=" + ret_rsp_k11);
							
							// TODO convert RSP_K11 to analyzer format.
							// TODO send message to analyzer
						} catch (Exception e) {
							System.out.println("ERROR LAB27 send msg e : " + e);
						}
						
						// we archived the file
				        try {
				        	String filename_archive = file_detected.getFileName() + "." + hl7Timestamp;
				        	
				            Files.move(dir_lab27.resolve(file_detected), dir_archive27.resolve(filename_archive));

				            System.out.println("DEBUG LAB27 file moved");
				        } catch (IOException e) {
				        	System.out.println("ERROR LAB27  file not moved e : " + e);
				        }
					}
				}

				// Reset the key for future events
				boolean valid = key.reset();
				if (!valid) {
					System.out.println("DEBUG LAB27 directory monitoring is over");
					break;
				}
			}
		} catch (Exception e) {
			System.out.println("ERROR LAB27 watch directory : " + e);
		}
		
	return "";
	}

	/**
	 * This method receives a string of type hl7 OML_O33.
	 * Reads and constructs an hl7 message of type ORL_O34, then returns this message.
	 * 
	 * @param str_OML_O33 HL7 String of type OML_O33.
	 * @return String orl_o34
	 */
	@Override
	public String lab28(final String str_OML_O33) {
		// Parse hl7 OML O33
		DefaultHapiContext context = new DefaultHapiContext();

		PipeParser parser = context.getPipeParser();

		OML_O33 oml_o33 = null;

		// Variables get from message received useful to the response
		String msh_control_id = "";

		try {
			System.out.println("DEBUG LAB28 hl7_received :\n" + str_OML_O33);
			oml_o33 = (OML_O33) parser.parse(str_OML_O33);

			msh_control_id = oml_o33.getMSH().getMessageControlID().getValue();
		} catch (Exception e) {
			System.out.println("ERROR LAB28 parse hl7 received : " + e);
		}

		// Create an ORL^O34 message
		ORL_O34 orl_o34 = new ORL_O34();

		try {
			System.out.println("DEBUG LAB28 Start to build ORL_O34");

			Date currentTimestamp = new Date();
			SimpleDateFormat hl7DateFormat = new SimpleDateFormat("yyyyMMddHHmmss.SSSZ");

			// Format the timestamp according to HL7 TS format (yyyyMMddHHmmss.SSSZ)
			String hl7Timestamp = hl7DateFormat.format(currentTimestamp);

			// MSH segment
			MSH mshSegment = orl_o34.getMSH();
			mshSegment.getMsh1_FieldSeparator().setValue("|");
			mshSegment.getMsh2_EncodingCharacters().setValue("^~\\&");
			mshSegment.getMsh3_SendingApplication().getNamespaceID().setValue("LabBook Connect");
			mshSegment.getMsh7_DateTimeOfMessage().getTime().setValue(hl7Timestamp);
			mshSegment.getMsh9_MessageType().getMsg1_MessageCode().setValue("ORL");
			mshSegment.getMsh9_MessageType().getMsg2_TriggerEvent().setValue("O34");
			mshSegment.getMsh9_MessageType().getMsg3_MessageStructure().setValue("ORL_O42"); // cf IHE_PaLM_TF_Vol2b.pdf
			mshSegment.getMsh10_MessageControlID().setValue(msh_control_id);
			mshSegment.getMsh21_MessageProfileIdentifier(0).getEntityIdentifier().setValue("LAB-28");
			mshSegment.getMsh21_MessageProfileIdentifier(0).getNamespaceID().setValue("IHE");

			/* TODO : Disable for now
			// MSA segment
			MSA msaSegment = orl_o34.getMSA();

			// Group RESPONSE
			ORL_O34_RESPONSE responseGroup = orl_o34.getRESPONSE();

			// Group PATIENT
			ORL_O34_PATIENT patientGroup = responseGroup.getPATIENT();

			// PID segment
			PID pidSegment = patientGroup.getPID();

			// Group SPECIMEN
			ORL_O34_SPECIMEN specimenGroup = patientGroup.getSPECIMEN();

			// SPM segment
			SPM spmSegment = specimenGroup.getSPM();

			// Group ORDER
			ORL_O34_ORDER orderGroup = specimenGroup.getORDER();

			// ORC segment
			ORC orcSegment = orderGroup.getORC();

			// ORC-1
			// ORC-5
			*/

			System.out.println("DEBUG LAB28 orl_o34 = " + orl_o34);

			context.close();
		}
		catch(Exception e) {
			System.out.println("ERROR LAB28 build ORL_O34 : " + e);
		}

		return orl_o34.toString();
	}

	/**
	 * This method monitors the arrival of files in the lab29 directory of this analyzer. 
	 * Reads the file and builds a response hl7 OUL_R22, then sends it to url_upstream_lab29.
	 * Receives the ack in hl7 format of type ACK_R22.
	 * 
	 * @return empty String
	 */
	@Override
	public String lab29() {
		// For this plugin demo we have chosen to trigger the arrival of results with a file in TOML format 

		Path dir_lab29     = Paths.get("/storage/resource/connect/analyzer/" + this.getId_analyzer() + "/lab29");
		Path dir_archive29 = Paths.get("/storage/resource/connect/analyzer/" + this.getId_analyzer() + "/archive_lab29");

		// Monitors a results repository
		try {
			WatchService watchService = FileSystems.getDefault().newWatchService();

			// Register directory with WatchService for creation events
			dir_lab29.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

			System.out.println("DEBUG LAB29 Watch directory : " + dir_lab29);
			
			Path file_detected = null;

			while (true) {
				// Wait for events
				WatchKey key = watchService.take();

				for (WatchEvent<?> event : key.pollEvents()) {
					WatchEvent.Kind<?> kind = event.kind();

					if (kind == StandardWatchEventKinds.OVERFLOW) {
						System.out.println("ERROR LAB29 overflow");
						continue;
					}

					// The event is a file creation
					if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
						file_detected = (Path) event.context();
						System.out.println("DEBUG LAB29 new file detected (absolutePath) : " + file_detected.toAbsolutePath());

						// Read result file
						Toml file_result = new Toml().read(dir_lab29.resolve(file_detected).toFile());

						// Variables of result
						String control_id  = file_result.getString("message.control_id") ;
						//String res_id_analyzer = file_result.getString("analyzer.id") ;

						// Create an OUL_R22 message
						OUL_R22 oul_r22 = new OUL_R22();

						Date currentTimestamp = new Date();
						SimpleDateFormat hl7DateFormat = new SimpleDateFormat("yyyyMMddHHmmss");

						// Format the timestamp according to HL7 TS format (yyyyMMddHHmmss)
						String hl7Timestamp = hl7DateFormat.format(currentTimestamp);

						// MSH segment
						MSH mshSegment = oul_r22.getMSH();
						mshSegment.getMsh1_FieldSeparator().setValue("|");
						mshSegment.getMsh2_EncodingCharacters().setValue("^~\\&");
						mshSegment.getMsh3_SendingApplication().getNamespaceID().setValue("LabBook Connect");
						mshSegment.getMsh7_DateTimeOfMessage().getTime().setValue(hl7Timestamp);
						mshSegment.getMsh9_MessageType().getMsg1_MessageCode().setValue("OUL");
						mshSegment.getMsh9_MessageType().getMsg2_TriggerEvent().setValue("R22");
						mshSegment.getMsh9_MessageType().getMsg3_MessageStructure().setValue("OUL_R22"); // cf IHE_PaLM_TF_Vol2b.pdf
						mshSegment.getMsh10_MessageControlID().setValue(control_id);
						mshSegment.getMsh21_MessageProfileIdentifier(0).getEntityIdentifier().setValue("LAB-29");
						mshSegment.getMsh21_MessageProfileIdentifier(0).getNamespaceID().setValue("IHE");

						// SEND message OUL_R22 to upstream
						try {
							String ret_ack_r22 = Connect_util.send_hl7_msg(this, this.getUrl_upstream_lab29(),oul_r22.toString());
							
							System.out.println("DEBUG LAB29 ret_oul_r22=" + ret_ack_r22);
							
							// TODO convert ACK_R22 to analyzer format.
							// TODO send message to analyzer
						} catch (Exception e) {
							System.out.println("ERROR LAB29 send msg e : " + e);
						}
						
						// processed results, we archived the file
				        try {
				        	String filename_archive = file_detected.getFileName() + "." + hl7Timestamp;
				        	
				            Files.move(dir_lab29.resolve(file_detected), dir_archive29.resolve(filename_archive));

				            System.out.println("DEBUG LAB29 file moved");
				        } catch (IOException e) {
				        	System.out.println("ERROR LAB29 file not moved e : " + e);
				        }
					}
				}

				// Reset the key for future events
				boolean valid = key.reset();
				if (!valid) {
					System.out.println("DEBUG LAB29 directory monitoring is over");
					break;
				}
			}
		} catch (Exception e) {
			System.out.println("ERROR LAB29 watch directory : " + e);
		}
		
	return "";
	}
}
