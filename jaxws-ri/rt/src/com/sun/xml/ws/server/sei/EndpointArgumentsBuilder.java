/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.xml.ws.server.sei;

import com.sun.xml.bind.api.AccessorException;
import com.sun.xml.bind.api.Bridge;
import com.sun.xml.bind.api.CompositeStructure;
import com.sun.xml.bind.api.RawAccessor;
import com.sun.xml.ws.api.SOAPVersion;
import com.sun.xml.ws.api.message.Attachment;
import com.sun.xml.ws.api.message.AttachmentSet;
import com.sun.xml.ws.api.message.Message;
import com.sun.xml.ws.api.model.ParameterBinding;
import com.sun.xml.ws.api.streaming.XMLStreamReaderFactory;
import com.sun.xml.ws.message.AttachmentUnmarshallerImpl;
import com.sun.xml.ws.model.ParameterImpl;
import com.sun.xml.ws.model.WrapperParameter;
import com.sun.xml.ws.resources.ServerMessages;
import com.sun.xml.ws.streaming.XMLStreamReaderUtil;

import javax.activation.DataHandler;
import javax.imageio.ImageIO;
import javax.jws.WebParam.Mode;
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPFault;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.ws.Holder;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.soap.SOAPFaultException;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads a request {@link Message}, disassembles it, and moves obtained Java values
 * to the expected places.
 *
 * @author Jitendra Kotamraju
 */
abstract class EndpointArgumentsBuilder {
    /**
     * Reads a request {@link Message}, disassembles it, and moves obtained
     * Java values to the expected places.
     *
     * @param request
     *      The request {@link Message} to be de-composed.
     * @param args
     *      The Java arguments given to the SEI method invocation.
     *      Some parts of the reply message may be set to {@link Holder}s in the arguments.
     * @throws JAXBException
     *      if there's an error during unmarshalling the request message.
     * @throws XMLStreamException
     *      if there's an error during unmarshalling the request message.
     */
    abstract void readRequest(Message request, Object[] args)
        throws JAXBException, XMLStreamException;

    static final class None extends EndpointArgumentsBuilder {
        private None(){
        }
        public void readRequest(Message msg, Object[] args) {
            msg.consume();
        }
    }

    /**
     * The singleton instance that produces null return value.
     * Used for operations that doesn't have any output.
     */
    public static EndpointArgumentsBuilder NONE = new None();

    /**
     * Returns the 'uninitialized' value for the given type.
     *
     * <p>
     * For primitive types, it's '0', and for reference types, it's null.
     */
    public static Object getVMUninitializedValue(Type type) {
        // if this map returns null, that means the 'type' is a reference type,
        // in which case 'null' is the correct null value, so this code is correct.
        return primitiveUninitializedValues.get(type);
    }

    private static final Map<Class,Object> primitiveUninitializedValues = new HashMap<Class, Object>();

    static {
        Map<Class, Object> m = primitiveUninitializedValues;
        m.put(int.class,(int)0);
        m.put(char.class,(char)0);
        m.put(byte.class,(byte)0);
        m.put(short.class,(short)0);
        m.put(long.class,(long)0);
        m.put(float.class,(float)0);
        m.put(double.class,(double)0);
    }

    /**
     * {@link EndpointArgumentsBuilder} that sets the VM uninitialized value to the type.
     */
    static final class NullSetter extends EndpointArgumentsBuilder {
        private final EndpointValueSetter setter;
        private final Object nullValue;

        public NullSetter(EndpointValueSetter setter, Object nullValue){
            assert setter!=null;
            this.nullValue = nullValue;
            this.setter = setter;
        }
        public void readRequest(Message msg, Object[] args) {
            setter.put(nullValue, args);
        }
    }

    /**
     * {@link EndpointArgumentsBuilder} that is a composition of multiple
     * {@link EndpointArgumentsBuilder}s.
     *
     * <p>
     * Sometimes we need to look at multiple parts of the reply message
     * (say, two header params, one body param, and three attachments, etc.)
     * and that's when this object is used to combine multiple {@link EndpointArgumentsBuilder}s
     * (that each responsible for handling one part).
     *
     * <p>
     * The model guarantees that only at most one {@link EndpointArgumentsBuilder} will
     * return a value as a return value (and everything else has to go to
     * {@link Holder}s.)
     */
    static final class Composite extends EndpointArgumentsBuilder {
        private final EndpointArgumentsBuilder[] builders;

        public Composite(EndpointArgumentsBuilder... builders) {
            this.builders = builders;
        }

        public Composite(Collection<? extends EndpointArgumentsBuilder> builders) {
            this(builders.toArray(new EndpointArgumentsBuilder[builders.size()]));
        }

        public void readRequest(Message msg, Object[] args) throws JAXBException, XMLStreamException {
            for (EndpointArgumentsBuilder builder : builders) {
                builder.readRequest(msg,args);
            }
        }
    }

    
    /**
     * Reads an Attachment into a Java parameter.
     */
    static abstract class AttachmentBuilder extends EndpointArgumentsBuilder {
        protected final EndpointValueSetter setter;
        protected final ParameterImpl param;
        protected final String pname;
        protected final String pname1;
            
        AttachmentBuilder(ParameterImpl param, EndpointValueSetter setter) {
            this.setter = setter;
            this.param = param;
            this.pname = param.getPartName();
            this.pname1 = "<"+pname;
        }

        /**
         * Creates an AttachmentBuilder based on the parameter type
         *
         * @param param
         *      runtime Parameter that abstracts the annotated java parameter
         * @param setter
         *      specifies how the obtained value is set into the argument. Takes
         *      care of Holder arguments.
         */
        public static EndpointArgumentsBuilder createAttachmentBuilder(ParameterImpl param, EndpointValueSetter setter) {
            Class type = (Class)param.getTypeReference().type;
            if (DataHandler.class.isAssignableFrom(type)) {
                return new DataHandlerBuilder(param, setter);
            } else if (byte[].class==type) {
                return new ByteArrayBuilder(param, setter);
            } else if(Source.class.isAssignableFrom(type)) {
                return new SourceBuilder(param, setter);
            } else if(Image.class.isAssignableFrom(type)) {
                return new ImageBuilder(param, setter);
            } else if(InputStream.class==type) {
                return new InputStreamBuilder(param, setter);
            } else if(isXMLMimeType(param.getBinding().getMimeType())) {
                return new JAXBBuilder(param, setter);
            } else {
                throw new UnsupportedOperationException("Attachment is not mapped");
            }
        }
        
        public void readRequest(Message msg, Object[] args) throws JAXBException, XMLStreamException {
            // TODO not to loop
            for (Attachment att : msg.getAttachments()) {
                String part = getWSDLPartName(att);
                if (part == null) {
                    continue;
                }
                if(part.equals(pname) || part.equals(pname1)){
                    mapAttachment(att, args);
                    break;
                }
            }
        }
        
        abstract void mapAttachment(Attachment att, Object[] args) throws JAXBException;
    }
        
    private static final class DataHandlerBuilder extends AttachmentBuilder {
        DataHandlerBuilder(ParameterImpl param, EndpointValueSetter setter) {
            super(param, setter);
        }
        
        void mapAttachment(Attachment att, Object[] args) {
            setter.put(att.asDataHandler(), args);
        }
    }
        
    private static final class ByteArrayBuilder extends AttachmentBuilder {
        ByteArrayBuilder(ParameterImpl param, EndpointValueSetter setter) {
            super(param, setter);
        }
        
        void mapAttachment(Attachment att, Object[] args) {
            setter.put(att.asByteArray(), args);
        }
    }
        
    private static final class SourceBuilder extends AttachmentBuilder {
        SourceBuilder(ParameterImpl param, EndpointValueSetter setter) {
            super(param, setter);
        }
        
        void mapAttachment(Attachment att, Object[] args) {
            setter.put(att.asSource(), args);
        }
    }
        
    private static final class ImageBuilder extends AttachmentBuilder {
        ImageBuilder(ParameterImpl param, EndpointValueSetter setter) {
            super(param, setter);
        }
        
        void mapAttachment(Attachment att, Object[] args) {
            Image image;
            try {
                image = ImageIO.read(att.asInputStream());
            } catch(IOException ioe) {
                throw new WebServiceException(ioe);
            }
            setter.put(image, args);
        }
    }
        
    private static final class InputStreamBuilder extends AttachmentBuilder {
        InputStreamBuilder(ParameterImpl param, EndpointValueSetter setter) {
            super(param, setter);
        }
        
        void mapAttachment(Attachment att, Object[] args) {
            setter.put(att.asInputStream(), args);
        }
    }
        
    private static final class JAXBBuilder extends AttachmentBuilder {
        JAXBBuilder(ParameterImpl param, EndpointValueSetter setter) {
            super(param, setter);
        }
        
        void mapAttachment(Attachment att, Object[] args) throws JAXBException {
            Object obj = param.getBridge().unmarshal(att.asInputStream());
            setter.put(obj, args);
        }
    }

    
    /**
     * Gets the WSDL part name of this attachment.
     *
     * <p>
     * According to WSI AP 1.0
     * <PRE>
     * 3.8 Value-space of Content-Id Header
     *   Definition: content-id part encoding
     *   The "content-id part encoding" consists of the concatenation of:
     * The value of the name attribute of the wsdl:part element referenced by the mime:content, in which characters disallowed in content-id headers (non-ASCII characters as represented by code points above 0x7F) are escaped as follows:
     *     o Each disallowed character is converted to UTF-8 as one or more bytes.
     *     o Any bytes corresponding to a disallowed character are escaped with the URI escaping mechanism (that is, converted to %HH, where HH is the hexadecimal notation of the byte value).
     *     o The original character is replaced by the resulting character sequence.
     * The character '=' (0x3D).
     * A globally unique value such as a UUID.
     * The character '@' (0x40).
     * A valid domain name under the authority of the entity constructing the message.
     * </PRE>
     *
     * So a wsdl:part fooPart will be encoded as:
     *      <fooPart=somereallybignumberlikeauuid@example.com>
     *
     * @return null
     *      if the parsing fails.
     */
    public static final String getWSDLPartName(com.sun.xml.ws.api.message.Attachment att){
        String cId = att.getContentId();

        int index = cId.lastIndexOf('@', cId.length());
        if(index == -1){
            return null;
        }
        String localPart = cId.substring(0, index);
        index = localPart.lastIndexOf('=', localPart.length());
        if(index == -1){
            return null;
        }
        try {
            return java.net.URLDecoder.decode(localPart.substring(0, index), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new WebServiceException(e);
        }
    }

    
    

    /**
     * Reads a header into a JAXB object.
     */
    static final class Header extends EndpointArgumentsBuilder {
        private final Bridge<?> bridge;
        private final EndpointValueSetter setter;
        private final QName headerName;
        private final SOAPVersion soapVersion;

        /**
         * @param name
         *      The name of the header element.
         * @param bridge
         *      specifies how to unmarshal a header into a JAXB object.
         * @param setter
         *      specifies how the obtained value is returned to the client.
         */
        public Header(SOAPVersion soapVersion, QName name, Bridge<?> bridge, EndpointValueSetter setter) {
            this.soapVersion = soapVersion;
            this.headerName = name;
            this.bridge = bridge;
            this.setter = setter;
        }

        public Header(SOAPVersion soapVersion, ParameterImpl param, EndpointValueSetter setter) {
            this(
                soapVersion,
                param.getTypeReference().tagName,
                param.getBridge(),
                setter);
            assert param.getOutBinding()== ParameterBinding.HEADER;
        }

        private SOAPFaultException createDuplicateHeaderException() {
            try {
                SOAPFault fault = soapVersion.saajSoapFactory.createFault(
                        ServerMessages.DUPLICATE_PORT_KNOWN_HEADER(headerName), soapVersion.faultCodeClient);
                return new SOAPFaultException(fault);
            } catch(SOAPException e) {
                throw new WebServiceException(e);
            }
        }

        public void readRequest(Message msg, Object[] args) throws JAXBException {
            com.sun.xml.ws.api.message.Header header = null;
            Iterator<com.sun.xml.ws.api.message.Header> it =
                msg.getHeaders().getHeaders(headerName,true);
            if (it.hasNext()) {
                header = it.next();
                if (it.hasNext()) {
                    throw createDuplicateHeaderException();
                }
            }

            if(header!=null) {
                setter.put( header.readAsJAXB(bridge), args );
            } else {
                // header not found.
            }
        }
    }

    /**
     * Reads the whole payload into a single JAXB bean.
     */
    static final class Body extends EndpointArgumentsBuilder {
        private final Bridge<?> bridge;
        private final EndpointValueSetter setter;

        /**
         * @param bridge
         *      specifies how to unmarshal the payload into a JAXB object.
         * @param setter
         *      specifies how the obtained value is returned to the client.
         */
        public Body(Bridge<?> bridge, EndpointValueSetter setter) {
            this.bridge = bridge;
            this.setter = setter;
        }

        public void readRequest(Message msg, Object[] args) throws JAXBException {
            setter.put( msg.readPayloadAsJAXB(bridge), args );
        }
    }

    /**
     * Treats a payload as multiple parts wrapped into one element,
     * and processes all such wrapped parts.
     */
    static final class DocLit extends EndpointArgumentsBuilder {
        /**
         * {@link PartBuilder} keyed by the element name (inside the wrapper element.)
         */
        private final PartBuilder[] parts;

        private final Bridge wrapper;

        public DocLit(WrapperParameter wp, Mode skipMode) {
            wrapper = wp.getBridge();
            Class wrapperType = (Class) wrapper.getTypeReference().type;

            List<PartBuilder> parts = new ArrayList<PartBuilder>();

            List<ParameterImpl> children = wp.getWrapperChildren();
            for (ParameterImpl p : children) {
                if (p.getMode() == skipMode) {
                    continue;
                }
                /*
                if(p.isIN())
                    continue;
                 */
                QName name = p.getName();
                try {
                    parts.add( new PartBuilder(
                        wp.getOwner().getJAXBContext().getElementPropertyAccessor(
                            wrapperType,
                            name.getNamespaceURI(),
                            p.getName().getLocalPart()),
                        EndpointValueSetter.get(p)
                    ));
                    // wrapper parameter itself always bind to body, and
                    // so do all its children
                    assert p.getBinding()== ParameterBinding.BODY;
                } catch (JAXBException e) {
                    throw new WebServiceException(  // TODO: i18n
                        wrapperType+" do not have a property of the name "+name,e);
                }
            }

            this.parts = parts.toArray(new PartBuilder[parts.size()]);
        }

        public void readRequest(Message msg, Object[] args) throws JAXBException, XMLStreamException {

            if (parts.length>0) {
                XMLStreamReader reader = msg.readPayload();
                Object wrapperBean = wrapper.unmarshal(reader, (msg.getAttachments() != null) ?
                        new AttachmentUnmarshallerImpl(msg.getAttachments()): null);

                try {
                    for (PartBuilder part : parts) {
                        part.readRequest(args,wrapperBean);
                    }
                } catch (AccessorException e) {
                    // this can happen when the set method throw a checked exception or something like that
                    throw new WebServiceException(e);    // TODO:i18n
                }

                // we are done with the body
                reader.close();
                XMLStreamReaderFactory.recycle(reader);
            } else {
                msg.consume();
            }
        }

        /**
         * Unmarshals each wrapped part into a JAXB object and moves it
         * to the expected place.
         */
        static final class PartBuilder {
            private final RawAccessor accessor;
            private final EndpointValueSetter setter;

            /**
             * @param accessor
             *      specifies which portion of the wrapper bean to obtain the value from.
             * @param setter
             *      specifies how the obtained value is returned to the client.
             */
            public PartBuilder(RawAccessor accessor, EndpointValueSetter setter) {
                this.accessor = accessor;
                this.setter = setter;
                assert accessor!=null && setter!=null;
            }

            final void readRequest( Object[] args, Object wrapperBean ) throws AccessorException {
                Object obj = accessor.get(wrapperBean);
                setter.put(obj,args);
            }


        }
    }

    /**
     * Treats a payload as multiple parts wrapped into one element,
     * and processes all such wrapped parts.
     */
    static final class RpcLit extends EndpointArgumentsBuilder {
        /**
         * {@link PartBuilder} keyed by the element name (inside the wrapper element.)
         */
        private final Map<QName,PartBuilder> parts = new HashMap<QName,PartBuilder>();

        private QName wrapperName;

        public RpcLit(WrapperParameter wp) {
            assert wp.getTypeReference().type== CompositeStructure.class;

            wrapperName = wp.getName();
            List<ParameterImpl> children = wp.getWrapperChildren();
            for (ParameterImpl p : children) {
                parts.put( p.getName(), new PartBuilder(
                    p.getBridge(), EndpointValueSetter.get(p)
                ));
                // wrapper parameter itself always bind to body, and
                // so do all its children
                assert p.getBinding()== ParameterBinding.BODY;
            }
        }

        public void readRequest(Message msg, Object[] args) throws JAXBException, XMLStreamException {
            XMLStreamReader reader = msg.readPayload();
            if (!reader.getName().equals(wrapperName))
                throw new WebServiceException( // TODO: i18n
                    "Unexpected request element "+reader.getName()+" expected: "+wrapperName);
            reader.nextTag();

            while(reader.getEventType()==XMLStreamReader.START_ELEMENT) {
                // TODO: QName has a performance issue
                PartBuilder part = parts.get(reader.getName());
                if(part==null) {
                    // no corresponding part found. ignore
                    XMLStreamReaderUtil.skipElement(reader);
                    reader.nextTag();
                } else {
                    part.readRequest(args,reader, msg.getAttachments());
                }
            }

            // we are done with the body
            reader.close();
            XMLStreamReaderFactory.recycle(reader);
        }

        /**
         * Unmarshals each wrapped part into a JAXB object and moves it
         * to the expected place.
         */
        static final class PartBuilder {
            private final Bridge bridge;
            private final EndpointValueSetter setter;

            /**
             * @param bridge
             *      specifies how the part is unmarshalled.
             * @param setter
             *      specifies how the obtained value is returned to the endpoint.
             */
            public PartBuilder(Bridge bridge, EndpointValueSetter setter) {
                this.bridge = bridge;
                this.setter = setter;
            }

            final void readRequest( Object[] args, XMLStreamReader r, AttachmentSet att) throws JAXBException {
                Object obj = bridge.unmarshal(r, (att != null)?new AttachmentUnmarshallerImpl(att):null);
                setter.put(obj,args);
            }
        }
    }
    
    private static boolean isXMLMimeType(String mimeType){
        return mimeType.equals("text/xml") || mimeType.equals("application/xml");
    }
}
