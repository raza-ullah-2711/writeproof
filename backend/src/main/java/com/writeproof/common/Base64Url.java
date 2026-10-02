package com.writeproof.common;

import java.util.Base64;

/** Unpadded base64url, the wire encoding for keys, nonces and signatures. */
public final class Base64Url {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Base64Url() {}

    public static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    /** @throws IllegalArgumentException if {@code value} is not valid base64url */
    public static byte[] decode(String value) {
        return DECODER.decode(value);
    }
}
