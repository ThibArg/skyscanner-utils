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
 *     thibaud
 */

package org.nuxeo.skyscanner.crop;

import java.io.File;
import java.io.IOException;

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
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Batch resize/crop/watermark, using nuxeo-labs ImageCrop operation (and other
 * operations from nuxeo)
 * <p>
 * (typically, get the watermark only once, at first call)
 * <p>
 * IMPORTANT: nuxeo-labs MUST BE INSTALLED
 * <p>
 * IMPORTANT #2: NOT READY FOR PRODUCTION. Mus be optimized. Better handling of
 * the watermark, and mainly maybe, run asynchrnosously (start a Worker)
 * 
 */
@Operation(id = SkyScannerBatchResizeWatermarkOp.ID, category = Constants.CAT_CONVERSION, label = "Batch Resize and Watermark", description = "Resize/Crop/Watermark the documents. IMPORTANT: Requests nuxeo-labs to be installed")
public class SkyScannerBatchResizeWatermarkOp {

    public static final String ID = "SkyScannerBatchResizeWatermarkOp";

    private static final String CROP_OPERATION = "ImageCrop";

    @Context
    protected CoreSession session;

    @Context
    protected AutomationService automationService;

    @Param(name = "size", required = true)
    protected String size = "";

    @Param(name = "watermarkDocId", required = false)
    protected String watermarkDocId = "";

    @OperationMethod
    public DocumentModelList run(DocumentModelList inDocs)
            throws OperationException, IOException {

        int newWidth = 0;
        int newHeight = 0;

        // Get the final dimensions
        if (size.equals("1200x1200")) {
            newWidth = 1200;
            newHeight = 1200;
        } else if (size.equals("1200x627")) {
            newWidth = 1200;
            newHeight = 627;
        } else if (size.equals("468x283")) {
            newWidth = 468;
            newHeight = 283;
        }

        // Get the watermark
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
        File watermarkFile = File.createTempFile("SkyScannerBatch-", suffix);
        watermark.transferTo(watermarkFile);
        watermarkFile.deleteOnExit();
        Framework.trackFile(watermarkFile, this);

        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

        // Process
        int count = 0;
        for (DocumentModel doc : inDocs) {

            Blob processedPict = null;

            int TARGET_SIZE = 1200;
            int origWidth = ((Long) doc.getPropertyValue("picture:info/width")).intValue();
            int origHeight = ((Long) doc.getPropertyValue("picture:info/height")).intValue();

            int widthFor1200 = 0;
            int heightFor1200 = 0;
            double ratio = 1.0;
            int diff = 0;
            int cropTop = 0;
            int cropLeft = 0;

            // ========================================
            if (newHeight == 1200) {
                // ========================================

                if (origWidth <= origHeight) {
                    widthFor1200 = TARGET_SIZE;
                    diff = origWidth - TARGET_SIZE;
                    ratio = 1 - ((double) diff / (double) origWidth);
                    heightFor1200 = (int) ((double) origHeight * ratio);
                } else {
                    heightFor1200 = TARGET_SIZE;
                    diff = origHeight - TARGET_SIZE;
                    ratio = 1 - ((double) diff / (double) origHeight);
                    widthFor1200 = (int) ((double) origWidth * ratio);
                }

                // Crop must center the picture, at 0 for top.
                if (widthFor1200 > TARGET_SIZE) {
                    cropLeft = (widthFor1200 - TARGET_SIZE) / 2;
                }
                if (heightFor1200 > TARGET_SIZE) {
                    cropTop = (heightFor1200 - TARGET_SIZE) / 2;
                } else if (newHeight < heightFor1200) {
                    cropTop = (heightFor1200 - newHeight) / 2;
                }
                // ========================================
            } else {
                // ========================================
                // Assume 627
                // Resize with target width of 1200
                widthFor1200 = TARGET_SIZE;
                diff = origWidth - TARGET_SIZE;
                ratio = 1 - ((double) diff / (double) origWidth);
                heightFor1200 = (int) ((double) origHeight * ratio);
                cropTop = (heightFor1200 - newHeight) / 2;
            }

            Blob originalPict = (Blob) doc.getPropertyValue("file:content");
            String fileName = originalPict.getFilename();
            String mimeType = originalPict.getMimeType();

            pos = fileName.lastIndexOf(".");
            fileName = fileName.substring(0, pos) + "-" + newWidth + "x"
                    + newHeight + fileName.substring(pos);

            OperationContext ctx = new OperationContext(session);
            OperationChain chain;

            // ==================================================
            // Resize to widthFor1200 x heightFor1200
            // ==================================================
            chain = new OperationChain("SkyScannerBatch_Resize");
            ctx.setInput(originalPict);
            // Parameters for Blob.RunConverter
            Properties props = new Properties();
            props.put("targetFileName", fileName);
            props.put("width", "" + widthFor1200);
            props.put("height", "" + heightFor1200);

            chain.add("Blob.RunConverter").set("converter",
                    "skyscannerResizePicture").set("parameters", props);
            processedPict = (Blob) automationService.run(ctx, chain);
            processedPict.setMimeType(mimeType);

            // ==================================================
            // Crop
            // ==================================================
            processedPict = MiscTools.crop(session, automationService,
                    originalPict, cropTop, cropLeft, newWidth, newHeight, 0,
                    0, fileName, "");
            // Make sure we have our values
            processedPict.setMimeType(mimeType);

            // ==================================================
            // Watermark
            // ==================================================
            processedPict = MiscTools.watermark(session, automationService,
                    processedPict, fileName, watermarkFile.getAbsolutePath(),
                    "NorthEast");
            processedPict.setMimeType(mimeType);

            // ==================================================
            // Create the cropped Picture Document
            // ==================================================
            DocumentModel newPictureDoc = MiscTools.addToCroppedPictures(
                    session, null, processedPict);
            count += 1;
            if ((count % 10) == 0) {
                session.save();
                TransactionHelper.commitOrRollbackTransaction();
                TransactionHelper.startTransaction();
            }

            // ==================================================
            // Create the relation
            // ==================================================
            chain = new OperationChain("SkyScannerBatch_Relation");
            ctx.setInput(newPictureDoc);
            chain.add("Relations.CreateRelation").set("object", doc.getId()).set(
                    "predicate", "http://purl.org/dc/terms/IsBasedOn");
            newPictureDoc = (DocumentModel) automationService.run(ctx, chain);
        }

        session.save();
        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

        return inDocs;

    }

}
