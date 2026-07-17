package br.kauan.notificationgateway.grpc.security;

import io.grpc.Context;
import io.grpc.Status;

public final class AuthenticatedPspContext {

    public static final Context.Key<String> AUTHENTICATED_ISPB = Context.key("authenticated-ispb");

    private AuthenticatedPspContext() {
    }

    public static String requireAuthenticatedIspb() {
        String ispb = AUTHENTICATED_ISPB.get();
        if (ispb == null || ispb.isBlank()) {
            throw Status.UNAUTHENTICATED
                    .withDescription("authenticated PSP ISPB is required")
                    .asRuntimeException();
        }
        return ispb;
    }
}
