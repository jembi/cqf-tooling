package org.opencds.cqf.tooling.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.opencds.cqf.tooling.measure.RefreshGeneratedContent;
import org.opencds.cqf.tooling.measure.r4.RefreshR4Measure;
import org.opencds.cqf.tooling.measure.stu3.RefreshStu3Measure;
import org.opencds.cqf.tooling.utilities.BundleUtils;
import org.opencds.cqf.tooling.utilities.HttpClientUtils;
import org.opencds.cqf.tooling.utilities.IOUtils;
import org.opencds.cqf.tooling.utilities.IOUtils.Encoding;
import org.opencds.cqf.tooling.utilities.LogUtils;
import org.opencds.cqf.tooling.utilities.ResourceUtils;

import ca.uhn.fhir.context.FhirContext;

public class MeasureProcessor
{      
    public static final String ResourcePrefix = "measure-";   
    public static String getId(String baseId) {
        return ResourcePrefix + baseId;
    }

    public static ArrayList<String> refreshIgMeasureContent(String igPath, Encoding outputEncoding, Boolean versioned, FhirContext fhirContext, String measureToRefreshPath) {
        System.out.println("Refreshing measures...");
        ArrayList<String> refreshedMeasureNames = new ArrayList<String>();
        HashSet<String> measurePaths = IOUtils.getMeasurePaths(fhirContext);
        RefreshGeneratedContent refresher = null;

        // Filter to specific measure if specified in arguments.
        Boolean hasMeasureToRefreshpath = measureToRefreshPath != null && !measureToRefreshPath.isEmpty();
        if (hasMeasureToRefreshpath) {
            measurePaths.removeIf(mp -> !mp.equals(measureToRefreshPath));
        }

        for (String path : measurePaths) {
            try {
                switch (fhirContext.getVersion().getVersion()) {
                    case DSTU3:
                        refresher = new RefreshStu3Measure(path);
                        break;
                    case R4:
                        refresher = new RefreshR4Measure(path);
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "Unknown fhir version: " + fhirContext.getVersion().getVersion().getFhirVersionString());
                }

                refresher.refreshGeneratedContent();
                refreshedMeasureNames.add(FilenameUtils.getBaseName(path).replace(MeasureProcessor.ResourcePrefix, ""));
            } catch (Exception e) {
                LogUtils.putException(path, e);
            }
            finally {
                LogUtils.warn(path);
            }
        }

        return refreshedMeasureNames;
    }

    public static void bundleMeasures(ArrayList<String> refreshedLibraryNames, String igPath, Boolean includeDependencies,
            Boolean includeTerminology, Boolean includePatientScenarios, Boolean includeVersion, FhirContext fhirContext, String fhirUri,
            Encoding encoding) {
        // The set to bundle should be the union of the successfully refreshed Measures
        // and Libraries
        // Until we have the ability to refresh Measures, the set is the union of
        // existing Measures and successfully refreshed Libraries
        HashSet<String> measureSourcePaths = IOUtils.getMeasurePaths(fhirContext);
        List<String> measurePathLibraryNames = new ArrayList<String>();
        for (String measureSourcePath : measureSourcePaths) {
            measurePathLibraryNames
                    .add(FilenameUtils.getBaseName(measureSourcePath).replace(MeasureProcessor.ResourcePrefix, ""));
        }

        List<String> bundledMeasures = new ArrayList<String>();
        for (String refreshedLibraryName : refreshedLibraryNames) {
            try {
                if (!measurePathLibraryNames.contains(refreshedLibraryName)) {
                    continue;
                }

                Map<String, IBaseResource> resources = new HashMap<String, IBaseResource>();

                String refreshedLibraryFileName = IOUtils.formatFileName(refreshedLibraryName, encoding, fhirContext);
                String librarySourcePath;
                try {
                    librarySourcePath = IOUtils.getLibraryPathAssociatedWithCqlFileName(refreshedLibraryFileName, fhirContext);
                } catch (Exception e) {
                    LogUtils.putException(refreshedLibraryName, e);
                    continue;
                } finally {
                    LogUtils.warn(refreshedLibraryName);
                }
                
                String measureSourcePath = "";
                for (String path : measureSourcePaths) {
                    if (path.endsWith(refreshedLibraryFileName))
                    {
                        measureSourcePath = path;
                    }
                }

                Boolean shouldPersist = ResourceUtils.safeAddResource(measureSourcePath, resources, fhirContext);
                shouldPersist = shouldPersist
                        & ResourceUtils.safeAddResource(librarySourcePath, resources, fhirContext);

                String cqlFileName = IOUtils.formatFileName(refreshedLibraryName, Encoding.CQL, fhirContext);
                List<String> cqlLibrarySourcePaths = IOUtils.getCqlLibraryPaths().stream()
                    .filter(path -> path.endsWith(cqlFileName))
                    .collect(Collectors.toList());
                String cqlLibrarySourcePath = (cqlLibrarySourcePaths.isEmpty()) ? null : cqlLibrarySourcePaths.get(0);
                if (includeTerminology) {
                    shouldPersist = shouldPersist
                        & ValueSetsProcessor.bundleValueSets(cqlLibrarySourcePath, igPath, fhirContext, resources, encoding, includeDependencies, includeVersion);
                }

                if (includeDependencies) {
                    shouldPersist = shouldPersist
                        & LibraryProcessor.bundleLibraryDependencies(librarySourcePath, fhirContext, resources, encoding);
                }

                if (includePatientScenarios) {
                    shouldPersist = shouldPersist
                        & TestCaseProcessor.bundleTestCases(igPath, refreshedLibraryName, fhirContext, resources);
                }

                if (shouldPersist) {
                    String bundleDestPath = FilenameUtils.concat(IGProcessor.getBundlesPath(igPath), refreshedLibraryName);
                    persistBundle(igPath, bundleDestPath, refreshedLibraryName, encoding, fhirContext, new ArrayList<IBaseResource>(resources.values()), fhirUri);
                    bundleFiles(igPath, bundleDestPath, refreshedLibraryName, measureSourcePath, librarySourcePath, fhirContext, encoding, includeTerminology, includeDependencies, includePatientScenarios, includeVersion);
                    bundledMeasures.add(refreshedLibraryName);
                }
            } catch (Exception e) {
                LogUtils.putException(refreshedLibraryName, e);
            } finally {
                LogUtils.warn(refreshedLibraryName);
            }
        }
        String message = "\r\n" + bundledMeasures.size() + " Measures successfully bundled:";
        for (String bundledMeasure : bundledMeasures) {
            message += "\r\n     " + bundledMeasure + " BUNDLED";
        }

        ArrayList<String> failedMeasures = new ArrayList<>(measurePathLibraryNames);
        measurePathLibraryNames.removeAll(bundledMeasures);
        measurePathLibraryNames.retainAll(refreshedLibraryNames);
        message += "\r\n" + measurePathLibraryNames.size() + " Measures refreshed, but not bundled (due to issues):";
        for (String notBundled : measurePathLibraryNames) {
            message += "\r\n     " + notBundled + " REFRESHED";
        }

        failedMeasures.removeAll(bundledMeasures);
        failedMeasures.removeAll(measurePathLibraryNames);
        message += "\r\n" + failedMeasures.size() + " Measures failed refresh:";
        for (String failed : failedMeasures) {
            message += "\r\n     " + failed + " FAILED";
        }

        LogUtils.info(message);
    }

    private static void persistBundle(String igPath, String bundleDestPath, String libraryName, Encoding encoding, FhirContext fhirContext, List<IBaseResource> resources, String fhirUri) {
        IOUtils.initializeDirectory(bundleDestPath);
        Object bundle = BundleUtils.bundleArtifacts(libraryName, resources, fhirContext);
        IOUtils.writeBundle(bundle, bundleDestPath, encoding, fhirContext);

        if (fhirUri != null && !fhirUri.equals("")) {
            try {
                HttpClientUtils.post(fhirUri, (IBaseResource) bundle, encoding, fhirContext);
            } catch (IOException e) {
                LogUtils.putException(((IBaseResource)bundle).getIdElement().getIdPart(), "Error posting to FHIR Server: " + fhirUri + ".  Bundle not posted.");
            }
        }
    }

    private static void bundleFiles(String igPath, String bundleDestPath, String libraryName, String resourceFocusSourcePath, String librarySourcePath, FhirContext fhirContext, Encoding encoding, Boolean includeTerminology, Boolean includeDependencies, Boolean includePatientScenarios, Boolean includeVersion) {
        String bundleDestFilesPath = FilenameUtils.concat(bundleDestPath, libraryName + "-" + IGBundleProcessor.bundleFilesPathElement);
        IOUtils.initializeDirectory(bundleDestFilesPath);

        IOUtils.copyFile(resourceFocusSourcePath, FilenameUtils.concat(bundleDestFilesPath, FilenameUtils.getName(resourceFocusSourcePath)));
        IOUtils.copyFile(librarySourcePath, FilenameUtils.concat(bundleDestFilesPath, FilenameUtils.getName(librarySourcePath)));

        String cqlFileName = IOUtils.formatFileName(libraryName, Encoding.CQL, fhirContext);
        List<String> cqlLibrarySourcePaths = IOUtils.getCqlLibraryPaths().stream()
            .filter(path -> path.endsWith(cqlFileName))
            .collect(Collectors.toList());
        String cqlLibrarySourcePath = (cqlLibrarySourcePaths.isEmpty()) ? null : cqlLibrarySourcePaths.get(0);
        String cqlDestPath = FilenameUtils.concat(bundleDestFilesPath, cqlFileName);
        IOUtils.copyFile(cqlLibrarySourcePath, cqlDestPath);

        if (includeTerminology) {  
            try {     
                Map<String, IBaseResource> valuesets = ResourceUtils.getDepValueSetResources(cqlLibrarySourcePath, igPath, fhirContext, includeDependencies, includeVersion);      
                if (!valuesets.isEmpty()) {
                    Object bundle = BundleUtils.bundleArtifacts(ValueSetsProcessor.getId(libraryName), new ArrayList<IBaseResource>(valuesets.values()), fhirContext);
                    IOUtils.writeBundle(bundle, bundleDestFilesPath, encoding, fhirContext);  
                }  
            }  catch (Exception e) {
                LogUtils.putException(libraryName, e.getMessage());
            }       
        }
        
        if (includeDependencies) {
            Map<String, IBaseResource> depLibraries = ResourceUtils.getDepLibraryResources(librarySourcePath, fhirContext, encoding);
            if (!depLibraries.isEmpty()) {
                String depLibrariesID = "library-deps-" + libraryName;
                Object bundle = BundleUtils.bundleArtifacts(depLibrariesID, new ArrayList<IBaseResource>(depLibraries.values()), fhirContext);            
                IOUtils.writeBundle(bundle, bundleDestFilesPath, encoding, fhirContext);  
            }        
        }

         if (includePatientScenarios) {
            TestCaseProcessor.bundleTestCaseFiles(igPath, libraryName, bundleDestFilesPath, fhirContext);
        }        
    }
}
