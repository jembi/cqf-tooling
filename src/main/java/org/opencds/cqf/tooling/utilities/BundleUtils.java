package org.opencds.cqf.tooling.utilities;

import java.util.List;
import java.util.UUID;

import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.context.FhirContext;

public class BundleUtils {
    
    public static Object bundleArtifacts(String id, List<IBaseResource> resources, FhirContext fhirContext) {
        for (IBaseResource resource : resources) {
            if (resource.getIdElement().getIdPart() == null || resource.getIdElement().getIdPart().equals("")) {
                ResourceUtils.setIgId(id.replace("-bundle", "-" + UUID.randomUUID()), resource, false);
                resource.setId(resource.getClass().getSimpleName() + "/" + resource.getIdElement().getIdPart());
            }
        }
        
        switch (fhirContext.getVersion().getVersion()) {
            case DSTU3:
                return bundleStu3Artifacts(id, resources);
            case R4:
                return bundleR4Artifacts(id, resources);
            default:
                throw new IllegalArgumentException("Unknown fhir version: " + fhirContext.getVersion().getVersion().getFhirVersionString());
        }
    }

    public static org.hl7.fhir.dstu3.model.Bundle bundleStu3Artifacts(String id, List<IBaseResource> resources)
    {
        org.hl7.fhir.dstu3.model.Bundle bundle = new org.hl7.fhir.dstu3.model.Bundle();
        ResourceUtils.setIgId(id, bundle, false);
        bundle.setType(org.hl7.fhir.dstu3.model.Bundle.BundleType.TRANSACTION);
        for (IBaseResource resource : resources)
        {
            bundle.addEntry(
            new org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent()
                    .setResource((org.hl7.fhir.dstu3.model.Resource) resource)
                    .setRequest(
                            new org.hl7.fhir.dstu3.model.Bundle.BundleEntryRequestComponent()
                                    .setMethod(org.hl7.fhir.dstu3.model.Bundle.HTTPVerb.PUT)
                                    .setUrl(((org.hl7.fhir.dstu3.model.Resource) resource).getId())
                    )
            );
        }
        return bundle;
    }

    public static org.hl7.fhir.r4.model.Bundle bundleR4Artifacts(String id, List<IBaseResource> resources)
    {
        org.hl7.fhir.r4.model.Bundle bundle = new org.hl7.fhir.r4.model.Bundle();
        ResourceUtils.setIgId(id, bundle, false);
        bundle.setType(org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION);
        for (IBaseResource resource : resources)
        {            
            String resourceRef = (resource.getIdElement().getResourceType() == null) ? resource.fhirType() + "/" + resource.getIdElement().getIdPart() : resource.getIdElement().getIdPart() ;
            bundle.addEntry(
            new org.hl7.fhir.r4.model.Bundle.BundleEntryComponent()
                    .setResource((org.hl7.fhir.r4.model.Resource) resource)
                    .setRequest(
                            new org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent()
                                    .setMethod(org.hl7.fhir.r4.model.Bundle.HTTPVerb.PUT)
                                    .setUrl(resourceRef)//shouldnt this be canonicalUrl?
                    )
            );
        }
        return bundle;
    }
}
