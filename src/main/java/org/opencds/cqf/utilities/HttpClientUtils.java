package org.opencds.cqf.utilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.opencds.cqf.utilities.IOUtils.Encoding;

import ca.uhn.fhir.context.FhirContext;

public class HttpClientUtils {
    public static void post(String fhirServerUrl, IAnyResource resource, Encoding encoding, FhirContext fhirContext)
            throws IOException {  
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            HttpPost post = new HttpPost(fhirServerUrl);
            post.addHeader("content-type", "application/json");

            String resourceString = IOUtils.parseResourceAsString(resource, encoding, fhirContext);
            StringEntity input = new StringEntity(resourceString);
            post.setEntity(input);
            HttpResponse response = httpClient.execute(post);
            BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
            String line = "";
            while ((line = rd.readLine()) != null) {
                System.out.println(line);
            }
        //TODO: add summary of what was done
        } catch (IOException e) {
        
            // handle
        
        }    
    }
}