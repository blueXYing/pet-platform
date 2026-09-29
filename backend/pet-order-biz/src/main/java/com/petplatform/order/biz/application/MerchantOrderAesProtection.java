package com.petplatform.order.biz.application;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
/** Authenticated encryption binds ciphertext to its command/decision purpose. */
public final class MerchantOrderAesProtection implements MerchantOrderPorts.Protection {
    private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
    public MerchantOrderAesProtection(byte[] key){if(key==null||key.length!=32)throw new IllegalArgumentException("256-bit order protection key required");this.key=new SecretKeySpec(key.clone(),"AES");}
    public byte[] protect(String purpose,byte[] plaintext){
        try{byte[] iv=new byte[12];random.nextBytes(iv);Cipher c=cipher(Cipher.ENCRYPT_MODE,purpose,iv);
            byte[] body=c.doFinal(plaintext);return ByteBuffer.allocate(12+body.length).put(iv).put(body).array();
        }catch(Exception failure){throw new IllegalStateException("Order value protection failed");}
    }
    public byte[] reveal(String purpose,byte[] value){
        try{if(value==null||value.length<28)throw new IllegalArgumentException();ByteBuffer b=ByteBuffer.wrap(value);byte[] iv=new byte[12];b.get(iv);
            byte[] body=new byte[b.remaining()];b.get(body);return cipher(Cipher.DECRYPT_MODE,purpose,iv).doFinal(body);
        }catch(Exception failure){throw new IllegalStateException("Order protected value unavailable");}
    }
    private Cipher cipher(int mode,String purpose,byte[] iv)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,key,new GCMParameterSpec(128,iv));
        c.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return c;
    }
}
