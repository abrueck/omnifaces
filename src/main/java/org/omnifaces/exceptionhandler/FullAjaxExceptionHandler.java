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
package org.omnifaces.exceptionhandler;

import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION;
import static javax.servlet.RequestDispatcher.ERROR_EXCEPTION_TYPE;
import static javax.servlet.RequestDispatcher.ERROR_MESSAGE;
import static javax.servlet.RequestDispatcher.ERROR_REQUEST_URI;
import static javax.servlet.RequestDispatcher.ERROR_STATUS_CODE;
import static org.omnifaces.util.Exceptions.unwrap;
import static org.omnifaces.util.Faces.getContext;
import static org.omnifaces.util.FacesLocal.normalizeViewId;

import java.io.IOException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.el.ELException;
import javax.faces.FacesException;
import javax.faces.application.ViewHandler;
import javax.faces.component.UIViewRoot;
import javax.faces.context.ExceptionHandler;
import javax.faces.context.ExceptionHandlerFactory;
import javax.faces.context.ExceptionHandlerWrapper;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.faces.event.AbortProcessingException;
import javax.faces.event.ExceptionQueuedEvent;
import javax.faces.event.PhaseId;
import javax.faces.event.PreRenderViewEvent;
import javax.faces.view.ViewDeclarationLanguage;
import javax.faces.webapp.FacesServlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.omnifaces.config.WebXml;
import org.omnifaces.context.OmniPartialViewContext;
import org.omnifaces.filter.FacesExceptionFilter;
import org.omnifaces.util.Exceptions;
import org.omnifaces.util.Hacks;

/**
 * <p>
 * The {@link FullAjaxExceptionHandler} will transparently handle exceptions during ajax requests exactly the same way
 * as exceptions during synchronous (non-ajax) requests.
 * <p>
 * By default, when an exception occurs during a JSF ajax request, the enduser would not get any form of feedback if the
 * action was successfully performed or not. In Mojarra, only when the project stage is set to <code>Development</code>,
 * the enduser would see a bare JavaScript alert with only the exception type and message. It would make sense if
 * exceptions during ajax requests are handled the same way as exceptions during synchronous requests, which is
 * utilizing the standard Servlet API <code>&lt;error-page&gt;</code> mechanisms in <code>web.xml</code>.
 *
 * <h3>Installation</h3>
 * <p>
 * This handler must be registered by a factory as follows in <code>faces-config.xml</code> in order to get it to run:
 * <pre>
 * &lt;factory&gt;
 *     &lt;exception-handler-factory&gt;org.omnifaces.exceptionhandler.FullAjaxExceptionHandlerFactory&lt;/exception-handler-factory&gt;
 * &lt;/factory&gt;
 * </pre>
 *
 * <h3>Error pages</h3>
 * <p>
 * This exception handler will parse the <code>web.xml</code> and <code>web-fragment.xml</code> files to find the error
 * page locations of the HTTP error code <code>500</code> and all declared specific exception types. Those locations
 * need to point to Facelets files (JSP is not supported) and the URL must match the {@link FacesServlet} mapping (just
 * mapping it on <code>*.xhtml</code> should eliminate confusion about virtual URLs).
 * <p>
 * The location of the HTTP error code <code>500</code> or the exception type <code>java.lang.Throwable</code> is
 * <b>required</b> in order to get the {@link FullAjaxExceptionHandler} to work, because there's then at least a fall
 * back error page whenever there's no match with any of the declared specific exceptions. So, you must at least have
 * either
 * <pre>
 * &lt;error-page&gt;
 *     &lt;error-code&gt;500&lt;/error-code&gt;
 *     &lt;location&gt;/WEB-INF/errorpages/500.xhtml&lt;/location&gt;
 * &lt;/error-page&gt;
 * </pre>
 * <p>or
 * <pre>
 * &lt;error-page&gt;
 *     &lt;exception-type&gt;java.lang.Throwable&lt;/exception-type&gt;
 *     &lt;location&gt;/WEB-INF/errorpages/500.xhtml&lt;/location&gt;
 * &lt;/error-page&gt;
 * </pre>
 * <p>
 * You can have both, but the <code>java.lang.Throwable</code> one will always get precedence over the <code>500</code>
 * one, as per the Servlet API specification, so the <code>500</code> one would be basically superfluous.
 * <p>
 * The exception detail is available in the request scope by the standard Servlet error request attributes like as in a
 * normal synchronous error page response. You could for example show them in the error page as follows:
 * <pre>
 * &lt;ul&gt;
 *     &lt;li&gt;Date/time: #{of:formatDate(now, 'yyyy-MM-dd HH:mm:ss')}&lt;/li&gt;
 *     &lt;li&gt;User agent: #{header['user-agent']}&lt;/li&gt;
 *     &lt;li&gt;User IP: #{request.remoteAddr}&lt;/li&gt;
 *     &lt;li&gt;Request URI: #{requestScope['javax.servlet.error.request_uri']}&lt;/li&gt;
 *     &lt;li&gt;Ajax request: #{facesContext.partialViewContext.ajaxRequest ? 'Yes' : 'No'}&lt;/li&gt;
 *     &lt;li&gt;Status code: #{requestScope['javax.servlet.error.status_code']}&lt;/li&gt;
 *     &lt;li&gt;Exception type: #{requestScope['javax.servlet.error.exception_type']}&lt;/li&gt;
 *     &lt;li&gt;Exception message: #{requestScope['javax.servlet.error.message']}&lt;/li&gt;
 *     &lt;li&gt;Stack trace:
 *         &lt;pre&gt;#{of:printStackTrace(requestScope['javax.servlet.error.exception'])}&lt;/pre&gt;
 *     &lt;/li&gt;
 * &lt;/ul&gt;
 * </pre>
 * <p>
 * Exceptions during render response can only be handled when the <code>javax.faces.FACELETS_BUFFER_SIZE</code> is
 * large enough so that the so far rendered response until the occurrence of the exception fits in there and can
 * therefore safely be resetted. When rendering of the error page itself fails due to a bug in the error page itself
 * and the response can still be resetted, then a hardcoded message will be returned informing the developer about the
 * double mistake.
 *
 * <h3>Error in error page itself</h3>
 * <p>
 * When the rendering of the error page itself failed due to a bug in the error page itself, then the
 * {@link FullAjaxExceptionHandler} will reset the response and display a hardcoded error message in "plain text".
 *
 * <h3>Normal requests</h3>
 * <p>
 * Note that the {@link FullAjaxExceptionHandler} does not deal with normal (non-ajax) requests at all. To properly
 * handle JSF and EL exceptions on normal requests as well, you need an additional {@link FacesExceptionFilter}. This
 * will extract the root cause from a wrapped {@link FacesException} and {@link ELException} before delegating the
 * {@link ServletException} further to the container (the container will namely use the first root cause of
 * {@link ServletException} to match an error page by exception in web.xml).
 *
 * <h3>Customizing <code>FullAjaxExceptionHandler</code></h3>
 * <p>
 * If more fine grained control is desired for determining the root cause of the caught exception, or whether it should
 * be handled, or determining the error page, or logging the exception, then the developer can opt to extend this
 * {@link FullAjaxExceptionHandler} and override one or more of the following protected methods:
 * <ul>
 * <li>{@link #findExceptionRootCause(FacesContext, Throwable)}
 * <li>{@link #shouldHandleExceptionRootCause(FacesContext, Throwable)}
 * <li>{@link #findErrorPageLocation(FacesContext, Throwable)}
 * <li>{@link #logException(FacesContext, Throwable, String, String, Object...)}
 * </ul>
 * <p>
 * Don't forget to create a custom {@link ExceptionHandlerFactory} for it as well, so that it could be registered
 * in <code>faces-config.xml</code>. This does not necessarily need to extend from
 * {@link FullAjaxExceptionHandlerFactory}.
 *
 * @author Bauke Scholtz
 * @see FullAjaxExceptionHandlerFactory
 */
public class FullAjaxExceptionHandler extends ExceptionHandlerWrapper {

	// Private constants ----------------------------------------------------------------------------------------------

	private static final Logger logger = Logger.getLogger(FullAjaxExceptionHandler.class.getName());

	private static final String ERROR_DEFAULT_LOCATION_MISSING =
		"Either HTTP 500 or java.lang.Throwable error page is required in web.xml or web-fragment.xml."
			+ " Neither was found.";
	private static final String LOG_EXCEPTION_HANDLED =
		"FullAjaxExceptionHandler: An exception occurred during processing JSF ajax request."
			+ " Error page '%s' will be shown.";
	private static final String LOG_RENDER_EXCEPTION_HANDLED =
		"FullAjaxExceptionHandler: An exception occurred during rendering JSF ajax response."
			+ " Error page '%s' will be shown.";
	private static final String LOG_RENDER_EXCEPTION_UNHANDLED =
		"FullAjaxExceptionHandler: An exception occurred during rendering JSF ajax response."
			+ " Error page '%s' CANNOT be shown as response is already committed."
			+ " Consider increasing 'javax.faces.FACELETS_BUFFER_SIZE' if it really needs to be handled.";
	private static final String LOG_ERROR_PAGE_ERROR =
		"FullAjaxExceptionHandler: Well, another exception occurred during rendering error page '%s'."
			+ " Trying to render a hardcoded error page now.";
	private static final String ERROR_PAGE_ERROR =
		"<?xml version='1.0' encoding='UTF-8'?><partial-response id='error'><changes><update id='javax.faces.ViewRoot'>"
			+ "<![CDATA[<html lang='en'><head><title>Error in error</title></head><body><section><h2>Oops!</h2>"
			+ "<p>A problem occurred during processing the ajax request. Subsequently, another problem occurred during"
			+ " processing the error page which should inform you about that problem.</p><p>If you are the responsible"
			+ " web developer, it's time to read the server logs about the bug in the error page itself.</p></section>"
			+ "</body></html>]]></update></changes></partial-response>";

	// Variables ------------------------------------------------------------------------------------------------------

	private ExceptionHandler wrapped;

	// Constructors ---------------------------------------------------------------------------------------------------

	/**
	 * Construct a new ajax exception handler around the given wrapped exception handler.
	 * @param wrapped The wrapped exception handler.
	 */
	public FullAjaxExceptionHandler(ExceptionHandler wrapped) {
		this.wrapped = wrapped;
	}

	// Actions --------------------------------------------------------------------------------------------------------

	/**
	 * Handle the ajax exception as follows, only and only if the current request is an ajax request with an uncommitted
	 * response and there is at least one unhandled exception:
	 * <ul>
	 *   <li>Find the root cause of the exception by {@link #findExceptionRootCause(FacesContext, Throwable)}.
	 *   <li>Find the error page location based on root cause by {@link #findErrorPageLocation(FacesContext, Throwable)}.
	 *   <li>Set the standard servlet error request attributes.
	 *   <li>Force JSF to render the full error page in its entirety.
	 * </ul>
	 * Any remaining unhandled exceptions will be swallowed. Only the first one is relevant.
	 */
	@Override
	public void handle() throws FacesException {
		handleAjaxException(getContext());
		wrapped.handle();
	}

	private void handleAjaxException(FacesContext context) {
		if (context == null || !context.getPartialViewContext().isAjaxRequest()) {
			return; // Not an ajax request.
		}

		Iterator<ExceptionQueuedEvent> unhandledExceptionQueuedEvents = getUnhandledExceptionQueuedEvents().iterator();

		if (!unhandledExceptionQueuedEvents.hasNext()) {
			return; // There's no unhandled exception.
		}

		Throwable exception = unhandledExceptionQueuedEvents.next().getContext().getException();

		if (exception instanceof AbortProcessingException) {
			return; // Let JSF handle it itself.
		}

		exception = findExceptionRootCause(context, exception);

		if (!shouldHandleExceptionRootCause(context, exception)) {
			return; // A subclass apparently want to do it differently.
		}

		String errorPageLocation = findErrorPageLocation(context, exception);

		if (errorPageLocation == null) {
			// If there's no default error page location, then it's end of story.
			throw new IllegalArgumentException(ERROR_DEFAULT_LOCATION_MISSING);
		}

		unhandledExceptionQueuedEvents.remove();
		ExternalContext externalContext = context.getExternalContext();

		// Check if we're inside render response and if the response is committed.
		if (context.getCurrentPhaseId() != PhaseId.RENDER_RESPONSE) {
			logException(context, exception, errorPageLocation, LOG_EXCEPTION_HANDLED);
		}
		else if (!externalContext.isResponseCommitted()) {
			logException(context, exception, errorPageLocation, LOG_RENDER_EXCEPTION_HANDLED);

			// If the exception was thrown in midst of rendering the JSF response, then reset (partial) response.
			resetResponse(context);
		}
		else {
			logException(context, exception, errorPageLocation, LOG_RENDER_EXCEPTION_UNHANDLED);

			// Mojarra doesn't close the partial response during render exception. Let do it ourselves.
			OmniPartialViewContext.getCurrentInstance(context).closePartialResponse();
			return;
		}

		// Set the necessary servlet request attributes which a bit decent error page may expect.
		HttpServletRequest request = (HttpServletRequest) externalContext.getRequest();
		request.setAttribute(ERROR_EXCEPTION, exception);
		request.setAttribute(ERROR_EXCEPTION_TYPE, exception.getClass());
		request.setAttribute(ERROR_MESSAGE, exception.getMessage());
		request.setAttribute(ERROR_REQUEST_URI, request.getRequestURI());
		request.setAttribute(ERROR_STATUS_CODE, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

		try {
			renderErrorPageView(context, request, errorPageLocation);
		}
		catch (IOException e) {
			throw new FacesException(e);
		}

		while (unhandledExceptionQueuedEvents.hasNext()) {
			// Any remaining unhandled exceptions are not interesting. First fix the first.
			unhandledExceptionQueuedEvents.next();
			unhandledExceptionQueuedEvents.remove();
		}
	}

	/**
	 * Determine the root cause based on the caught exception, which will then be used to find the error page location.
	 * The default implementation delegates to {@link Exceptions#unwrap(Throwable)}.
	 * @param context The involved faces context.
	 * @param exception The caught exception to determine the root cause for.
	 * @return The root cause of the caught exception.
	 * @since 1.5
	 */
	protected Throwable findExceptionRootCause(FacesContext context, Throwable exception) {
		return unwrap(exception);
	}

	/**
	 * Returns <code>true</code> if the {@link FullAjaxExceptionHandler} should handle this exception root cause. If
	 * this returns <code>false</code>, then the {@link FullAjaxExceptionHandler} will skip handling this exception and
	 * delegate it further to the wrapped exception handler. The default implementation just returns <code>true</code>.
	 * @param context The involved faces context.
	 * @param exception The caught exception to determine the root cause for.
	 * @return <code>true</code> if the given exception should be handled by the {@link FullAjaxExceptionHandler}.
	 * @since 1.8
	 */
	protected boolean shouldHandleExceptionRootCause(FacesContext context, Throwable exception) {
		return true;
	}

	/**
	 * Determine the error page location based on the given exception.
	 * The default implementation delegates to {@link WebXml#findErrorPageLocation(Throwable)}.
	 * @param context The involved faces context.
	 * @param exception The exception to determine the error page for.
	 * @return The location of the error page. It must start with <code>/</code> and be relative to the context path.
	 * @since 1.5
	 */
	protected String findErrorPageLocation(FacesContext context, Throwable exception) {
		return WebXml.INSTANCE.findErrorPageLocation(exception);
	}

	/**
	 * Log the thrown exception and determined error page location with the given message, optionally parameterized
	 * with the given parameters.
	 * The default implementation logs through <code>java.util.logging</code> as SEVERE.
	 * @param context The involved faces context.
	 * @param exception The exception to log.
	 * @param location The error page location.
	 * @param message The log message.
	 * @param parameters The log message parameters, if any.
	 * @since 1.6
	 */
	protected void logException(FacesContext context, Throwable exception, String location, String message, Object... parameters) {
		logger.log(Level.SEVERE, String.format(message, location), exception);
	}

	private void resetResponse(FacesContext context) {
		ExternalContext externalContext = context.getExternalContext();
		String contentType = externalContext.getResponseContentType(); // Remember content type.
		String characterEncoding = externalContext.getResponseCharacterEncoding(); // Remember encoding.
		externalContext.responseReset();
		OmniPartialViewContext.getCurrentInstance(context).resetPartialResponse();
		externalContext.setResponseContentType(contentType);
		externalContext.setResponseCharacterEncoding(characterEncoding);
	}

	private void renderErrorPageView(FacesContext context, final HttpServletRequest request, String errorPageLocation)
		throws IOException
	{
		String viewId = getViewIdAndPrepareParamsIfNecessary(context, errorPageLocation);
		ViewHandler viewHandler = context.getApplication().getViewHandler();
		UIViewRoot viewRoot = viewHandler.createView(context, viewId);
		context.setViewRoot(viewRoot);
		context.getPartialViewContext().setRenderAll(true);
		Hacks.removeResourceDependencyState(context);

		try {
			ViewDeclarationLanguage vdl = viewHandler.getViewDeclarationLanguage(context, viewId);
			vdl.buildView(context, viewRoot);
			context.getApplication().publishEvent(context, PreRenderViewEvent.class, viewRoot);
			vdl.renderView(context, viewRoot);
			context.responseComplete();
		}
		catch (Exception e) {
			// Apparently, the error page itself contained an error.
			logException(context, e, errorPageLocation, LOG_ERROR_PAGE_ERROR);
			ExternalContext externalContext = context.getExternalContext();

			if (!externalContext.isResponseCommitted()) {
				// Okay, reset the response and tell that the error page itself contained an error.
				resetResponse(context);
				externalContext.setResponseContentType("text/xml");
				externalContext.getResponseOutputWriter().write(ERROR_PAGE_ERROR);
				context.responseComplete();
			}
			else {
				// Well, it's too late to handle. Just let it go.
				throw new FacesException(e);
			}
		}
		finally {
			// Prevent some servlet containers from handling error page itself afterwards. So far Tomcat/JBoss
			// are known to do that. It would only result in IllegalStateException "response already committed"
			// or "getOutputStream() has already been called for this response".
			request.removeAttribute(ERROR_EXCEPTION);
		}
	}

	private String getViewIdAndPrepareParamsIfNecessary(FacesContext context, String errorPageLocation) {
		String[] parts = errorPageLocation.split("\\?", 2);

		if (parts.length == 2) {
			// Map<String, List<String>> params = toParameterMap(parts[1]);
			// TODO: #287: make those params available via #{param(Values)}. Request wrapper needed :|
		}

		return normalizeViewId(context, parts[0]);
	}

	@Override
	public ExceptionHandler getWrapped() {
		return wrapped;
	}

}