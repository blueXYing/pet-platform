package com.petplatform.order.biz.application;
/** Protection dependency only, not approval of any text or business action. */
@FunctionalInterface
public interface OrderCreationInputProtection {
    ProtectedInput protect(String purpose, String value);
    record ProtectedInput(byte[] ciphertext, byte[] equalityToken) {
        public ProtectedInput { ciphertext=ciphertext==null?null:ciphertext.clone(); equalityToken=equalityToken==null?null:equalityToken.clone(); }
        @Override public byte[] ciphertext(){return ciphertext==null?null:ciphertext.clone();}
        @Override public byte[] equalityToken(){return equalityToken==null?null:equalityToken.clone();}
    }
}
