package com.relaydelivery.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryCodeProtectorTest {
    private final DeliveryCodeProtector protector=new DeliveryCodeProtector();

    @Test void generatesExactlySixDigitsAndProtectsAtRest(){
        String code=protector.generate();
        assertTrue(code.matches("[0-9]{6}"));
        DeliveryCodeProtector.ProtectedCode protectedCode=protector.protect(code);
        assertNotEquals(code,protectedCode.hash());
        assertNotEquals(code,protectedCode.ciphertext());
        assertTrue(protector.verify(code,protectedCode.hash()));
        assertFalse(protector.verify("999999".equals(code)?"000000":"999999",protectedCode.hash()));
        assertEquals(code,protector.decrypt(protectedCode.ciphertext()));
    }

    @Test void fingerprintIsStableButSaltedHashesAreNot(){
        DeliveryCodeProtector.ProtectedCode first=protector.protect("482915");
        DeliveryCodeProtector.ProtectedCode second=protector.protect("482915");
        assertEquals(first.fingerprint(),second.fingerprint());
        assertNotEquals(first.hash(),second.hash());
        assertNotEquals(first.ciphertext(),second.ciphertext());
    }
}
