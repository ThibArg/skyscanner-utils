/*
 * (C) Copyright ${year} Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thibaud Arguillere
 */

package org.nuxeo.skyscanner.crop;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.binary.metadata.internals.operations.TriggerMetadataMappingOnDocument;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationChain;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.collectors.DocumentModelCollector;
import org.nuxeo.ecm.automation.core.collectors.BlobCollector;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.runtime.api.Framework;

/**
 * Specific operation for SkyScanner demo/POC: Basically a wrapper for the
 * "ImageCrop" operation from nuxeo-labs.
 * <p>
 * The picture is cropped, and the result is saved in a new Picture document in
 * the "Cropped Pictures" Workspace, which is created if it does not exist
 * <p>
 * IMPORTANT: nuxeo-labs MUST BE INSTALLED
 * 
 */
@Operation(id = SkyScannerCropAndSaveInCroppedPictures.ID, category = Constants.CAT_CONVERSION, label = "Crop and Save in 'Cropped Pictures'", description = "Crop the picture and save the result in a new Picture dcument, in the 'Cropped Pictures', return the created document Workspace. WARNING: We assume nuxeo-labs is installed")
public class SkyScannerCropAndSaveInCroppedPictures {

    public static final String ID = "SkyScannerCropAndSaveInCroppedPictures";

    private static final String CROP_OPERATION = "ImageCrop";
    
    private static final Log log = LogFactory.getLog(SkyScannerCropAndSaveInCroppedPictures.class);

    @Context
    protected CoreSession session;

    @Context
    protected AutomationService automationService;

    @Param(name = "title", required = false)
    protected String title = "";

    @Param(name = "top", required = false)
    protected long top = 0;

    @Param(name = "left", required = false)
    protected long left = 0;

    @Param(name = "width", required = false)
    protected long width = 0;

    @Param(name = "height", required = false)
    protected long height = 0;

    @Param(name = "pictureWidth", required = false)
    protected long pictureWidth = 0;

    @Param(name = "pictureHeight", required = false)
    protected long pictureHeight = 0;

    @Param(name = "targetFileName", required = false)
    protected String targetFileName = "";

    @Param(name = "targetFileNameSuffix", required = false)
    protected String targetFileNameSuffix = "";

    @Param(name = "watermarkPosition", required = false, widget = Constants.W_OPTION,  values = {"Top Right", "Top Left", "Bottom Right", "Bottom Left"} )
    protected String watermarkPosition = "Top Right";

    @Param(name = "watermarkDocId", required = false)
    protected String watermarkDocId = "";

    @OperationMethod
    public DocumentModel run(DocumentModel inDoc) throws OperationException {

        // Possibly, nothing to do.
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Invalid width and/or height (should not be <= 0");
        }

        if (!inDoc.hasFacet("Picture")) {
            throw new ClientException(
                    String.format(
                            "The document (id:'%s') with title '%s' doesn't have the 'Picture' facet",
                            inDoc.getId(), inDoc.getTitle()));
        }

        Blob pictureBlob = (Blob) inDoc.getPropertyValue("file:content");

        if (targetFileNameSuffix == null || targetFileNameSuffix.isEmpty()) {
            targetFileNameSuffix = "-crop" + top + "-" + left + "-" + width
                    + "x" + height;
        }

        // Call nuxeo-labs crop operation
        AutomationService service = Framework.getLocalService(AutomationService.class);
        // Input setting
        OperationContext ctx = new OperationContext(session);
        ctx.setInput(pictureBlob);

        OperationChain chain;
        chain = new OperationChain("SkyScannerDoTheCrop_Chain");
        chain.add(CROP_OPERATION).set("top", top).set("left", left).set(
                "width", width).set("height", height).set("pictureWidth",
                pictureWidth).set("pictureHeight", pictureHeight).set(
                "targetFileName", targetFileName).set("targetFileNameSuffix",
                targetFileNameSuffix);
        Blob cropped = (Blob) automationService.run(ctx, chain);

        /*
         * if (title == null || title.isEmpty()) { title = "Crop-" + top + "-" +
         * left + "-" + width + "x" + height; }
         */
        title = cropped.getFilename();
        DocumentModel result = MiscTools.addToCroppedPictures(session, title,
                cropped);
        
        // Force trigger the metadata mapping defined in the Studio project
        // (but ignore in case of problem)
        try {
            chain = new OperationChain("SkyScannerDoMatadataMapping_Chain");
            ctx.setInput(result);
            chain.add(TriggerMetadataMappingOnDocument.ID).set("metadataMappingId", "imageInfo");
            automationService.run(ctx, chain);
            
        } catch(Exception e) {
            log.error("Error getting the 'imageInfo' mapping - should be defined in the Studio project", e);
        }
        

        // Now, we create the relation
        // Relations.CreateRelation
        chain = new OperationChain("SkyScannerAddRelation_Chain");
        ctx.setInput(result);
        chain.add("Relations.CreateRelation").set("object", inDoc.getId()).set(
                "predicate", "http://purl.org/dc/terms/IsBasedOn");
        result = (DocumentModel) automationService.run(ctx, chain);

        return result;
    }

}
