/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2026 Extremely Heavy Industries Inc.
 */

package io.xh.hoist

import grails.async.Promise
import groovy.transform.CompileStatic
import groovy.transform.NamedParam
import groovy.transform.NamedVariant
import io.xh.hoist.cluster.ClusterService
import io.xh.hoist.cluster.ClusterResult
import io.xh.hoist.json.JSONParser
import io.xh.hoist.telemetry.trace.SpanRef
import io.xh.hoist.json.JSONSerializer
import io.xh.hoist.log.LogSupport
import io.xh.hoist.user.HoistUser
import io.xh.hoist.user.IdentityService
import io.xh.hoist.user.IdentitySupport
import io.xh.hoist.util.Utils
import org.owasp.encoder.Encode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static grails.async.web.WebPromises.task
import static java.nio.charset.StandardCharsets.UTF_8
import static io.xh.hoist.HoistFilter.REQUEST_SPAN_ATTR
import static org.apache.hc.core5.http.HttpStatus.SC_NO_CONTENT
import static org.apache.hc.core5.http.HttpStatus.SC_OK

@CompileStatic
abstract class BaseController implements LogSupport, IdentitySupport {

    IdentityService identityService
    ClusterService clusterService

    /**
     * Render an object to JSON.
     *
     * Favor this method over the direct use of grails `render` method in order
     * to utilize the customizable jackson-based serialization exposed by Hoist.
     *
     * @param o - object to be serialized.
     */
    protected void renderJSON(Object o){
        response.contentType = 'application/json; charset=UTF-8'
        render (JSONSerializer.serialize(o))
    }

    /**
     * Render a collection of objects as newline-delimited JSON (NDJSON), streaming each element
     * to the client as it is serialized.
     *
     * Favor over {@link #renderJSON} for large row-oriented datasets — elements are written
     * incrementally, so neither the full JSON string nor (for lazy sources) the full dataset is
     * held in memory, and clients can parse rows as they arrive.
     *
     * The response is flushed (and committed) after the first element, so earlier failures still
     * render a clean error response. A mid-stream failure cannot alter the committed status —
     * instead the stream is terminated with a deliberately non-JSON line, so consumers fail
     * parsing rather than mistaking the truncated (but otherwise well-formed) stream for a
     * complete result. Successful streams are standard NDJSON, with every line
     * newline-terminated and no end delimiter.
     *
     * @param source - an Iterable or Iterator of elements to serialize, one per line.
     * @param contentType - defaults to 'application/x-ndjson'.
     */
    @NamedVariant
    protected void renderNdJSON(Object source, @NamedParam String contentType = null) {
        Iterator<?> rows = source instanceof Iterator ? source : (source as Iterable).iterator()

        response.contentType = contentType ?: 'application/x-ndjson'
        response.characterEncoding = 'UTF-8'

        BufferedOutputStream out = null
        try {
            while (rows.hasNext()) {
                byte[] row = serializeNdjsonRow(rows.next())
                boolean first = !out
                if (first) {
                    // Acquire the output stream lazily - first-element failures (bad source,
                    // failing lazy query) can then still render a clean error response.
                    out = new BufferedOutputStream(response.outputStream, NDJSON_BUFFER_SIZE)
                }
                out.write(row)
                if (first) out.flush()
            }
            out ? out.flush() : response.flushBuffer()
        } catch (Throwable t) {
            // Truncation falls at a line boundary and would otherwise read as a complete
            // stream. Guarded so a failed write cannot mask the original exception.
            if (response.committed) {
                try {
                    out.write(NDJSON_POISON.getBytes(UTF_8))
                    out.flush()
                } catch (Throwable ignored) {}
            }
            throw t
        }
    }

    /**
     * Parse JSON submitted in the body of the request.
     *
     * Favor this method over the direct use of grails' request.getJSON() in order
     * to utilize the customizable jackson-based parsing provided by Hoist.
     *
     * @param safeEncode - true to run input through OWASP encoder before parsing.
     */
    @NamedVariant
    protected Map parseRequestJSON(@NamedParam boolean safeEncode = false) {
        safeEncode ?
            JSONParser.parseObject(this.safeEncode(request.inputStream.text)) :
            JSONParser.parseObject(request.inputStream)
    }

    /**
     * Parse JSON submitted in the body of the request.
     *
     * Favor this method over the direct use of grails' request.getJSON() in order
     * to utilize the customizable jackson-based parsing provided by Hoist.
     *
     * @param safeEncode - true to run input through OWASP encoder before parsing.
     */
    @NamedVariant
    protected List parseRequestJSONArray(@NamedParam boolean safeEncode = false) {
        safeEncode ?
            JSONParser.parseArray(this.safeEncode(request.inputStream.text)) :
            JSONParser.parseArray(request.inputStream)
    }

    /**
     * Run user-provided string input through an OWASP-provided encoder to escape tags. Note the
     * use of `forHtmlContent()` encodes only `&<>` and in particular leaves quotes un-escaped to
     * support JSON strings.
     */
    protected String safeEncode(String input) {
        return input ? Encode.forHtmlContent(input) : input
    }

    /**
     * Render a ClusterResult to the Request object as Json.
     *
     * If the result's value is a string, it will be assumed to be Json and rendered as is.
     * Otherwise it will be serialized as needed.  The former is the most efficient, and typical
     * use of this method; to get ClusterResults in this form, be sure to use the appropriate
     * variants of ClusterUtils, e.g. `ClusterUtils.runOnXXXAsJson`.
     */
    protected void renderClusterJSON(ClusterResult result) {
        def contentType = 'application/json; charset=UTF-8',
            exception = result.exception,
            value = result.value

        if (exception) {
            render(
                text: exception.causeAsJson,
                status: exception.causeStatusCode,
                contentType: contentType
            )
        } else {
            value != null ?
                render(
                    text: value instanceof String ? value : JSONSerializer.serialize(value),
                    status: SC_OK,
                    contentType: contentType
                ) :
                render(
                    text: null,
                    status: SC_NO_CONTENT,
                    contentType: contentType
                )
        }
    }


    /**
     * Render an empty, successful response.
     */
    protected void renderSuccess() {
        // Content type not strictly needed -- this type provides consistency with the rest of the
        // api and in particular what would be returned for an exception on the same endpoint.
        render(text: null, status: SC_NO_CONTENT, contentType: 'application/json; charset=UTF-8')
    }

    protected Promise runAsync(Closure c) {
        task {
            try {
                c.call()
            } catch (Throwable t) {
                handleUncaughtInternal(t)
            }
        }
    }

    HoistUser getUser()         {identityService.user}
    String getUsername()        {identityService.username}
    HoistUser getAuthUser()     {identityService.authUser}
    String getAuthUsername()    {identityService.authUsername}

    //-------------------
    // Implementation
    //-------------------
    /**
     * Batches rows into large writes to amortize per-call overhead of the servlet output stream.
     * 4x Tomcat's default 8KB response buffer and equal to the 32KB deflate window — larger
     * values gain no throughput and only delay delivery to the client.
     */
    private static final int NDJSON_BUFFER_SIZE = 32 * 1024

    /**
     * Deliberately non-JSON line written by {@link #renderNdJSON} when a stream fails after the
     * response has committed. Guarantees consumers see a parse failure rather than a truncated
     * stream that reads as complete. Never present in a successful response, which remains
     * standard NDJSON. No trailing newline — an incomplete final line reinforces the signal.
     */
    private static final String NDJSON_POISON = '//xh-ndjson-stream-error'

    private static byte[] serializeNdjsonRow(Object row) {
        (JSONSerializer.serialize(row) + '\n').getBytes(UTF_8)
    }

    void handleException(Exception ex) {
        handleUncaughtInternal(ex)
    }

    private void handleUncaughtInternal(Throwable t) {
        def span = request.getAttribute(REQUEST_SPAN_ATTR) as SpanRef
        span?.recordException(t)
        Utils.handleException(
            exception: t,
            logTo: this,
            logMessage: [_action: actionName],
            renderTo: response
        )
    }

    // Provide cached logger to LogSupport for possible performance benefit
    private final Logger _log = LoggerFactory.getLogger(this.class)
    Logger getInstanceLog() { _log }
}
