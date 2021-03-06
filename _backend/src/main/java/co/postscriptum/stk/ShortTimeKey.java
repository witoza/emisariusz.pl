package co.postscriptum.stk;

import co.postscriptum.security.AESGCMEncrypted;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShortTimeKey {

    private final String username;

    private final String key;

    private final Type type;

    private long validUntil;

    private AESGCMEncrypted extraData;

    public enum Type {
        USER_RESET_PASSWORD_REQUEST,
        RECALL_TOTP_KEY,
        REGISTER_NEW_USER,
        LOGIN_FROM_NOT_VERIFIED_BROWSER_TOKEN
    }

}
