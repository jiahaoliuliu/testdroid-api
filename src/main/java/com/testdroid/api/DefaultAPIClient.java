package com.testdroid.api;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.testdroid.api.model.APIUser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;

/**
 *
 * @author kajdus
 */
public class DefaultAPIClient implements APIClient {

    private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static Credential CREDENTIAL = new Credential.Builder(BearerToken.queryParameterAccessMethod()).build();
    private static String API_URI = "/api/v2";
    
    private String cloudURL;
    private String apiURL;
    private String username;
    private String password;
    private String accessToken;

    public DefaultAPIClient(String cloudURL, String username, String password) {
        this.cloudURL = cloudURL;
        this.apiURL = cloudURL + API_URI;
        this.username = username;
        this.password = password;
    }
    
    private String getAccessToken() {
        if(accessToken == null) {
            try {
                accessToken = acquireAccessToken();
            }
            catch(APIException ex) {
                ex.printStackTrace();
                // Do nothing, leave null
            }
        }
        return accessToken;
    }
    
    private String acquireAccessToken() throws APIException {
        try {
            HttpRequest request = HTTP_TRANSPORT.createRequestFactory().buildGetRequest(new GenericUrl(
                    String.format("%s/oauth/token?client_id=testdroid-cloud-api&client_secret=qwerty&grant_type=password&username=%s&password=%s",
                    cloudURL, username, password)));
            HttpResponse response = request.execute();
            if(response.getStatusCode() != 200) {
                throw new APIException(response.getStatusCode(), "Failed to acquire access token");
            }
            String content = StringUtils.join(IOUtils.readLines(response.getContent()), "\n");
            JSONObject json = JSONObject.fromObject(content);
            return json.optString("access_token");
        }
        catch(IOException ex) {
            throw new APIException("Failed to acquire access token", ex);
        }
    }
    
    @Override
    public <T extends APIEntity> T get(String uri, Class<T> type) throws APIException {
        try {
            return getOnce(uri, type);
        }
        catch(APIException ex) {
            if(ex.getStatus() != null && HttpStatus.SC_UNAUTHORIZED == ex.getStatus()) {
                // Access token may have expired. Clean and try again.
                accessToken = null;
                return getOnce(uri, type);
            }
            else {
                throw ex;
            }
        }
    }
    
    /**
     * Tries to call API once. Returns expected entity or throws exception.
     */
    private <T extends APIEntity> T getOnce(String uri, Class<T> type) throws APIException {
        // Build request
        CREDENTIAL.setAccessToken(getAccessToken());
        HttpRequestFactory factory = HTTP_TRANSPORT.createRequestFactory(new HttpRequestInitializer() {

            @Override
            public void initialize(HttpRequest request) throws IOException {
                CREDENTIAL.initialize(request);
            }
        });
        HttpRequest request = null;
        HttpResponse response = null;
        try {
             request = factory.buildGetRequest(new GenericUrl(apiURL + uri));
             request.setHeaders(new HttpHeaders().setAccept("application/xml"));

            // Call request and parse result
            JAXBContext context = JAXBContext.newInstance(type);

            Unmarshaller unmarshaller = context.createUnmarshaller();
            response = request.execute();
            T result = (T) unmarshaller.unmarshal(response.getContent());
            result.client = this;
            result.selfURI = uri;
            return result;
        }
        catch(JAXBException ex) {
            throw new APIException(response != null ? response.getStatusCode() : null, String.format("Failed to parse response as %s", type.getName()), ex);
        }
        catch(IOException ex) {
            throw new APIException(String.format("Failed to execute API call: %s", uri), ex);
        }
    }

    @Override
    public <T extends APIEntity> T post(String uri, Object body, Class<T> type) throws APIException {
        try {
            return postOnce(uri, body, type);
        }
        catch(APIException ex) {
            if(ex.getStatus() != null && HttpStatus.SC_UNAUTHORIZED == ex.getStatus()) {
                // Access token may have expired. Clean and try again.
                accessToken = null;
                return postOnce(uri, body, type);
            }
            else {
                throw ex;
            }
        }
    }

    private <T extends APIEntity> T postOnce(String uri, Object body, Class<T> type) throws APIException {
        // Build request
        CREDENTIAL.setAccessToken(getAccessToken());
        HttpRequestFactory factory = HTTP_TRANSPORT.createRequestFactory(new HttpRequestInitializer() {

            @Override
            public void initialize(HttpRequest request) throws IOException {
                CREDENTIAL.initialize(request);
            }
        });
        HttpRequest request = null;
        HttpResponse response = null;
        try {
             request = factory.buildPostRequest(new GenericUrl(apiURL + uri), new UrlEncodedContent(body));
             request.setHeaders(new HttpHeaders().setAccept("application/xml"));

            // Call request and parse result
            JAXBContext context = JAXBContext.newInstance(type);

            Unmarshaller unmarshaller = context.createUnmarshaller();
            response = request.execute();
            T result = (T) unmarshaller.unmarshal(response.getContent());
            result.client = this;
            result.selfURI = uri;
            // In case of entity creation, we need to update its url
            if(response.getStatusCode() == HttpStatus.SC_CREATED && result.hasId()) {
                result.selfURI += String.format("/%s", result.getId());
            }
            return result;
        }
        catch(JAXBException ex) {
            throw new APIException(response != null ? response.getStatusCode() : null, String.format("Failed to parse response as %s", type.getName()), ex);
        }
        catch(IOException ex) {
            throw new APIException(String.format("Failed to execute API call: %s", uri), ex);
        }
    }
    
    @Override
    public void delete(String uri) throws APIException {
        try {
            deleteOnce(uri);
        }
        catch(APIException ex) {
            if(ex.getStatus() != null && HttpStatus.SC_UNAUTHORIZED == ex.getStatus()) {
                // Access token may have expired. Clean and try again.
                accessToken = null;
                deleteOnce(uri);
            }
            else {
                throw ex;
            }
        }
    }
    
    private void deleteOnce(String uri) throws APIException {
        // Build request
        CREDENTIAL.setAccessToken(getAccessToken());
        HttpRequestFactory factory = HTTP_TRANSPORT.createRequestFactory(new HttpRequestInitializer() {

            @Override
            public void initialize(HttpRequest request) throws IOException {
                CREDENTIAL.initialize(request);
            }
        });
        HttpRequest request = null;
        HttpResponse response = null;
        try {
             request = factory.buildDeleteRequest(new GenericUrl(apiURL + uri));
             request.setHeaders(new HttpHeaders().setAccept("application/xml"));

            response = request.execute();
            if(response == null) {
                throw new APIException("No response from API");
            }
            if(response.getStatusCode() != HttpStatus.SC_OK) {
                throw new APIException(response.getStatusCode(), "Failed to delete resource: " + response.getStatusMessage());
            }
        }
        catch(IOException ex) {
            throw new APIException(String.format("Failed to execute API call: %s", uri), ex);
        }
    }

    @Override
    public APIUser me() throws APIException {
        return get("/me", APIUser.class);
    }

}