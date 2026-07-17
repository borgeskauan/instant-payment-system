package br.kauan.notificationgateway.grpc.security;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

import javax.net.ssl.SSLSession;

@GrpcGlobalServerInterceptor
public class PspClientCertificateInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        SSLSession sslSession = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        String authenticatedIspb;
        try {
            authenticatedIspb = PspClientCertificateIdentityExtractor.extractIspb(sslSession);
        } catch (StatusRuntimeException e) {
            call.close(e.getStatus(), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        Context context = Context.current()
                .withValue(AuthenticatedPspContext.AUTHENTICATED_ISPB, authenticatedIspb);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
