package org.opencds.cqf.tooling.processor;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.opencds.cqf.tooling.parameter.PostBundlesInDirParameters;
import org.opencds.cqf.tooling.utilities.HttpClientUtils;
import org.opencds.cqf.tooling.utilities.IOUtils;
import org.opencds.cqf.tooling.utilities.IOUtils.Encoding;
import org.opencds.cqf.tooling.utilities.ResourceUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;

public class PostBundlesInDirProcessor {
    public enum FHIRVersion {
        FHIR3("fhir3"), FHIR4("fhir4");

        private String string;

        public String toString() {
            return this.string;
        }

        private FHIRVersion(String string) {
            this.string = string;
        }

        public static FHIRVersion parse(String value) {
            switch (value) {
            case "fhir3":
                return FHIR3;
            case "fhir4":
                return FHIR4;
            default:
                throw new RuntimeException("Unable to parse FHIR version value:" + value);
            }
        }
    }

    public static FhirContext getFhirContext(FHIRVersion fhirVersion)
        {
            switch (fhirVersion) {
                case FHIR3:
                    return FhirContext.forDstu3();
                case FHIR4:
                    return FhirContext.forR4();
                default:
                    throw new IllegalArgumentException("Unknown IG version: " + fhirVersion);
            }     
        }

    public static void PostBundlesInDir(PostBundlesInDirParameters params) {
        String directoryPath = params.directoryPath;
        String fhirUri = params.fhirUri;
        FHIRVersion fhirVersion = params.fhirVersion;
        Encoding encoding = params.encoding;
        FhirContext fhirContext = getFhirContext(fhirVersion);

        File dir = new File(directoryPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("path to directory must be an existing directory.");
        }

        List<String> filePaths = IOUtils.getFilePaths(directoryPath, true).stream().filter(x -> !x.endsWith(".cql")).collect(Collectors.toList());
        List<IBaseResource> resources = IOUtils.readResources(filePaths, fhirContext);

        RuntimeResourceDefinition bundleDefinition = (RuntimeResourceDefinition)ResourceUtils.getResourceDefinition(fhirContext, "Bundle");
        String bundleClassName = bundleDefinition.getImplementingClass().getName();
        resources.stream()
            .filter(entry -> entry != null)
            .filter(entry ->  bundleClassName.equals(entry.getClass().getName()))
            .forEach(entry -> postBundleToFhirUri(fhirUri, encoding, fhirContext, entry));        
    }

	private static void postBundleToFhirUri(String fhirUri, Encoding encoding, FhirContext fhirContext, IBaseResource bundle) {
        if (fhirUri != null && !fhirUri.equals("")) {  
            try {
                HttpClientUtils.post(fhirUri, (IBaseResource) bundle, encoding, fhirContext);
                System.out.println("Resource successfully posted to FHIR server (" + fhirUri + "): " + ((IBaseResource)bundle).getIdElement().getIdPart());
            } catch (Exception e) {
                System.out.println(((IBaseResource)bundle).getIdElement().getIdPart() + e);             
            }  
        }
    }
}