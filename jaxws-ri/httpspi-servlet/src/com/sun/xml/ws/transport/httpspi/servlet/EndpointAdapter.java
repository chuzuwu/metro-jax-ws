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

package com.sun.xml.ws.transport.httpspi.servlet;

import com.sun.xml.ws.transport.http.servlet.ServletHandler;

import javax.xml.ws.Endpoint;
import javax.xml.ws.spi.http.HttpContext;
import javax.xml.ws.EndpointContext;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @author Jitendra Kotamraju
*/
public final class EndpointAdapter implements ServletHandler {
    private final Endpoint endpoint;
    private final String urlPattern;
    private final EndpointHttpContext httpContext;
    private final EndpointContext appContext;

    public EndpointAdapter(Endpoint endpoint, String urlPattern, EndpointContext appContext) {
        this.endpoint = endpoint;
        this.urlPattern = urlPattern;
        this.appContext = appContext;
        httpContext = new EndpointHttpContext(urlPattern, appContext);
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public HttpContext getContext() {
        return httpContext;
    }

    public void publish() {
        endpoint.publish(httpContext);
    }

    public void dispose() {
        endpoint.stop();
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void handle(ServletContext context, HttpServletRequest request, HttpServletResponse response) throws IOException {
        httpContext.handle(context, request, response);
    }
}
