package se.vgr.usdservice;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.rpc.holders.StringHolder;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.apache.axis.client.Call;
import org.apache.axis.client.Service;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import se.vgr.usdservice.domain.Issue;

import com.ca.www.UnicenterServicePlus.ServiceDesk.USD_WebServiceSoap;
import com.ca.www.UnicenterServicePlus.ServiceDesk.USD_WebServiceSoapSoapBindingStub;

/**
 * @author Andrew Culbert
 * @author Ulf Carlsson
 * 
 */
public class USDServiceImpl implements USDService {

    private URL endPoint;
    private String wsUser;
    private String wsPassword;
    private String wsAttachmentRepHandle;
    private USD_WebServiceSoapSoapBindingStub webService;
    private static final Log log = LogFactory.getLog(USDServiceImpl.class);

    private Properties usdAppToGroupMappings;

    // Define which attributes to fetch when retrieving contact's issue list
    private static final String[] attributeNamesContactIssueList = new String[] { "description", "summary",
            "status", "ref_num", "web_url" };

    public USDServiceImpl(Properties p) {
        String sEndPoint = p.getProperty("endpoint");
        try {
            endPoint = new URL(sEndPoint);
        }
        catch (MalformedURLException e) {
            throw new RuntimeException("URL not found:" + sEndPoint, e);
        }
        this.wsUser = p.getProperty("user");
        this.wsPassword = p.getProperty("password");
        this.wsAttachmentRepHandle = p.getProperty("repositoryHandle");
    }

    public Properties getUsdAppToGroupMappings() {
        return usdAppToGroupMappings;
    }

    public void setUsdAppToGroupMappings(Properties appToGroupMappings) {
        this.usdAppToGroupMappings = appToGroupMappings;

        System.out.println("USDMappings:" + usdAppToGroupMappings);

    }

    private void createAttachment(USD_WebServiceSoap service, int sid, String repHandle, String objectHandle,
            String description, String fileName) throws Exception {

        FileDataSource fds = new FileDataSource(fileName);
        DataHandler dhandler = new DataHandler(fds);
        // Affix DIME type header to attachment before sending
        ((javax.xml.rpc.Stub) service)._setProperty(org.apache.axis.client.Call.ATTACHMENT_ENCAPSULATION_FORMAT,
                Call.ATTACHMENT_ENCAPSULATION_FORMAT_DIME);
        ((org.apache.axis.client.Stub) service).addAttachment(dhandler);
        // Create attachment
        service.createAttachment(sid, repHandle, objectHandle, description, fileName);
        System.out.println("Creating attachment...SUCCESS");

    }

    protected String getHandleFromResponse(InputStream xml) throws Exception {

        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
        Document doc = docBuilder.parse(xml);

        NodeList handles = doc.getElementsByTagName("Handle");
        int i = handles.getLength();

        for (int s = 0; s < handles.getLength(); s++) {
            Node handleNode = handles.item(s);
            return handleNode.getFirstChild().getNodeValue();
        }

        return "";
    }

    private String getGroupHandle(String groupName) {

        int sessionID = 0;
        String sql = "type = 2308 and delete_flag = 0 and last_name = '" + groupName + "'";

        try {
            sessionID = getWebService().login(wsUser, wsPassword);
            String result = getWebService().doSelect(sessionID, "cnt", sql, -1, new String[] { "last_name" });
            ByteArrayInputStream s = new ByteArrayInputStream(result.getBytes());
            return getHandleFromResponse(s);
        }
        catch (RemoteException e) {
            throw new RuntimeException("Error when getting group handle", e);
        }
        catch (Exception e) {
            throw new RuntimeException("Error when parsing xml response when searching for a group", e);
        }

    }

    public String createRequest(Properties requestParameters, String userId, List<File> files) {

        String result = null;
        int sessionID = 0;
        try {

            String contactHandle;
            sessionID = getWebService().login(wsUser, wsPassword);
            try {
                contactHandle = getHandleForUserid(sessionID, userId);
            }
            catch (Throwable e) {
                log.debug("Trying default contactHandle:" + e.getMessage());
                // Use the wsUser if the user is unknown
                contactHandle = getHandleForUserid(sessionID, wsUser);

                // contactHandle = wsUser;

            }
            requestParameters.setProperty("customer", contactHandle);
            List<String> lAttributes = new ArrayList<String>();
            List<String> lAttributeValues = new ArrayList<String>();

            for (Enumeration<Object> e = requestParameters.keys(); e.hasMoreElements();) {
                String key = (String) e.nextElement();
                lAttributes.add(key);
                lAttributeValues.add(key);
                lAttributeValues.add(requestParameters.getProperty(key));
            }

            String[] attributes = lAttributes.toArray(new String[0]);
            String[] attrVals = lAttributeValues.toArray(new String[0]);
            String[] propertyValues = new String[0];

            StringHolder newRequestHandle = new StringHolder();
            StringHolder newRequestNumber = new StringHolder();

            String template = "";

            result = getWebService().createRequest(sessionID, contactHandle, attrVals, propertyValues, template,
                    attributes, newRequestHandle, newRequestNumber);

            ByteArrayInputStream s = new ByteArrayInputStream(result.getBytes());

            String handle;
            try {
                handle = getHandleFromResponse(s);
            }
            catch (Exception e) {
                throw new RuntimeException("Error parsing handle to USD incident from xml response...\n" + result,
                        e);
            }

            if (handle != "") {
                if (files != null && files.size() > 0) {
                    int i = 0;
                    for (File f : files) {
                        try {
                            System.out.println("Attaching: " + f.getAbsolutePath());
                            this.createAttachment(getWebService(), sessionID, wsAttachmentRepHandle, handle,
                                    "Attachment " + i, f.getAbsolutePath());
                        }
                        catch (Exception e) {
                            throw new RuntimeException("Error creating attchment in USD", e);
                        }
                    }
                }
            }

        }
        catch (RemoteException e) {
            log.error("Failed to create request to USD Service", e);
            try {
                getWebService().logout(sessionID);
            }
            catch (RemoteException e1) {
                // No action
            }
            throw new RuntimeException(e);
        }
        finally {

            try {

                getWebService().logout(sessionID);

            }
            catch (RemoteException e) {
                log.warn(e);
            }
        }

        return result;
    }

    protected USD_WebServiceSoapSoapBindingStub getWebService() {
        if (webService == null) {
            Service service1 = new Service();
            try {
                webService = new USD_WebServiceSoapSoapBindingStub(endPoint, service1);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return webService;
    }

    public String getUSDGroupHandleForApplicationName(String appName) {
        String usdGroupName = usdAppToGroupMappings.getProperty(appName);
        System.out.println("usdAppToGroupMappings=" + usdAppToGroupMappings);
        String result = getGroupHandle(usdGroupName);
        if (result == null || result.length() == 0) {

            throw new RuntimeException("No group handle found for application name=" + appName);
        }
        return result;
    }

    public List<Issue> getIssuesForContact(String userId, Integer maxRows) {
        List<Issue> taskList = new ArrayList<Issue>();

        StringBuilder whereClause = new StringBuilder();
        int sessionID = 0;
        int endIndex = -1; // To fetch max number of rows, which according to spec is 250 per call

        // Handle end index input
        if (maxRows != null && maxRows >= -1) {
            endIndex = maxRows.intValue();
        }

        try {
            // Login and get session
            sessionID = getWebService().login(wsUser, wsPassword);

            // Get handle for user
            String contactHandle;
            try {
                contactHandle = getHandleForUserid(sessionID, userId);
            }
            catch (Throwable e) {
                log.error("Could not get handle for user with userId " + userId);
                // TODO: User not registered in USD? Should we inform the user?
                return taskList;
            }

            // Rid object type from handle
            if (contactHandle != null) {
                contactHandle = contactHandle.replace("cnt:", "");
            }

            // Build where clause
            whereClause.append("requestor.id = U'");
            whereClause.append(contactHandle);
            whereClause.append("' AND active = 1");

            // Get list xml
            String listXml = getWebService().doSelect(sessionID, "iss", whereClause.toString(), endIndex,
                    attributeNamesContactIssueList);

            // Get input stream for xml data
            ByteArrayInputStream bais = new ByteArrayInputStream(listXml.getBytes());

            // Parse xml to list
            taskList = getIssuesFromList(bais);

            return taskList;
        }
        catch (RemoteException e) {
            log.error("Failed to get issue list from USD Service for user=" + userId, e);
            try {
                getWebService().logout(sessionID);
            }
            catch (RemoteException e1) {
                // No action
            }
            throw new RuntimeException(e);
        }
        finally {
            try {
                getWebService().logout(sessionID);
            }
            catch (RemoteException e) {
                log.warn(e);
            }
        }
    }

    protected List<Issue> getIssuesFromList(InputStream xml) throws RuntimeException {
        List<Issue> issueList = new ArrayList<Issue>();

        try {
            // Parse the XML to get a DOM to query
            DocumentBuilderFactory dbfactory = DocumentBuilderFactory.newInstance();
            dbfactory.setNamespaceAware(true);
            dbfactory.setXIncludeAware(true);

            DocumentBuilder parser = dbfactory.newDocumentBuilder();
            Document doc = parser.parse(xml);

            // Get an XPath processor
            XPathFactory xpfactory = XPathFactory.newInstance();
            XPath xpathprocessor = xpfactory.newXPath();

            // Create XPath expressions
            String xpathUDSObject = "/UDSObjectList/UDSObject";
            XPathExpression udsObjectXPath = xpathprocessor.compile(xpathUDSObject);

            // Execute the XPath expressions
            NodeList udsObjects = (NodeList) udsObjectXPath.evaluate(doc, XPathConstants.NODESET);
            for (int i = 1; i < udsObjects.getLength() + 1; i++) {
                Issue issue = new Issue();

                // Get summary
                StringBuilder xPath = new StringBuilder();
                xPath.append("/UDSObjectList/UDSObject[");
                xPath.append(i);
                xPath.append("]/Attributes/Attribute[AttrName='summary']/AttrValue");

                XPathExpression attributeValueXPath = xpathprocessor.compile(xPath.toString());
                String value = (String) attributeValueXPath.evaluate(doc, XPathConstants.STRING);
                issue.setSummary(value);

                // Get description
                xPath = new StringBuilder();
                xPath.append("/UDSObjectList/UDSObject[");
                xPath.append(i);
                xPath.append("]/Attributes/Attribute[AttrName='description']/AttrValue");

                attributeValueXPath = xpathprocessor.compile(xPath.toString());
                value = (String) attributeValueXPath.evaluate(doc, XPathConstants.STRING);
                issue.setDescription(value);

                // Get status
                xPath = new StringBuilder();
                xPath.append("/UDSObjectList/UDSObject[");
                xPath.append(i);
                xPath.append("]/Attributes/Attribute[AttrName='status']/AttrValue");

                attributeValueXPath = xpathprocessor.compile(xPath.toString());
                value = (String) attributeValueXPath.evaluate(doc, XPathConstants.STRING);
                issue.setStatusId(value);

                // Get ref_num
                xPath = new StringBuilder();
                xPath.append("/UDSObjectList/UDSObject[");
                xPath.append(i);
                xPath.append("]/Attributes/Attribute[AttrName='ref_num']/AttrValue");

                attributeValueXPath = xpathprocessor.compile(xPath.toString());
                value = (String) attributeValueXPath.evaluate(doc, XPathConstants.STRING);
                // No "dead" issues...
                if (value == null) {
                    continue;
                }
                issue.setRefNum(Integer.valueOf(value));

                // Get web_url
                xPath = new StringBuilder();
                xPath.append("/UDSObjectList/UDSObject[");
                xPath.append(i);
                xPath.append("]/Attributes/Attribute[AttrName='web_url']/AttrValue");

                attributeValueXPath = xpathprocessor.compile(xPath.toString());
                value = (String) attributeValueXPath.evaluate(doc, XPathConstants.STRING);
                issue.setURL(value);

                // Add Issue object to list
                issueList.add(issue);
            }

            return issueList;
        }
        catch (Exception e) {
            log.error("Error when trying to parse issue list from XML", e);
            throw new RuntimeException("Error when trying to parse issue list from XML", e);
        }

    }

    private String getHandleForUserid(int sessionID, String userId) throws RemoteException {
        return new StringBuffer(getWebService().getHandleForUserid(sessionID, userId)).toString();
    }
}