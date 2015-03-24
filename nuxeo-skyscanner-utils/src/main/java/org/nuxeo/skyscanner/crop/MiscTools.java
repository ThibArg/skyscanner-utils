/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and contributors.
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

import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationChain;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.automation.core.util.Properties;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.runtime.api.Framework;

/**
 * Shared code and misc (avoid copy-paste of code, basically)
 */
public class MiscTools {

    private static DocumentModel croppedPicturesWS = null;

    private static String LOCK = "MiscToolsMutex";

    protected static int counter = 0;

    protected static final String NxLAB_CROP_OPERATION = "ImageCrop";

    /**
     * Get the "Cropped Pictures" workspace, creates it if not found.
     * <p>
     * Assumes this workspace will not be deleted after creation.
     * <p>
     * IMPORTANT: WE DON't HANDLE ACCESS RIGHTS HERE, current user must be able
     * to read this folder. (probably to write since the caller probably wants
     * to add a new Picture)
     * <p>
     * IMPORTANT #2: THIS CODE IS NOT PRODUCTION READY. To put on production, it
     * requires unit testing, optimization about access rights, etc.
     * 
     * @return the "Cropped Pictures" workspace
     *
     */
    public static String CROPPED_PICTURES_WS_NAME = "Cropped Pictures";

    public static DocumentModel getCroppedPictureWorkspace(CoreSession inSession) {

        if (croppedPicturesWS == null) {
            synchronized (LOCK) {
                if (croppedPicturesWS == null) {

                    String nxql = "SELECT * FROM Workspace WHERE dc:title = '"
                            + CROPPED_PICTURES_WS_NAME
                            + "' AND ecm:currentLifeCycleState != 'deleted'";
                    DocumentModelList docs = inSession.query(nxql);
                    if (docs.size() > 0) {
                        croppedPicturesWS = docs.get(0);
                    } else {
                        DocumentModelList domains = inSession.query("SELECT * FROM Domain WHERE ecm:currentLifeCycleState != 'deleted'");
                        // We assume we'll be able to get at least one domain
                        // AGAIN: CHECK ACCESS RIGHTS FOR CURRENT USER
                        DocumentModel currentDomain = domains.get(0);

                        croppedPicturesWS = inSession.createDocumentModel(
                                currentDomain.getPathAsString(),
                                CROPPED_PICTURES_WS_NAME, "Workspace");
                        croppedPicturesWS.setPropertyValue("dc:title",
                                CROPPED_PICTURES_WS_NAME);
                        try {
                            croppedPicturesWS = inSession.createDocument(croppedPicturesWS);
                            croppedPicturesWS = inSession.saveDocument(croppedPicturesWS);
                            inSession.save();
                        } catch (Exception e) {
                            // Can't save at first level of WS (only during
                            // tests actually, the SkyScanner config allows
                            // that)
                            DocumentModelList wsRoot = inSession.query("SELECT * FROM WorkspaceRoot WHERE ecm:currentLifeCycleState != 'deleted'");
                            // We assume we'll be able to get at least one
                            // domain
                            // AGAIN: CHECK ACCESS RIGHTS FOR CURRENT USER
                            DocumentModel currentWSRoot = wsRoot.get(0);
                            croppedPicturesWS = inSession.createDocumentModel(
                                    currentWSRoot.getPathAsString(),
                                    CROPPED_PICTURES_WS_NAME, "Workspace");
                            croppedPicturesWS.setPropertyValue("dc:title",
                                    CROPPED_PICTURES_WS_NAME);
                            croppedPicturesWS = inSession.createDocument(croppedPicturesWS);
                            croppedPicturesWS = inSession.saveDocument(croppedPicturesWS);
                            inSession.save();
                        }
                    }
                }
            }
        }

        return croppedPicturesWS;
    }

    public static DocumentModel addToCroppedPictures(CoreSession inSession,
            String inTitle, Blob inPicture) {

        DocumentModel result = null;

        if (inTitle == null || inTitle.isEmpty()) {
            inTitle = inPicture.getFilename();
        }

        DocumentModel croppedPicturesWS = MiscTools.getCroppedPictureWorkspace(inSession);
        result = inSession.createDocumentModel(
                croppedPicturesWS.getPathAsString(), inTitle, "Picture");
        result.setPropertyValue("dc:title", inTitle);
        result.setPropertyValue("file:content", (Serializable) inPicture);
        result = inSession.createDocument(result);
        result = inSession.saveDocument(result);

        return result;

    }

    public static File createWMFileForDocId(CoreSession inSession, String inId)
            throws IOException {

        File wmFile;

        DocumentModel watermarkDoc = inSession.getDocument(new IdRef(inId));
        Blob watermark = (Blob) watermarkDoc.getPropertyValue("file:content");
        String suffix = watermark.getFilename();
        int pos = suffix.lastIndexOf(".");
        if (pos > 0) {
            suffix = suffix.substring(pos);
        } else {
            suffix = "";
        }
        wmFile = File.createTempFile("TempWatermark-", suffix);
        watermark.transferTo(wmFile);
        wmFile.deleteOnExit();

        return wmFile;
    }

    public static Blob crop(CoreSession session, AutomationService as,
            Blob inPict, long top, long left, long width, long height,
            long pictureWidth, long pictureHeight, String targetFileName,
            String targetFileNameSuffix) throws OperationException {

        Blob result = null;
        OperationChain chain;

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(inPict);

        counter += 1;
        chain = new OperationChain("Chain_Crop_" + counter);

        chain.add(NxLAB_CROP_OPERATION).set("top", top).set("left", left).set(
                "width", width).set("height", height).set("pictureWidth",
                pictureWidth).set("pictureHeight", pictureHeight).set(
                "targetFileName", targetFileName).set("targetFileNameSuffix",
                targetFileNameSuffix);

        result = (Blob) as.run(ctx, chain);

        return result;
    }

    // Convenience wrapper
    public static Blob crop(CoreSession session, AutomationService as,
            Blob inPict, int top, int left, int width, int height,
            int pictureWidth, int pictureHeight, String targetFileName,
            String targetFileNameSuffix) throws OperationException {

        return MiscTools.crop(session, as, inPict, (long) top, (long) left,
                (long) width, (long) height, (long) pictureWidth,
                (long) pictureHeight, targetFileName, targetFileNameSuffix);

    }

    public static Blob watermark(CoreSession session, AutomationService as,
            Blob inPict, String targetFileName, String watermarkFilePath,
            String gravity) throws OperationException {

        Blob result = null;
        OperationChain chain;

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(inPict);

        counter += 1;
        chain = new OperationChain("Chain_Watermark_" + counter);

     // Parameters for Blob.RunConverter
        Properties props = new Properties();
        props = new Properties();
        props.put("targetFileName", targetFileName);
        props.put("watermarkFilePath", watermarkFilePath);
        props.put("gravity", gravity);
        
        chain.add("Blob.RunConverter").set("converter",
                "skyscannerWatermarkWithImage").set("parameters", props);
        
        result = (Blob) as.run(ctx, chain);
        
        return result;
        
    }

}
