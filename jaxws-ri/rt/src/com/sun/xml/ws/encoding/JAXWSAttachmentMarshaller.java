/**
 * $Id: JAXWSAttachmentMarshaller.java,v 1.15 2005-09-21 22:20:49 vivekp Exp $
 */

/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the "License").  You may not use this file except
 * in compliance with the License.
 * 
 * You can obtain a copy of the license at
 * https://jwsdp.dev.java.net/CDDLv1.0.html
 * See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL
 * HEADER in each file and include the License file at
 * https://jwsdp.dev.java.net/CDDLv1.0.html  If applicable,
 * add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your
 * own identifying information: Portions Copyright [yyyy]
 * [name of copyright owner]
 */
package com.sun.xml.ws.encoding;

import com.sun.xml.ws.encoding.soap.internal.AttachmentBlock;
import com.sun.xml.ws.handler.HandlerContext;
import com.sun.xml.ws.spi.runtime.MtomCallback;
import com.sun.pept.ept.MessageInfo;

import javax.activation.DataHandler;
import javax.xml.bind.attachment.AttachmentMarshaller;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.WebServiceException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/**
 * @author WS Development Team
 *
 *
 */
public class JAXWSAttachmentMarshaller extends AttachmentMarshaller {

    public JAXWSAttachmentMarshaller(boolean isXOP){
        this.isXOP = isXOP;
    }

    public boolean isXOPPackage() {
        return isXOP;
    }

    /**
     * set the XOP package if the incoming SOAP envelope is a XOP package
     * @param isXOP
     */
    public void setXOPPackage(boolean isXOP){
        this.isXOP = isXOP;
    }

    /*
     * @see AttachmentMarshaller#addMtomAttachment(DataHandler, String, String)
     */
    public String addMtomAttachment(DataHandler data, String elementNamespace, String elementName) {
        if(!isXOP)
            return null;
        String cid = encodeCid(elementNamespace);
        if(cid != null){
            attachments.put("<"+cid+">", new AttachmentBlock("<"+cid+">", data, data.getContentType()));
            addToMessageContext("<"+cid+">", data);
            if(mtomCallback != null)
                mtomCallback.addedMtomAttachment("<"+cid+">", data, elementNamespace, elementName);
            isXopped = true;
            cid = "cid:"+cid;
        }
        return cid;
    }

    /**
     * <p>Consider binary <code>data</code> for optimized binary storage as an attachment.
     * <p/>
     * <p>Since content type is not known, the attachment's MIME content type must be set to "application/octet-stream".</p>
     * <p/>
     * <p/>
     * The <code>elementNamespace</code> and <code>elementLocalName</code>
     * parameters provide the
     * context that contains the binary data. This information could
     * be used by the MIME-based package processor to determine if the
     * binary data should be inlined or optimized as an attachment.
     *
     * @param data             represents the data to be attached. Must be non-null. The actual data region is
     *                         specified by <tt>(data,offset,len)</tt> tuple.
     * @param mimeType         If the data has an associated MIME type known to JAXB, that is passed
     *                         as this parameter. If none is known, "application/octet-stream".
     *                         This parameter may never be null.
     * @param elementNamespace the namespace URI of the element that encloses the base64Binary data.
     *                         Can be empty but never null.
     * @param elementLocalName The local name of the element. Always a non-null valid string.
     * @return content-id URI, cid, to the attachment containing
     *         <code>data</code> or null if data should be inlined.
     * @see #addMtomAttachment(javax.activation.DataHandler, String, String)
     */
    public String addMtomAttachment(byte[] data, int offset, int len, String mimeType, String elementNamespace, String elementLocalName) {
        if(!isXOP)
            return null;

        //TODO: With performance results we need to find out what length would need optimization
        if(len < mtomThresholdValue)
            return null;

        //this will not be needed if saaj exposes api that takes
        //byte[] actualData = getActualData(data, offset, len);

        String cid = encodeCid(elementNamespace);
        if(cid != null){
            attachments.put("<"+cid+">", new AttachmentBlock("<"+cid+">", new ByteArray(data, offset, len), "application/octet-stream"));
            DataHandler dh = new DataHandler(new ByteArrayDataSource(new ByteArrayInputStream(data, offset, len), "application/octet-stream"));
            addToMessageContext("<"+cid+">", dh);
            if(mtomCallback != null)
                mtomCallback.addedMtomAttachment("<"+cid+">", dh, elementNamespace, elementLocalName);
            isXopped = true;
            cid = "cid:"+cid;
        }
        return cid;

    }

    /*
     * @see AttachmentMarshaller#addSwaRefAttachment(DataHandler)
     */
    public String addSwaRefAttachment(DataHandler data) {
        String cid = encodeCid(null);
        if(cid != null){
            attachments.put("<"+cid+">", new AttachmentBlock("<"+cid+">", data, data.getContentType()));
            isXopped = false;
            cid = "cid:"+cid;
        }
        return cid;
    }

    private void addToMessageContext(String cid, DataHandler dh){
        if(hc == null)
            return;
        Map<String, DataHandler> attMap=null;
        Object obj = hc.getMessageContext().get(MessageContext.MESSAGE_ATTACHMENTS);
        if(obj == null){
            attMap = new HashMap<String, DataHandler>();
            hc.getMessageContext().put(MessageContext.MESSAGE_ATTACHMENTS, attMap);
        }else{
            attMap = (Map<String, DataHandler>)obj;
        }
        attMap.put(cid, dh);
    }

    /**
     *
     * @param ns
     * @return
     */
    private String encodeCid(String ns){
        String cid="example.jaxws.sun.com";
        String name = UUID.randomUUID()+"@";
        if(ns != null && (ns.length() > 0)){
            try {
                URI uri = new URI(ns);
                String host = uri.toURL().getHost();
                cid = host;
            } catch (URISyntaxException e) {
                e.printStackTrace();
                return null;
            } catch (MalformedURLException e) {
                try {
                    cid = URLEncoder.encode(ns, "UTF-8");
                } catch (UnsupportedEncodingException e1) {
                    throw new WebServiceException(e);
                }
            }
        }
        return name + cid;
    }

    /**
     * Must be called before marshalling any data.
     * @param attachments Reference to Map from InternalMessage
     */
    public void setAttachments(Map<String, AttachmentBlock> attachments){
        this.attachments = attachments;
        isXopped = false;
    }

    public void setHandlerContaxt(HandlerContext hc){
        this.hc = hc;
    }

    /**
     *
     * @return true if Xopped, false otherwise
     */
    public boolean isXopped() {
        return isXopped;
    }

    public void setMtomThresholdValue(Integer mtomThresholdValue) {
        if((mtomThresholdValue != null) && (mtomThresholdValue >=0))
            this.mtomThresholdValue = mtomThresholdValue;
    }

    public void setMtomCallback(MtomCallback mtomCallback){
        this.mtomCallback = mtomCallback;
    }

    private MtomCallback mtomCallback;
    private boolean isXOP;
    private boolean isXopped;
    private Map<String, AttachmentBlock> attachments;
    private HandlerContext hc;
    private int mtomThresholdValue = 1000;

}
