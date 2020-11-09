package org.area515.resinprinter.services;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.zip.ZipOutputStream;

import javax.annotation.security.RolesAllowed;
import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.area515.resinprinter.display.DisplayManager;
import org.area515.resinprinter.display.GraphicsOutputInterface;
import org.area515.resinprinter.job.PrintFileProcessor;
import org.area515.resinprinter.network.NetInterface;
import org.area515.resinprinter.network.NetworkManager;
import org.area515.resinprinter.network.WirelessNetwork;
import org.area515.resinprinter.notification.NotificationManager;
import org.area515.resinprinter.printer.MachineConfig;
import org.area515.resinprinter.printer.SlicingProfile;
import org.area515.resinprinter.serial.SerialCommunicationsPort;
import org.area515.resinprinter.serial.SerialManager;
import org.area515.resinprinter.server.CwhEmailSettings;
import org.area515.resinprinter.server.HostInformation;
import org.area515.resinprinter.server.HostProperties;
import org.area515.resinprinter.server.Main;
import org.area515.resinprinter.util.security.PhotonicUser;
import org.area515.util.IOUtilities;
import org.area515.util.MailUtilities;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import com.sun.mail.smtp.SMTPSendFailedException;

@Api(value="machine")
@RolesAllowed(PhotonicUser.FULL_RIGHTS)
@Path("machine")
public class MachineService {
    private static final Logger logger = LogManager.getLogger();
	public static MachineService INSTANCE = new MachineService();
	
	private Future<Boolean> restartProcess;
	
	private MachineService() {}
	
	private String getRemoteIpAddress(HttpServletRequest request) {
		String forwardHeader = HostProperties.Instance().getForwardHeader();
		if (forwardHeader != null) {
			String ipAddress = request.getHeader(forwardHeader);
			if (ipAddress == null || ipAddress.trim().equals("") || ipAddress.equalsIgnoreCase("unknown")) {
				return null;
			}
			return ipAddress;
		}
		
		return request.getRemoteAddr();
	}
	
    @ApiOperation(value="Cancels the network restart operation that could be in progress. "
    		+ "This assumes that there is already a startNetworkRestartProcess that is currently in progress")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@POST
	@Path("cancelNetworkRestartProcess")
	@Produces(MediaType.APPLICATION_JSON)
	public void cancelRestartOperation() {
		if (restartProcess != null) {
			if (!restartProcess.cancel(true)) {
				throw new IllegalArgumentException("Couldn't cancel the network reconfiguration process.");
			}
			
			try {
				restartProcess.get();
			} catch (CancellationException e) {
				restartProcess = null;
				return; //This is the normal expected outcome
			} catch (InterruptedException | ExecutionException e) {
				logger.error("Problem occurred while waiting for the network reconfiguraiton process to terminate.", e);
				throw new IllegalArgumentException("Problem occurred while waiting for the network reconfiguraiton process to terminate.");
			}
		}
	}
	
	/**
	 * 
	 * @param request
	 * @param timeoutMilliseconds
	 * @param millisecondsBetweenPings
	 * @param maxUnmatchedPings
	 * @return true if the proper network interface is found and the caller should start expecting shutdown pings
	 */
    @ApiOperation(value="This process ensures that the user unplugs the network cable in order to complete the network reconfiguration process." +
	 "Since it's not immediately obvious how to notify a client after the only network link to that client has been torn down, I" + 
     "described that process below:" + 
	 "" + 
	 "1. The Restful client should first reconfigure the WIFI AP of their choice with the connectToWifiSSID() method." + 
	 "2. The Restful client opens a websocket connection to /hostNotification url." + 
	 "3. Once that step is successful, this method should be called immediately afterwards." + 
	 "4. If this method returns false, the NetworkInterface was not found and the configuration process ENDS HERE." + 
	 "5. If this method is able to return true, it means this method found the proper NetworkInterface managing this HTTP socket" + 
	 "	connection." + 
	 "6. The NetworkInterface will be monitored for a disruption in network connectivity and Websocket ping events will start being " + 
	 "	produced from this host at the interval of \"millisecondsBetweenPings\"." + 
	 "7. Once the Restful client receives it's first ping event, it should ask the user to unplug the ethernet cable." + 
	 "8. The user should then either cancel the operation, or unplug the ethernet cable." + 
	 "9. If the user cancels the operation, the Restful client needs to call cancel" + 
	 "RestartOperation() to notify the server that it should should" + 
	 "	not continue to wait for the user to unplug the cable." + 
	 "10. If the user unplugs the ethernet cable, the proper NetworkInterface(discovered in step 4) is found to be down and this " + 
	 "	Host(and it's network) are restarted." + 
	 "11. Since this Host is now in the middle of restarting, it isn't able to send WebSocket ping events any longer." + 
	 "12. The Restful client then discovers that ping events are no longer coming and it's timeout period hasn't been exhausted, " + 
	 "	so it let's the user know that they should shut down the Restful client(probably a browser) and restart the printer with the Multicast client." + 
	 "13. The Multicast client eventually finds the Raspberry Pi on the new Wifi IP address and we are back in business...")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@POST
	@Path("startNetworkRestartProcess/{timeoutMilliseconds}/{millisecondsBetweenPings}/{maxUnmatchedPings}")
	@Produces(MediaType.APPLICATION_JSON)
	public boolean restartHostAfterNetworkCableUnplugged(
			@Context HttpServletRequest request, 
			@PathParam("timeoutMilliseconds") final long timeoutMilliseconds,
			@PathParam("millisecondsBetweenPings") final long millisecondsBetweenPings,
			@PathParam("maxUnmatchedPings") final int maxUnmatchedPings) {
		
		String ipAddress = request.getLocalAddr();
		try {
			if (restartProcess != null) {
				cancelRestartOperation();
			}
			
			final NetworkInterface iFace = NetworkInterface.getByInetAddress(InetAddress.getByName(ipAddress));
			final long startTime = System.currentTimeMillis();
			restartProcess = Main.GLOBAL_EXECUTOR.submit(new Callable<Boolean>() {
				@Override
				public Boolean call() throws Exception {
					boolean iFaceUp = true;
					while (iFaceUp = iFace.isUp() && timeoutMilliseconds > 0 && System.currentTimeMillis() - startTime < timeoutMilliseconds) {
						NotificationManager.sendPingMessage("Please unplug your network cable to finish this setup process.");
						logger.debug("  Interface:{} isUp:{}", iFace, iFace.isUp());
						
						try {
							Thread.sleep(millisecondsBetweenPings);
						} catch (InterruptedException e) {
							return false;
						}
					}
		
					if (!iFaceUp) {
						//After executing this method, don't expect this JVM to stick around much longer
						IOUtilities.executeNativeCommand(HostProperties.Instance().getRebootCommand(), null, (String)null);
					}
					
					return true;
				}
			});
			
			return true;
		} catch (SocketException | UnknownHostException e) {
			logger.error("Error restarting host after network cable unplugged", e);
			return false;
		}
	}
    
// Early modifications to support fetching of WiFi signal strength. Feel free to discard or replace as necessary.
@ApiOperation(value = "Enumerates the signal strength in dBm for the currently connected wireless host.")
@ApiResponses(value = {
		@ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
		@ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
 @GET
 @Path("wirelessNetworks/getWirelessStrength")
 @Produces(MediaType.APPLICATION_JSON)
 public String getWirelessStrength() {
	Class<NetworkManager> managerClass = HostProperties.Instance().getNetworkManagerClass();
	try {
		NetworkManager networkManager = managerClass.newInstance();
		List<NetInterface> interfaces = networkManager.getNetworkInterfaces();
		String currentSSID = networkManager.getCurrentSSID();
		String signal = "-100";

		for (NetInterface network : interfaces) {
			for (WirelessNetwork wnetwork : network.getWirelessNetworks()) {
				if (wnetwork.getSsid().compareToIgnoreCase(currentSSID)==0){
					signal = wnetwork.getSignalStrength();
				}
			}
		}
		
		return signal;
	} catch (InstantiationException | IllegalAccessException e) {
		logger.error("Error retrieving wireless networks", e);
		return null;
	}
 }
    @ApiOperation(value="Retrieves all of the supported file types that are returned from the each of the org.area515.resinprinter.job.PrintFileProcessor.getFileExtensions()."
    		+ SwaggerMetadata.PRINT_FILE_PROCESSOR)
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("supportedFileTypes")
	@Produces(MediaType.APPLICATION_JSON)
	public Set<String> getSupportedFileTypes() {
		Set<String> fileTypes = new HashSet<String>();
		for (PrintFileProcessor<?, ?> processor : HostProperties.Instance().getPrintFileProcessors()) {
			fileTypes.addAll(Arrays.asList(processor.getFileExtensions()));
		}
		return fileTypes;
	}
	
    @ApiOperation(value="Upload TrueType fonts to be used with 2D file processing settings.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@POST
	@Path("/uploadFont")
	@Consumes("application/octet-stream")
	public Response uploadFont(InputStream istream) {
		try {
			Font font = Font.createFont(Font.TRUETYPE_FONT, istream);
			if (!GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font)) {
				String output = "Failed to register font due to a possible naming conflict";
			    logger.info(output);
				return Response.status(Status.BAD_REQUEST).entity(output).build();
			}
			
			NotificationManager.fileUploadComplete(new File(font.getName()));
		    logger.info("Font:{} registered:{} glyphs", font.getName(), font.getNumGlyphs());
		    return Response.status(Status.BAD_REQUEST).entity(font.getName()).build();
		} catch (IOException e) {
			String output = "Error while uploading font";
			logger.error(output, e);
			return Response.status(Status.BAD_REQUEST).entity(output).build();
		} catch (FontFormatException e) {
			String output = "This font didn't seem to be a true type font.";
			logger.error(output, e);
			return Response.status(Status.BAD_REQUEST).entity(output).build();
		}
	}
	
    @ApiOperation(value="Enumerates the list of available fonts to be used with 2D file printing.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("supportedFontNames")
	@Produces(MediaType.APPLICATION_JSON)
	public List<String> getSupportedFontFamilies() {
		List<String> fontNames = new ArrayList<String>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		Collections.sort(fontNames);
		return fontNames;
	}
	
	private File buildLogFileBundle() {
		File zippedFile = new File("LogBundle.zip");
		String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
		IOUtilities.executeNativeCommand(HostProperties.Instance().getDumpStackTraceCommand(), null, pid);
		
		ZipOutputStream zipOutputStream = null;
		try {
			zipOutputStream = new ZipOutputStream(new FileOutputStream(zippedFile));
			String logFiles[] = new String[]{"log.scrout", "log.screrr", "log.out", "log.err", "cwh.log", "log4j2.properties", "debuglog4j2.properties", "testlog4j2.properties"};
			for (String logFile : logFiles) {
				File file = new File(logFile);
				if (file.exists()) {
					IOUtilities.zipFile(file, zipOutputStream);
				}
			}
			
			HostProperties.Instance().exportDiagnostic(zipOutputStream);
			
			ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
			System.getProperties().store(byteStream, "Stored on " + new Date());
			IOUtilities.zipStream("System.properties", new ByteArrayInputStream(byteStream.toByteArray()), zipOutputStream);
			
			zipOutputStream.finish();
			return zippedFile;
		} catch (IOException e) {
			logger.error("Error executing diagnostic", e);
			throw new IllegalArgumentException("Failure creating log bundle.", e);
		} finally {
			IOUtils.closeQuietly(zipOutputStream);
		}
	}
	
    @ApiOperation(value = SwaggerMetadata.DIAGNOSTIC_DUMP_PREFIX + " then perform a file download of the zip file.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("downloadDiagnostic/{zipName}")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public StreamingOutput downloadSupportLogs() {
	    return new StreamingOutput() {
			@Override
			public void write(OutputStream output) throws IOException, WebApplicationException {
				File zippedFile = buildLogFileBundle();
				FileInputStream stream = null;
				try {
					stream = new FileInputStream(zippedFile);
					IOUtils.copy(stream, output);
				} finally {
					try {stream.close();} catch (IOException e) {}
					zippedFile.delete();
				}
			}  
	    };
	}
	
    @ApiOperation(value = SwaggerMetadata.DIAGNOSTIC_DUMP_PREFIX + " then perform an email of the zip file.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("executeDiagnostic")
	@Produces(MediaType.APPLICATION_JSON)
	public void emailSupportLogs() {
		File zippedFile = buildLogFileBundle();
		Transport transport = null;
		try {
			CwhEmailSettings settings = HostProperties.Instance().loadEmailSettings();
			HostInformation info = HostProperties.Instance().loadHostInformation();
			transport = MailUtilities.openTransportFromSettings(settings);
			MailUtilities.executeSMTPSend(
					info.getDeviceName().replace(" ", "") + "@My3DPrinter",
					settings.getServiceEmailAddresses(),
					"Service Request",
					"Attached diagnostic information",
					transport,
					zippedFile);
		} catch (SMTPSendFailedException e) {
			logger.error("Error sending email", e);
			if (e.getMessage().contains("STARTTLS")) {
				throw new IllegalArgumentException("Failure emailing log bundle: It looks like this server requires TLS to be enabled. " + e.getMessage());
			}
			throw new IllegalArgumentException("Failure emailing log bundle: " + e.getMessage());
		} catch (AuthenticationFailedException e) {
			logger.error("Authentication error sending email", e);
			throw new IllegalArgumentException("Failure emailing log bundle: Username or password incorrect");
		} catch (MessagingException | IOException e) {
			logger.error("Error sending email", e);
			if (e.getMessage() == null) {
				throw new IllegalArgumentException("Failure emailing log bundle:" + e.getClass());
			} else {
				throw new IllegalArgumentException("Failure emailing log bundle:" + e.getMessage());
			}
		} finally {
			zippedFile.delete();
			if (transport != null) {
				try {transport.close();} catch (MessagingException e) {}
			}
		}
	}
	
    @ApiOperation(value = "Upload a zip file that contains a Photonic 3d upgrade. The next time Phontonic 3d is restarted, this upgrade will be performed..")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@POST
	@Path("/stageOfflineInstall")
	@Consumes("multipart/form-data")
	public Response stageOfflineInstall(MultipartFormDataInput input) {
		return PrintableService.uploadFile(input, HostProperties.Instance().getUpgradeDir());
	}

    @ApiOperation(value = "Enumerates the list of WirelessNetworks that are currently in range for each network interface available on the host.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
			@GET
    @Path("wirelessNetworks/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<WirelessNetwork> getWirelessNetworks() {
    	Class<NetworkManager> managerClass = HostProperties.Instance().getNetworkManagerClass();
		try {
			NetworkManager networkManager = managerClass.newInstance();
			List<NetInterface> interfaces = networkManager.getNetworkInterfaces();
			List<WirelessNetwork> wInterfaces = new ArrayList<WirelessNetwork>();
			
			for (NetInterface network : interfaces) {
				for (WirelessNetwork wnetwork : network.getWirelessNetworks()) {
					wInterfaces.add(wnetwork);
				}
			}
			
			return wInterfaces;
		} catch (InstantiationException | IllegalAccessException e) {
			logger.error("Error retrieving wireless networks", e);
			return null;
		}
	}
	
    @ApiOperation(value = "Connects to the supplied wireless SSID using the provided passphrase and Wireless settings.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
    @PUT
    @Path("wirelessConnect")
    @Consumes(MediaType.APPLICATION_JSON)
    public void connectToWifiSSID(WirelessNetwork network) {
		Class<NetworkManager> managerClass = HostProperties.Instance().getNetworkManagerClass();
		try {
			NetworkManager networkManager = managerClass.newInstance();
			networkManager.connectToWirelessNetwork(network);
		} catch (InstantiationException | IllegalAccessException e) {
			logger.error("Error connecting to WifiSSID:" + network.getSsid(), e);
		}
	 }
    
 // Early modifications to support fetching of WiFi signal strength. Feel free to discard or replace as necessary.
    @ApiOperation(value = "Enumerates Printer interfaces' IPs, MACs, HostName and SSID information.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
			@GET
			@Path("getNetworkHostConfiguration")
			@Produces(MediaType.APPLICATION_JSON)
			public Map<String, ?> getNetworkHostConfiguration() {
			   Class<NetworkManager> managerClass = HostProperties.Instance().getNetworkManagerClass();
			   try {
				   NetworkManager networkManager = managerClass.newInstance();
				   Map<String, Object> networkHost = new HashMap<>();
				   networkHost.put("MACs", networkManager.getMACs());
				   networkHost.put("IPs", networkManager.getIPs());
				   networkHost.put("Hostname",networkManager.getHostname());
				   networkHost.put("SSID",networkManager.getCurrentSSID());
				   return networkHost;
			   } catch (InstantiationException | IllegalAccessException e) {
				   logger.error("Error retrieving network host configuration", e);
				   return null;
			   }
			}
    
    @ApiOperation(value="Changes the hostname of the computer running Photonic.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, response=MachineResponse.class, message = SwaggerMetadata.MACHINE_RESPONSE),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("setNetworkHostname/{hostname}")
	@Produces(MediaType.APPLICATION_JSON)
	public MachineResponse startProjector(@PathParam("hostname") String host) {
    	if (Pattern.matches("^[a-zA-Z0-9\\-]+$", host)){
    		try {
    	    	Class<NetworkManager> managerClass = HostProperties.Instance().getNetworkManagerClass();
    			NetworkManager networkManager = managerClass.newInstance();
    			networkManager.setHostname(host);
    			logger.debug("Set new hostname to: " + host);
    			return new MachineResponse("setNetworkHostname", true, "Changed hostname to:" + host);
    		} catch (InstantiationException | IllegalAccessException e) {
    			logger.error("Error setting new hostname", e);
    			return new MachineResponse("setNetworkHostname", false, e.getMessage());
    		}
    	}
    	else{
    		logger.error("Error setting new hostname - RegEx failed");
    		throw new IllegalArgumentException("Hostname \""+host+"\" contained invalid characters. Please retry with uppercase, lowercase and numeric characters and hyphens [-] only.");
    	}
    }
	
    @ApiOperation(value = "Enumerates the list of serial ports available on the Photonic 3D host.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
    @GET
    @Path("serialPorts/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getSerialPorts() {
		 List<SerialCommunicationsPort> identifiers = SerialManager.Instance().getSerialDevices();
		 List<String> identifierStrings = new ArrayList<String>();
		 for (SerialCommunicationsPort current : identifiers) {
			 identifierStrings.add(current.getName());
		 }
		 
		 return identifierStrings;
    }
	 
    @ApiOperation(value = "Enumerates the list of graphics displays that are available on the Photonic 3D host.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
    @GET
    @Path("graphicsDisplays/list")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getDisplays() {
		 List<GraphicsOutputInterface> devices = DisplayManager.Instance().getDisplayDevices();
		 List<String> deviceStrings = new ArrayList<String>();
		 for (GraphicsOutputInterface current : devices) {
			 deviceStrings.add(current.getIDstring());
		 }
		 
		 return deviceStrings;
	}
	 
    
    
	@ApiOperation(value = "Enumerates the list of machine configurations that are available on the Photonic 3D host.")
	@ApiResponses(value = {
	        @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
	        @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("machineConfigurations/list")
	public List<MachineConfig> getMachineConfigurations() {
		return HostProperties.Instance().getConfigurations(HostProperties.Instance().MACHINE_DIR, HostProperties.MACHINE_EXTENSION, MachineConfig.class);
	}
	
	@ApiOperation(value = "Save a machine configuration to the machine config directory of Photonic 3D host.")
	@ApiResponses(value = {
	        @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
	        @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("machineConfigurations")
	public void saveMachineConfiguration(MachineConfig machineConfig) throws JAXBException {
		File machineFile = new File(HostProperties.Instance().MACHINE_DIR, machineConfig.getName() + HostProperties.MACHINE_EXTENSION);
		JAXBContext jaxbContext = JAXBContext.newInstance(MachineConfig.class);
		Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
		jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		jaxbMarshaller.marshal(machineConfig, machineFile);
	}
	
	@ApiOperation(value = "Deletes a machine configuration(by name) from the machine config directory of Photonic 3D host.")
	@ApiResponses(value = {
	        @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
	        @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@DELETE
	@Path("machineConfigurations/{machineConfigurationName}")
	public void deleteMachineConfiguration(@PathParam("machineConfigurationName") String machineConfig) throws JAXBException {
		File machineFile = new File(HostProperties.Instance().MACHINE_DIR, machineConfig + HostProperties.MACHINE_EXTENSION);
		machineFile.delete();
	}
	
	
	
    @ApiOperation(value = "Enumerates the list of slicing profiles that are available on the Photonic 3D host.")
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
            @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@GET
	@Path("slicingProfiles/list")
	public List<SlicingProfile> getSlicingProfiles() {
	    	return HostProperties.Instance().getConfigurations(HostProperties.Instance().PROFILES_DIR, HostProperties.PROFILES_EXTENSION, SlicingProfile.class);
	}
    
	@ApiOperation(value = "Save a slicing profile to the slicing profile directory of Photonic 3D host.")
	@ApiResponses(value = {
	        @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
	        @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@PUT
	@Consumes(MediaType.APPLICATION_JSON)
	@Path("slicingProfiles")
	public void saveSlicingProfile(SlicingProfile slicingProfile) throws JAXBException {
		File profileFile = new File(HostProperties.Instance().PROFILES_DIR, slicingProfile.getName() + HostProperties.PROFILES_EXTENSION);
		JAXBContext jaxbContext = JAXBContext.newInstance(SlicingProfile.class);
		Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
		jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		jaxbMarshaller.marshal(slicingProfile, profileFile);
	}
	
	@ApiOperation(value = "Deletes a slicing profile from the slicing profile directory of Photonic 3D host.")
	@ApiResponses(value = {
	        @ApiResponse(code = 200, message = SwaggerMetadata.SUCCESS),
	        @ApiResponse(code = 500, message = SwaggerMetadata.UNEXPECTED_ERROR)})
	@DELETE
	@Path("slicingProfiles/{slicingProfileName}")
	public void deleteSlicingProfile(@PathParam("slicingProfileName") String profile) throws JAXBException {
		File profileFile = new File(HostProperties.Instance().PROFILES_DIR, profile + HostProperties.PROFILES_EXTENSION);
		profileFile.delete();
	}
}
