package cn.managame.runtime.handler;

import cn.managame.runtime.context.ClientHandlerContext;

/**
 * Frames the response a Handler returned. This codec owns the wire envelope, which Runtime does not
 * define; it reads the context's correlation to address the response and must return a message the
 * connection's pipeline can encode.
 *
 * <p>Both methods run on the request's Route, right after its Handler returns or throws. A Handler that
 * answers some other way writes on the connection itself and never reaches this codec.
 *
 * <p>Returning hands ownership of the frame to Runtime, which releases it unless the transport accepts
 * it, so a frame carrying a reclaimable resource is safe here and must not be released by the codec.
 */
public interface ClientResultCodec {
    /**
     * Frames one business result, never null — Runtime treats a null result as a deliberate silence and
     * does not call this method.
     *
     * @return the transport message to write; returning null is an integration error.
     */
    Object encode(ClientHandlerContext context, Object result) throws Exception;

    /** Frames a framework error code without a business object, for a rejected or failed request. */
    Object encodeError(ClientHandlerContext context, int errorCode) throws Exception;
}
