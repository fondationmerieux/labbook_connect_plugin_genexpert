package plugin;

/**
 * This interface defines the methods to be used when writing an analyzer class.
 */
public interface Analyzer {
	
	/**
	 * Variable to specify this analyzer's identifier
	 * 
	 * The value must come from the settings file.
	 */
	public String id_analyzer = "";
	
	/**
	 * Variable to specify endpoint of upstream for lab27 transaction
	 * 
	 * The value must come from the settings file.
	 */
	public String url_upstream_lab27 = "";
	
	/**
	 * Variable to specify endpoint of upstream for lab29 transaction
	 * 
	 * The value must come from the settings file.
	 */
	public String url_upstream_lab29 = "";
	
	/**
	 * Return value of id_analyzer.
	 * 
	 * @return id_analyzer
	 */
	public String getId_analyzer();
	
	/**
	 * Defines the value of id_analyzer.
	 * 
	 * @param id_analyzer value for id_analyzer.
	 */
	public void setId_analyzer(String id_analyzer);
	
	/**
	 * Return value of url_upstream_lab27.
	 * 
	 * @return url_upstream_lab27
	 */
	public String getUrl_upstream_lab27();
	
	/**
	 * Defines the value of url_upstream_lab27.
	 * 
	 * @param url value for url_upstream_lab27.
	 */
	public void setUrl_upstream_lab27(String url);
	
	/**
	 * Return value of url_upstream_lab29.
	 * 
	 * @return url_upstream_lab29
	 */
	public String getUrl_upstream_lab29();
	
	/**
	 * Defines the value of url_upstream_lab29.
	 * 
	 * @param url value for url_upstream_lab29.
	 */
	public void setUrl_upstream_lab29(String url);
	
	/**
	 * This method creates an instance of this class
	 */
	public Analyzer copy();
	
	/**
	 * @return name of this plugin
	 */
	public String test();
	
	/**
	 * This method monitors the arrival of files in the lab27 directory of this analyzer. 
	 * Reads the file and builds a response hl7 QPB_Q11, then sends it to url_upstream_lab27.
	 * Receives the ack in hl7 format of type RSP_K11.
	 * 
	 * @return empty String
	 */
	public String lab27();

	/**
	 * This method receives a string of type hl7 OML_O33.
	 * Reads and constructs an hl7 message of type ORL_O34, then returns this message.
	 * 
	 * @param str_OML_O33 HL7 String of type OML_O33.
	 * @return String orl_o34
	 */
	public String lab28(final String str_OML_O33);

	/**
	 * This method monitors the arrival of files in the lab27 directory of this analyzer. 
	 * Reads the file and builds a response hl7 OUL_R22, then sends it to url_upstream_lab29.
	 * Receives the ack in hl7 format of type ACK_R22.
	 * 
	 * @return empty String
	 */
	public String lab29();
}