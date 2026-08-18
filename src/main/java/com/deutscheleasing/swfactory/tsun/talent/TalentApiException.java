package com.deutscheleasing.swfactory.tsun.talent;

/** Any failure while talking to the TALENT Monitoring cloud API. */
public class TalentApiException extends RuntimeException {

    private final boolean unauthorized;

    public TalentApiException(String message) {
        this(message, false, null);
    }

    public TalentApiException(String message, boolean unauthorized, Throwable cause) {
        super(message, cause);
        this.unauthorized = unauthorized;
    }

    /** True when the API rejected our token/credentials, i.e. a re-login may help. */
    public boolean isUnauthorized() {
        return unauthorized;
    }
}
