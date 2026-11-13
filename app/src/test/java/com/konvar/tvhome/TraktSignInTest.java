package com.konvar.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TraktSignInTest {
    @Test
    public void challengeMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                TraktSignIn.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void redirectMustBeAnHttpsUrl() {
        assertTrue(TraktSignIn.isRedirectUri("https://example.org/trakt/callback"));
        assertFalse(TraktSignIn.isRedirectUri("http://example.org/trakt/callback"));
        assertFalse(TraktSignIn.isRedirectUri("https://example.org/trakt/callback#fragment"));
        assertFalse(TraktSignIn.isRedirectUri("https://example.org@other.example/trakt/callback"));
    }
}
