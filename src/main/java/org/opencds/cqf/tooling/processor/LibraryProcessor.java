package org.opencds.cqf.tooling.processor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.fhir.ucum.UcumEssenceService;
import org.fhir.ucum.UcumException;
import org.fhir.ucum.UcumService;
import org.hl7.fhir.Resource;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r5.model.Library;
import org.hl7.fhir.r5.model.Attachment;
import org.hl7.fhir.r5.model.ImplementationGuide;
import org.hl7.fhir.r5.model.RelatedArtifact;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.Utilities;
import org.opencds.cqf.tooling.npm.LibraryLoader;
import org.opencds.cqf.tooling.parameter.RefreshLibraryParameters;
import org.opencds.cqf.tooling.utilities.IGUtils;
import org.opencds.cqf.tooling.utilities.IOUtils;
import org.opencds.cqf.tooling.utilities.IOUtils.Encoding;
import org.opencds.cqf.tooling.utilities.LogUtils;
import org.opencds.cqf.tooling.utilities.ResourceUtils;

import ca.uhn.fhir.context.FhirContext;

public class LibraryProcessor extends BaseProcessor {
    public static final String ResourcePrefix = "library-";   
    public static String getId(String baseId) {
        return ResourcePrefix + baseId;
    }

    public static Boolean bundleLibraryDependencies(String path, FhirContext fhirContext, Map<String, IAnyResource> resources,
            Encoding encoding, boolean versioned) {
        Boolean shouldPersist = true;
        try {
            Map<String, IAnyResource> dependencies = ResourceUtils.getDepLibraryResources(path, fhirContext, encoding);
            String currentResourceID = IOUtils.getTypeQualifiedResourceId(path, fhirContext);
            for (IAnyResource resource : dependencies.values()) {
                resources.putIfAbsent(resource.getId(), resource);

                // NOTE: Assuming dependency library will be in directory of dependent.
                String dependencyPath = IOUtils.getResourceFileName(IOUtils.getResourceDirectory(path), resource, encoding, fhirContext, versioned);
                bundleLibraryDependencies(dependencyPath, fhirContext, resources, encoding, versioned);
            }
        } catch (Exception e) {
            shouldPersist = false;
            LogUtils.putException(path, e);
        }
        return shouldPersist;
    }

    private UcumService ucumService;
    private List<String> binaryPaths;
    private CqlProcessor cqlProcessor;
    protected boolean versioned;

    /*
    Refreshes generated content in the given library.
    The name element of the library resource is used to find the cql file (filename = <name>.cql)
    The CqlProcessor is used to get the CqlSourceFileInformation
    Sets
        * cqlContent
        * elmXmlContent
        * elmJsonContent
        * dataRequirements
        * relatedArtifacts
        * parameters

     Does not set publisher-level information (id, name, url, version, publisher, contact, jurisdiction)
     Does not generate narrative
     */
    protected Library refreshGeneratedContent(Library sourceLibrary) {
        String libraryName = sourceLibrary.getName();
        if (versioned) {
            libraryName += "-" + sourceLibrary.getVersion();
        }
        String fileName = libraryName + ".cql";
        Attachment attachment = null;
        try {
            attachment = loadFile(fileName);
        } catch (IOException e) {
            logMessage(String.format("Error loading CQL source for library %s", libraryName));
            e.printStackTrace();
        }

        if (attachment != null) {
            sourceLibrary.getContent().clear();
            sourceLibrary.getContent().add(attachment);
            CqlProcessor.CqlSourceFileInformation info = cqlProcessor.getFileInformation(attachment.getUrl());
            attachment.setUrlElement(null);
            if (info != null) {
                //f.getErrors().addAll(info.getErrors());
                if (info.getElm() != null) {
                    sourceLibrary.addContent().setContentType("application/elm+xml").setData(info.getElm());
                }
                if (info.getJsonElm() != null) {
                    sourceLibrary.addContent().setContentType("application/elm+json").setData(info.getJsonElm());
                }
                sourceLibrary.getDataRequirement().clear();
                sourceLibrary.getDataRequirement().addAll(info.getDataRequirements());
                sourceLibrary.getRelatedArtifact().removeIf(n -> n.getType() == RelatedArtifact.RelatedArtifactType.DEPENDSON);
                sourceLibrary.getRelatedArtifact().addAll(info.getRelatedArtifacts());
                sourceLibrary.getParameter().clear();
                sourceLibrary.getParameter().addAll(info.getParameters());
            } else {
                logMessage(String.format(String.format("No cql info found for ", fileName)));
                //f.getErrors().add(new ValidationMessage(ValidationMessage.Source.Publisher, ValidationMessage.IssueType.NOTFOUND, "Library", "No cql info found for "+f.getName(), ValidationMessage.IssueSeverity.ERROR));
            }
        }

        return sourceLibrary;
    }

    protected List<Library> refreshGeneratedContent(List<Library> sourceLibraries) {
        try {
            binaryPaths = IGUtils.extractBinaryPaths(rootDir, sourceIg);
        }
        catch (IOException e) {
            logMessage(String.format("Errors occurred extracting binary path from IG: ", e.getMessage()));
            throw new IllegalArgumentException("Could not obtain binary path from IG");
        }

        LibraryLoader reader = new LibraryLoader(fhirVersion);
        try {
            ucumService = new UcumEssenceService(UcumEssenceService.class.getResourceAsStream("/ucum-essence.xml"));
        } catch (UcumException e) {
            System.err.println("Could not create UCUM validation service:");
            e.printStackTrace();
        }
        cqlProcessor = new CqlProcessor(packageManager.getNpmList(), binaryPaths, reader, this, ucumService,
                packageId, canonicalBase);

        cqlProcessor.execute();

        List<Library> resources = new ArrayList<Library>();
        for (Library library : sourceLibraries) {
            resources.add(refreshGeneratedContent(library));
        }
        return resources;
    }

    private Attachment loadFile(String fn) throws FileNotFoundException, IOException {
        for (String dir : binaryPaths) {
            File f = new File(Utilities.path(dir, fn));
            if (f.exists()) {
                Attachment att = new Attachment();
                att.setContentType("text/cql");
                att.setData(TextFile.fileToBytes(f));
                att.setUrl(f.getAbsolutePath());
                return att;
            }
        }
        return null;
    }

/*
    private void performLibraryCQLProcessing(FetchedFile f, org.hl7.fhir.r5.model.Library lib, Attachment attachment) {

    }
 */

    public Boolean refreshLibraryContent(RefreshLibraryParameters params) {
        return false;
    }
}