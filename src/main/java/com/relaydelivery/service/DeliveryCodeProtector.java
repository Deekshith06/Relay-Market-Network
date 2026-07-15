package com.relaydelivery.service;

import com.relaydelivery.config.Database;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

public final class DeliveryCodeProtector {
    public record ProtectedCode(String hash,String ciphertext,String fingerprint) {}
    private static final int ITERATIONS=120_000;
    private static final SecureRandom RANDOM=new SecureRandom();
    private final byte[] key;

    public DeliveryCodeProtector(){key=loadKey();}
    public String generate(){return String.format("%06d",RANDOM.nextInt(1_000_000));}
    public ProtectedCode protect(String code){return new ProtectedCode(hash(code),encrypt(code),fingerprint(code));}

    public boolean verify(String code,String stored){
        try{String[] parts=stored.split(":",2);byte[] salt=Base64.getUrlDecoder().decode(parts[0]);
            byte[] expected=Base64.getUrlDecoder().decode(parts[1]);return MessageDigest.isEqual(expected,derive(code.toCharArray(),salt));}
        catch(Exception e){return false;}
    }

    public String decrypt(String value){
        try{String[] parts=value.split(":",2);byte[] iv=Base64.getUrlDecoder().decode(parts[0]);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[1])),StandardCharsets.UTF_8);}
        catch(Exception e){throw new IllegalStateException("Delivery code cannot be decrypted",e);}
    }

    public String fingerprint(String code){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Delivery code fingerprint failed",e);}
    }

    private String hash(String code){byte[] salt=new byte[16];RANDOM.nextBytes(salt);return enc(salt)+":"+enc(derive(code.toCharArray(),salt));}
    private String encrypt(String code){
        try{byte[] iv=new byte[12];RANDOM.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return enc(iv)+":"+enc(cipher.doFinal(code.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("Delivery code encryption failed",e);}
    }
    private static byte[] derive(char[] code,byte[] salt){
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(code,salt,ITERATIONS,256)).getEncoded();}
        catch(Exception e){throw new IllegalStateException("Delivery code hashing failed",e);}
    }
    private static String enc(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}

    private static byte[] loadKey(){
        String configured=System.getenv("DELIVERY_CODE_KEY");if(configured!=null&&!configured.isBlank())return decodeKey(configured);
        Path file=Path.of(Database.env("DELIVERY_CODE_KEY_FILE",".relay-delivery-code.key"));
        try{
            if(Files.notExists(file)){byte[] generated=new byte[32];RANDOM.nextBytes(generated);String encoded=Base64.getEncoder().encodeToString(generated);
                try{Files.writeString(file,encoded,StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    try{Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));}catch(UnsupportedOperationException ignored){}
                }catch(FileAlreadyExistsException ignored){}}
            return decodeKey(Files.readString(file,StandardCharsets.US_ASCII).trim());
        }catch(Exception e){throw new IllegalStateException("Set DELIVERY_CODE_KEY to a Base64-encoded 32-byte key",e);}
    }
    private static byte[] decodeKey(String value){
        try{byte[] decoded=Base64.getDecoder().decode(value);if(decoded.length!=32)throw new IllegalArgumentException();return decoded;}
        catch(IllegalArgumentException e){throw new IllegalStateException("DELIVERY_CODE_KEY must contain exactly 32 Base64-encoded bytes");}
    }
}
