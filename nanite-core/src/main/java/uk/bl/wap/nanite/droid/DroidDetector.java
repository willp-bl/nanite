/**
 * 
 */
package uk.bl.wap.nanite.droid;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.bind.JAXBException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.CloseShieldInputStream;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;

import uk.gov.nationalarchives.droid.command.action.CommandExecutionException;
import uk.gov.nationalarchives.droid.container.ContainerSignatureDefinitions;
import uk.gov.nationalarchives.droid.container.ContainerSignatureSaxParser;
import uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier;
import uk.gov.nationalarchives.droid.core.CustomResultPrinter;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResult;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResultCollection;
import uk.gov.nationalarchives.droid.core.interfaces.RequestIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.resource.FileSystemIdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.resource.RequestMetaData;

/**
 * 
 * Attempts to perform full Droid identification, container and binary signatures.
 * 
 * Finding the actual droid-core invocation was tricky
 * From droid command line
 * - ReportCommand which launches a profileWalker,
 * - which fires a FileEventHandler when it hits a file,
 * - which submits an Identification request to the AsyncDroid subtype SubmissionGateway, 
 * - which calls DroidCore,
 * - which calls uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier
 * - Following which, SubmissionGateway does some handleContainer stuff, 
 * executes the container matching engine and does some complex logic to resolve the result.
 * 
 * This is all further complicated by the way a mix of Spring and Java is used to initialize
 * things, which makes partial or fast initialization rather difficult.
 * 
 * For droid-command-line, the stringing together starts with:
 * /droid-command-line/src/main/resources/META-INF/ui-spring.xml
 * this sets up the ProfileContextLocator and the SpringProfileInstanceFactory.
 * Other parts of the code set up Profiles and eventually call:
 * uk.gov.nationalarchives.droid.profile.ProfileContextLocator.openProfileInstanceManager(ProfileInstance)
 * which calls
 * uk.gov.nationalarchives.droid.profile.SpringProfileInstanceFactory.getProfileInstanceManager(ProfileInstance, Properties)
 * which then injects more xml, including:
 * @see /droid-results/src/main/resources/META-INF/spring-results.xml
 * which sets up most of the SubmissionGateway and identification stuff
 * (including the BinarySignatureIdentifier and the Container identifiers).
 * 
 * The ui-spring.xml file also includes
 * /droid-results/src/main/resources/META-INF/spring-signature.xml
 * which sets up the pronomClient for downloading binary and container signatures.
 * 
 * So, the profile stuff is hooked into the DB stuff which is hooked into the identifiers.
 * Everything is tightly coupled, so teasing it apart is hard work.
 * 
 * @see uk.gov.nationalarchives.droid.submitter.SubmissionGateway (in droid-results)
 * @see uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier
 * @see uk.gov.nationalarchives.droid.submitter.FileEventHandler.onEvent(File, ResourceId, ResourceId)
 * 
 * Also found 
 * @see uk.gov.nationalarchives.droid.command.action.DownloadSignatureUpdateCommand
 * which indicates how to download the latest sig file, 
 * but perhaps the SignatureManagerImpl does all that is needed?
 * 
 * @author Andrew Jackson <Andrew.Jackson@bl.uk>
 * @author Fabian Steeg
 * @author <a href="mailto:carl.wilson@bl.uk">Carl Wilson</a> <a
 *         href="http://sourceforge.net/users/carlwilson-bl"
 *         >carlwilson-bl@SourceForge</a> <a
 *         href="https://github.com/carlwilson-bl">carlwilson-bl@github</a>
 *
 */
public class DroidDetector implements Detector {

	/** */
	private static final long serialVersionUID = -170173360485335112L;

	private static Logger log = Logger.getLogger(DroidDetector.class.getName());

    static final String DROID_SIGNATURE_FILE = "DROID_SignatureFile_V69.xml";
    static final String DROID_SIG_RESOURCE = "droid/"+DROID_SIGNATURE_FILE;
    
	static final String DROID_SIG_FILE = "src/main/resources/droid/DROID_SignatureFile_V69.xml";
	static final String containerSignaturesFileName = "src/main/resources/droid/container-signature-20120828.xml";

	// Set up DROID binary handler:
	private BinarySignatureIdentifier binarySignatureIdentifier;
	private ContainerSignatureDefinitions containerSignatureDefinitions;
	
    private static final String FORWARD_SLASH = "/";
    private static final String BACKWARD_SLASH = "\\";
    private int maxBytesToScan = -1;
    boolean archives = false;

	private uk.gov.nationalarchives.droid.core.CustomResultPrinter resultPrinter;
	
	// Options:
	
	/** Set binarySignaturesOnly to disable container-based identification */
	private boolean binarySignaturesOnly = false;
	/** Disable this flag to prevent the file extension being used as a format hint */
	private boolean passFilenameWithInputStream = true;


	/** 
	 * Set up DROID resources
	 */
	public DroidDetector() throws CommandExecutionException {
		
        binarySignatureIdentifier = new BinarySignatureIdentifier();
        File fileSignaturesFile = new File(DROID_SIG_FILE);
        if (!fileSignaturesFile.exists()) {
            throw new CommandExecutionException("Signature file not found");
        }

        binarySignatureIdentifier.setSignatureFile(DROID_SIG_FILE);
        try {
            binarySignatureIdentifier.init();
        } catch (SignatureParseException e) {
            throw new CommandExecutionException("Can't parse signature file");
        }
        binarySignatureIdentifier.setMaxBytesToScan(maxBytesToScan);
        String path = fileSignaturesFile.getAbsolutePath();
        String slash = path.contains(FORWARD_SLASH) ? FORWARD_SLASH : BACKWARD_SLASH;
        String slash1 = slash;

        containerSignatureDefinitions = null;
        if (containerSignaturesFileName != null) {
            File containerSignaturesFile = new File(containerSignaturesFileName);
            if (!containerSignaturesFile.exists()) {
                throw new CommandExecutionException("Container signature file not found");
            }
            try {
                final InputStream in = new FileInputStream(containerSignaturesFileName);
                final ContainerSignatureSaxParser parser = new ContainerSignatureSaxParser();
                containerSignatureDefinitions = parser.parse(in);
            } catch (SignatureParseException e) {
                throw new CommandExecutionException("Can't parse container signature file");
            } catch (IOException ioe) {
                throw new CommandExecutionException(ioe);
            } catch (JAXBException jaxbe) {
                throw new CommandExecutionException(jaxbe);
            }
        }
        
		resultPrinter =
                new CustomResultPrinter(binarySignatureIdentifier, containerSignatureDefinitions,
                    "", slash, slash1, archives);
	}
	

	/**
	 * Currently, this is the most complete DROID identifier code, that only uses DROID code.
	 * 
	 * This uses a Custom Result Printer to get container results:
	 * 
	 * @param file
	 * @return
	 */
	protected MediaType detect(File file) {
		try {
			String fileName;
			try {
				fileName = file.getCanonicalPath();
			} catch (IOException e) {
				throw new CommandExecutionException(e);
			}
			URI uri = file.toURI();
			RequestMetaData metaData =
					new RequestMetaData(file.length(), file.lastModified(), fileName);
			RequestIdentifier identifier = new RequestIdentifier(uri);
			identifier.setParentId(1L);

			InputStream in = null;
			IdentificationRequest request = new FileSystemIdentificationRequest(metaData, identifier);
			try {
				return determineMediaType(request, new FileInputStream(file));
			} catch (IOException e) {
				throw new CommandExecutionException(e);
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (IOException e) {
						throw new CommandExecutionException(e);
					}
				}
			}
		} catch (CommandExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	
	/**
	 * 
	 */
	@Override
	public MediaType detect(InputStream input, Metadata metadata)
			throws IOException {
		try {
			// Optionally, get filename and identifiers from metadata: 
			String fileName = "";
			if( passFilenameWithInputStream ) {
				if( metadata.get(Metadata.RESOURCE_NAME_KEY) != null ) {
					fileName = metadata.get(Metadata.RESOURCE_NAME_KEY);
				}
 			}
			RequestMetaData metaData =
					new RequestMetaData((long) input.available(), null, fileName);
			RequestIdentifier identifier = new RequestIdentifier(URI.create("file:///./"+fileName));
			identifier.setParentId(1L);

			IdentificationRequest request = new InputStreamIdentificationRequest(metaData, identifier, input);
			try {
				return determineMediaType(request, input);
			} catch (IOException e) {
				throw new CommandExecutionException(e);
			}
		} catch (CommandExecutionException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * 
	 * @param request
	 * @param input
	 * @return
	 * @throws IOException
	 * @throws CommandExecutionException
	 */
	private MediaType determineMediaType( IdentificationRequest request, InputStream input ) throws IOException, CommandExecutionException {
		request.open(input);
		IdentificationResultCollection results =
				binarySignatureIdentifier.matchBinarySignatures(request);
		
		// Optionally, return top results from binary signature match only:
		if( this.binarySignaturesOnly ) {
			if( results.getResults().size() > 0 ) {
				return getMimeTypeFromResults( results.getResults() );
			} else {
				return MediaType.OCTET_STREAM;
			}
		}

		// Also get container results:
		resultPrinter.print(results, request);
		
		// Return as a MediaType:
		return getMimeTypeFromResult( resultPrinter.getResult() );		
	}
	
	/**
	 * 
	 * @param result
	 * @return
	 */
	protected static MediaType getMimeTypeFromResult(IdentificationResult result) {
		List<IdentificationResult> list = new ArrayList<IdentificationResult>();
		list.add(result);
		return getMimeTypeFromResults(list);
	}

	/**
	 * TODO Choose 'vnd' Vendor-style MIME types over other options when there are many in each Result.
	 * TODO This does not cope ideally with multiple/degenerate Results. 
	 * e.g. old TIFF or current RTF that cannot tell the difference so reports no versions.
	 * If there are sigs that differ more than this, this will ignore the versions.
	 * 
	 * @param list
	 * @return
	 * @throws MimeTypeParseException 
	 */
	protected static MediaType getMimeTypeFromResults( List<IdentificationResult> results ) {
		if( results == null || results.size() == 0 ) return MediaType.OCTET_STREAM;
		// Get the first result:
		IdentificationResult r = results.get(0);
		// Sort out the MIME type mapping:
		String mimeType = null;
		String mimeTypeString = r.getMimeType();
		if( mimeTypeString != null ) {
			// This sometimes has ", " separated multiple types
			String[] mimeTypeList = mimeTypeString.split(", ");
			// Taking first (highest priority) MIME type:
			mimeType = mimeTypeList[0];
		}
		// Build a MediaType
		MediaType mediaType = MediaType.parse(mimeType);
		Map<String,String> parameters = null;
		// Is there a MIME Type?
		if( mimeType != null && ! "".equals(mimeType) ) {
			parameters = new HashMap<String,String>(mediaType.getParameters());
			// Patch on a version parameter if there isn't one there already:
			if( parameters.get("version") == null && 
					r.getVersion() != null && (! "".equals(r.getVersion())) &&
					// But ONLY if there is ONLY one result.
					results.size() == 1 ) {
				parameters.put("version", r.getVersion());
			}
		} else {
			parameters = new HashMap<String,String>();
			// If there isn't a MIME type, make one up:
			String id = "puid-"+r.getPuid().replace("/", "-");
			String name = r.getName().replace("\"","'");
			// Lead with the PUID:
			mediaType = MediaType.parse("application/x-"+id);
			parameters.put("name", name);
			// Add the version, if set:
			String version = r.getVersion();
			if( version != null && !"".equals(version) && !"null".equals(version) ) {
				parameters.put("version", version);
			}
		}
		
		return new MediaType(mediaType,parameters);
	}
	
	/**
	 * @return
	 */
	public String getBinarySignatureFileVersion() {
		return resultPrinter.getBinarySignatureFileVersion();
	}
	
	/* ----- ----- ----- ----- */
	
	/**
	 * 
	 * @param args
	 * @throws CommandExecutionException
	 * @throws IOException
	 */
	public static void main(String[] args) throws CommandExecutionException, IOException {
		DroidDetector dr = new DroidDetector();
		for( String fname : args ) {
			File file = new File(fname);
			System.out.println("---- Identification Starts ---");
			System.out.println("File: "+fname);
			System.out.println("Droid using DROID binary signature file version: "+dr.getBinarySignatureFileVersion());
			System.out.println("---- VIA File ----");
			System.out.println("Result: " + dr.detect(file));
			System.out.println("---- VIA InputStream ----");
			Metadata metadata = new Metadata();
			metadata.set(Metadata.RESOURCE_NAME_KEY, file.toURI().toString());
			System.out.println("Result: " + dr.detect(new FileInputStream(file), metadata));
			System.out.println("---- VIA byte array ----");
			byte[] bytes = FileUtils.readFileToByteArray(file);
			System.out.println("Result: " + dr.detect(new ByteArrayInputStream(bytes), metadata));
			System.out.println("----\n");
		}
	}

}