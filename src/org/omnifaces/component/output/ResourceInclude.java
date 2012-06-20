/*
 * Copyright 2012 OmniFaces.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.component.output;

import java.io.IOException;

import javax.faces.FacesException;
import javax.faces.component.FacesComponent;
import javax.faces.component.UIComponentBase;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.omnifaces.servlet.BufferedHttpServletResponse;
import org.omnifaces.util.Components;

/**
 * This component is used to catch the output from a JSP/Servlet resource and render it as output
 * to the JSF writer. In effect, this allows you to include both Servlets and JSP pages in e.g. Facelets.
 *
 * @author Arjan Tijms
 * @author Bauke Scholtz
 */
@FacesComponent(ResourceInclude.COMPONENT_TYPE)
public class ResourceInclude extends UIComponentBase {

	// Public constants -----------------------------------------------------------------------------------------------

	/** The component type. */
	public static final String COMPONENT_TYPE = "org.omnifaces.component.output.ResourceInclude";

	/** The standard component family. */
	public static final String COMPONENT_FAMILY = "org.omnifaces.component.output";

	// UIComponent overrides ------------------------------------------------------------------------------------------

	/**
	 * Returns {@link #COMPONENT_FAMILY}.
	 */
	@Override
	public String getFamily() {
		return COMPONENT_FAMILY;
	}

	/**
	 * Returns <code>true</code>.
	 */
	@Override
	public boolean getRendersChildren() {
		return true;
	}

	/**
	 * Create a dispatcher for the resource given by the component's path attribute, catch its output and write it to
	 * the JSF response writer.
	 */
	@Override
	public void encodeBegin(FacesContext context) throws IOException {
		Components.validateHasNoChildren(this);

		ExternalContext externalContext = context.getExternalContext();
		HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
		HttpServletResponse response = (HttpServletResponse) externalContext.getResponse();
		BufferedHttpServletResponse bufferedResponse = new BufferedHttpServletResponse(response);

		try {
			request.getRequestDispatcher((String) getAttributes().get("path")).include(request, bufferedResponse);
		}
		catch (ServletException e) {
			throw new FacesException(e);
		}

		context.getResponseWriter().write(new String(bufferedResponse.getBuffer(), response.getCharacterEncoding()));
	}

}