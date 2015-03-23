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

import java.io.File;
import java.io.IOException;
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
import org.nuxeo.ecm.automation.core.util.Properties;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
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
    public DocumentModel run(DocumentModel inDoc) throws OperationException, IOException {

        Blob processedPict = null;

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

        // ============================== Get the watermark
        if(watermarkDocId == null || watermarkDocId.isEmpty()) {
            throw new ClientException("Missing watermark document id");
        }
        DocumentModel watermarkPicture = session.getDocument(new IdRef(watermarkDocId));
        DocumentModel watermarkDoc = session.getDocument(new IdRef(
                watermarkDocId));
        // We need a file on disc
        Blob watermark = (Blob) watermarkDoc.getPropertyValue("file:content");
        String suffix = watermark.getFilename();
        int pos = suffix.lastIndexOf(".");
        if (pos > 0) {
            suffix = suffix.substring(pos);
        } else {
            suffix = "";
        }
        File watermarkFile = File.createTempFile("SkyScannerCropWM-", suffix);
        watermark.transferTo(watermarkFile);
        watermarkFile.deleteOnExit();
        Framework.trackFile(watermarkFile, this);
        

     // ============================== 
        Blob originalPict = (Blob) inDoc.getPropertyValue("file:content");
        String fileName = originalPict.getFilename();
        String mimeType = originalPict.getMimeType();

        if (targetFileNameSuffix == null || targetFileNameSuffix.isEmpty()) {
            targetFileNameSuffix = "-crop" + top + "-" + left + "-" + width
                    + "x" + height;
        }

     // ============================== Crop (nuxeo-labs crop operation)
        OperationContext ctx = new OperationContext(session);
        ctx.setInput(originalPict);

        OperationChain chain;
        chain = new OperationChain("SkyScannerCropOne_Chain");
        chain.add(CROP_OPERATION).set("top", top).set("left", left).set(
                "width", width).set("height", height).set("pictureWidth",
                pictureWidth).set("pictureHeight", pictureHeight).set(
                "targetFileName", targetFileName).set("targetFileNameSuffix",
                targetFileNameSuffix);
        processedPict = (Blob) automationService.run(ctx, chain);
        processedPict.setMimeType(mimeType);
        // RunCOnverter has updated the file name with our suffix. Now, we stick with it.
        fileName = processedPict.getFilename();


        // ============================== Watermark
        chain = new OperationChain("SkyScannerCropOne_WM");
        ctx.setInput(processedPict);
        // Parameters for Blob.RunConverter
        Properties props = new Properties();
        props = new Properties();
        props.put("targetFileName", fileName);
        props.put("watermarkFilePath", watermarkFile.getAbsolutePath());
        switch(watermarkPosition) {
        case "Top Left":
            props.put("gravity", "NorthWest");
            break;

        case "Bottom Left":
            props.put("gravity", "SouthWest");
            break;

        case "Bottom Right":
            props.put("gravity", "SouthEast");
            break;
        
        default:
            props.put("gravity", "NorthEast");
            break;
        }

        chain.add("Blob.RunConverter").set("converter",
                "skyscannerWatermarkWithImage").set("parameters", props);
        processedPict = (Blob) automationService.run(ctx, chain);
        processedPict.setMimeType(mimeType);
        processedPict.setFilename(fileName);

        
        // ============================== Create the Picture document
        title = processedPict.getFilename();
        DocumentModel result = MiscTools.addToCroppedPictures(session, title,
                processedPict);

        // ============================== WORKAROUND BUG 7.2
        // Force trigger the metadata mapping defined in the Studio project
        // (but ignore in case of problem)
        try {
            chain = new OperationChain("SkyScannerCropOne_DataMapping");
            ctx.setInput(result);
            chain.add(TriggerMetadataMappingOnDocument.ID).set("metadataMappingId", "imageInfo");
            automationService.run(ctx, chain);
            
        } catch(Exception e) {
            log.error("Error getting the 'imageInfo' mapping - should be defined in the Studio project", e);
        }

        // ============================== Now, we create the relation
        // Relations.CreateRelation
        chain = new OperationChain("SkyScannerCropOne_Relation");
        ctx.setInput(result);
        chain.add("Relations.CreateRelation").set("object", inDoc.getId()).set(
                "predicate", "http://purl.org/dc/terms/IsBasedOn");
        result = (DocumentModel) automationService.run(ctx, chain);

        return result;
    }

}
