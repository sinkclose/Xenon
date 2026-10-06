package zxc.iconic.xenon.passkey;

import java.security.GeneralSecurityException;

public interface Encryptable {

    String encryptToUrl(byte[] password) throws GeneralSecurityException;

    void decrypt(byte[] password) throws GeneralSecurityException;

    boolean needDecryption();
}
