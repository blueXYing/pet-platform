package com.petplatform.verification.biz.application;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
/** Versioned authenticated protection, with separate encryption and lookup keys. No default keys. */
public final class CredentialProtection {
    private final String active;
    private final Map<String,byte[]> encryption,lookup;
    private final SecureRandom random=new SecureRandom();
    public CredentialProtection(String active,Map<String,byte[]> encryption,Map<String,byte[]> lookup){
        this.active=Objects.requireNonNull(active);this.encryption=copy(encryption);this.lookup=copy(lookup);
        if(!active.matches("[A-Za-z0-9_-]{1,32}")||!this.encryption.containsKey(active)||!this.lookup.containsKey(active))throw new IllegalArgumentException("Credential keys required");
    }
    private static Map<String,byte[]> copy(Map<String,byte[]> input){
        var result=new HashMap<String,byte[]>();input.forEach((k,v)->{if(!k.matches("[A-Za-z0-9_-]{1,32}")||v==null||v.length!=32)throw new IllegalArgumentException("256 bit key required");result.put(k,v.clone());});return Map.copyOf(result);
    }
    public String keyId(){return active;}
    public String generate(){
        byte[] bytes=new byte[20];random.nextBytes(bytes);String alphabet="0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder s=new StringBuilder(32);int acc=0,bits=0;
        for(byte b:bytes){acc=(acc<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;s.append(alphabet.charAt((acc>>>bits)&31));}}return s.toString();
    }
    public String digest(String keyId,String value){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(required(lookup,keyId),"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw unavailable();}}
    public byte[] protect(String purpose,byte[] plain){
        try{byte[] iv=new byte[12];random.nextBytes(iv);byte[] name=active.getBytes(StandardCharsets.US_ASCII);byte[] body=cipher(Cipher.ENCRYPT_MODE,active,purpose,iv).doFinal(plain);
            return ByteBuffer.allocate(1+name.length+12+body.length).put((byte)name.length).put(name).put(iv).put(body).array();
        }catch(Exception e){throw unavailable();}
    }
    public byte[] reveal(String purpose,byte[] value){
        try{ByteBuffer b=ByteBuffer.wrap(value);int n=b.get()&255;if(n<1||n>32||b.remaining()<n+28)throw unavailable();byte[] name=new byte[n],iv=new byte[12];b.get(name);b.get(iv);byte[] body=new byte[b.remaining()];b.get(body);
            return cipher(Cipher.DECRYPT_MODE,new String(name,StandardCharsets.US_ASCII),purpose,iv).doFinal(body);
        }catch(Exception e){throw unavailable();}
    }
    private Cipher cipher(int mode,String name,String purpose,byte[] iv)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(required(encryption,name),"AES"),new GCMParameterSpec(128,iv));c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return c;}
    private static byte[] required(Map<String,byte[]> keys,String name){byte[] value=keys.get(name);if(value==null)throw unavailable();return value;}
    private static IllegalStateException unavailable(){return new IllegalStateException("Credential protection unavailable");}
}
