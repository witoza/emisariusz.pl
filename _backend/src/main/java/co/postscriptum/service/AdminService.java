package co.postscriptum.service;

import co.postscriptum.db.Account;
import co.postscriptum.db.DB;
import co.postscriptum.email.UserEmailService;
import co.postscriptum.exception.BadRequestException;
import co.postscriptum.exception.ExceptionBuilder;
import co.postscriptum.exception.InternalException;
import co.postscriptum.internal.Params;
import co.postscriptum.internal.ReleasedMessagesDetails;
import co.postscriptum.internal.Utils;
import co.postscriptum.job.AccountMessageReleaserJob;
import co.postscriptum.job.AccountRemoverJob;
import co.postscriptum.model.bo.Message;
import co.postscriptum.model.bo.ReleaseItem;
import co.postscriptum.model.bo.ReleaseItem.Reminder;
import co.postscriptum.model.bo.RequiredAction;
import co.postscriptum.model.bo.RequiredAction.Resolution;
import co.postscriptum.model.bo.RequiredAction.Status;
import co.postscriptum.model.bo.RequiredAction.Type;
import co.postscriptum.model.bo.UserData;
import co.postscriptum.model.bo.UserInternal;
import co.postscriptum.security.AESGCMUtils;
import co.postscriptum.security.AESKeyUtils;
import co.postscriptum.security.RSAOAEPEncrypted;
import co.postscriptum.stk.ShortTimeKey;
import co.postscriptum.stk.ShortTimeKeyService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class AdminService {

    private final DB db;

    private final UserEmailService userEmailService;

    private final MessageReleaseService messageReleaseService;

    private final ShortTimeKeyService shortTimeKeyService;

    public List<RequiredAction> getRequiredAction(UserData userData, Status status) {
        return userData.getRequiredActions()
                       .stream()
                       .filter(ra -> ra.getStatus() == status)
                       .collect(Collectors.toList());

    }

    private String resolveIssueImpl(String adminInput, SecretKey userEncryptionKey, RequiredAction item) {

        if (item.getType() == Type.AUTOMATIC_RELEASE_MESSAGES_HAS_BEEN_DONE) {
            return "ACK";
        }

        if (item.getUserUuid() == null) {
            throw new IllegalStateException("Need to have userUuid");
        }

        return db.withLoadedAccountByUuid(item.getUserUuid(), account -> {
            UserData userData = account.getUserData();

            String returnMessage = null;

            if (item.getType() == Type.USER_STORAGE_INCREASE_REQUEST) {

                returnMessage = cmdUserStorageIncreaseRequest(userData, Params.of(item.getDetails()));

            } else if (item.getType() == Type.USER_RESET_PASSWORD_REQUEST) {

                returnMessage = cmdUserResetPasswordRequest(userData, userEncryptionKey);

            } else if (item.getType() == Type.REQUIRE_MANUAL_RELEASE_MESSAGES) {

                returnMessage = cmdManualReleaseMessagesRequired(userData, Params.of(item.getDetails()), userEncryptionKey);

            } else if (item.getType() == Type.MESSAGE_NOT_YET_OPENED_BY_THE_RECIPIENT) {

                returnMessage = cmdMessageNotOpenedByTheRecipient(userData, Params.of(item.getDetails()), adminInput);

            } else if (item.getType() == Type.ACCOUNT_CAN_BE_REMOVED) {

                cmdAccountCanBeRemoved(account);

            } else {
                throw new IllegalArgumentException("Don't know how to resolve issue of type " + item.getType());
            }

            if (returnMessage != null) {
                new UserDataHelper(userData).addNotification(returnMessage);
            }

            return returnMessage;
        });

    }

    private String cmdManualReleaseMessagesRequired(UserData userData, Params params, SecretKey userEncryptionKey) {
        if (userEncryptionKey == null) {
            throw new BadRequestException("You have to provide user encryption key to release user messages");
        }

        long userLastAccess = Long.parseLong(params.require(AccountMessageReleaserJob.USER_LAST_ACCESS));

        if (userLastAccess != userData.getUser().getLastAccess()) {
            return "User has logged in again after this issue was created, messages won't be send out";
        }

        UserInternal internal = userData.getInternal();

        if (internal.getEncryptionKeyEncryptedByAdminPublicKey() == null) {
            throw new InternalException("There is no EncryptionKeyEncryptedByAdminPublicKey, messages should have been released automatically");
        }

        ReleasedMessagesDetails details = messageReleaseService.releaseMessages(userData, Optional.of(userEncryptionKey));

        messageReleaseService.sendToOwnerReleasedMessageSummary(userData, details);

        userData.getUser().getTrigger().setReleasedTime(System.currentTimeMillis());

        return "User messages have been released:\n" + messageReleaseService.toHumanReadable(internal.getLang(), details);
    }

    private void cmdAccountCanBeRemoved(Account account) {
        db.removeAccount(account);
    }

    private String cmdMessageNotOpenedByTheRecipient(UserData userData, Params params, String adminInput) {
        if (StringUtils.isEmpty(adminInput)) {
            throw new BadRequestException("Admin input is required");
        }

        String releaseItemKey = params.require(AccountRemoverJob.RELEASE_ITEM_KEY);
        String remainderUuid = params.require(AccountRemoverJob.REMAINDER_UUID);

        Message message = new UserDataHelper(userData)
                .requireMessageByUuid(params.require(AccountRemoverJob.RELEASE_ITEM_MESSAGE_UUID));

        if (message.getRelease() == null) {
            return "There is no Release on the message, user must have resurrected";
        }

        ReleaseItem releaseItem = new UserDataHelper(userData).requireReleaseItem(message, releaseItemKey);

        Reminder remainder = releaseItem
                .getReminders()
                .stream()
                .filter(r -> r.getUuid().equals(remainderUuid))
                .findFirst()
                .orElseThrow(ExceptionBuilder.missingClass(Reminder.class, "uuid=" + remainderUuid));

        remainder.setResolvedTime(System.currentTimeMillis());
        remainder.setResolved(true);
        remainder.setInput(adminInput);

        return "Recipient " + releaseItem.getRecipient() + " has been manually contacted: " + adminInput;
    }

    private String cmdUserResetPasswordRequest(UserData userData, SecretKey userEncryptionKey) {
        if (userEncryptionKey == null) {
            throw new BadRequestException("You have to provide user encryption key to set user password");
        }

        UserInternal internal = userData.getInternal();

        if (internal.getEncryptionKeyEncryptedByAdminPublicKey() == null) {
            throw new InternalException(
                    "There is no encryptionKeyEncryptedByAdminPrivateKey, password should have been changed automatically");
        }

        SecretKey tmpKey = AESKeyUtils.generateRandomKey();

        ShortTimeKey stk = shortTimeKeyService.create(userData, ShortTimeKey.Type.USER_RESET_PASSWORD_REQUEST);
        stk.setExtraData(AESGCMUtils.encrypt(tmpKey, userEncryptionKey.getEncoded()));

        userEmailService.sendToOwnerAdminApprovedRequestPasswordReset(userData, stk, tmpKey);

        return "User's request for password change has been approved";
    }

    private String cmdUserStorageIncreaseRequest(UserData userData, Params params) {
        UserInternal internal = userData.getInternal();

        Integer numberOfMb = Integer.parseInt(params.require("numberOfMb"));

        internal.setQuotaBytes(internal.getQuotaBytes() + numberOfMb);

        return "Storage size increase of " + numberOfMb + " MB has been granted";
    }

    private void verifyNotResolved(RequiredAction requiredAction) {
        if (requiredAction.getStatus() == Status.resolved) {
            throw new BadRequestException("issue has already been resolved");
        }
    }

    public void removeIssue(UserData userData, String issueUuid) {
        log.info("Removing issue.uuid: {}", issueUuid);

        requireRequiredActionByUuid(userData, issueUuid);

        userData.getRequiredActions()
                .removeIf(ar -> ar.getUuid().equals(issueUuid));

    }

    public String getUserEncryptedEncryptionKey(String userUuid) {
        log.info("Get user.uuid: {} encrypted EncryptionKey", userUuid);

        RSAOAEPEncrypted encrypted = db.withLoadedAccountByUuid(userUuid, account -> {
            return account.getUserData().getInternal().getEncryptionKeyEncryptedByAdminPublicKey();
        });

        if (encrypted == null) {
            return "";
        }

        return Utils.base64encode(encrypted.getCt());
    }

    private SecretKey toSecretKey(String userEncryptionKeyBase64) {
        if (StringUtils.isEmpty(userEncryptionKeyBase64)) {
            return null;
        }
        try {
            return AESKeyUtils.toSecretKey(Utils.base64decode(userEncryptionKeyBase64));
        } catch (Exception e) {
            throw new BadRequestException("The user encryption key is invalid", e);
        }
    }

    public Resolution rejectIssue(UserData userData, String issueUuid, String input) {

        RequiredAction requiredAction = requireRequiredActionByUuid(userData, issueUuid);

        verifyNotResolved(requiredAction);

        Resolution resolution = new Resolution();
        resolution.setCreatedTime(System.currentTimeMillis());
        resolution.setUserInput(input);
        resolution.setMsg("issue manually rejected");

        requiredAction.getResolutions().add(resolution);
        requiredAction.setStatus(Status.resolved);
        return resolution;
    }

    public Resolution resolveIssue(UserData userData, String issueUuid, String input, String userEncryptionKeyBase64) {

        RequiredAction requiredAction = requireRequiredActionByUuid(userData, issueUuid);

        verifyNotResolved(requiredAction);

        Resolution resolution = new Resolution();
        resolution.setCreatedTime(System.currentTimeMillis());
        resolution.setUserInput(input);
        resolution.setMsg(resolveIssueImpl(input, toSecretKey(userEncryptionKeyBase64), requiredAction));

        requiredAction.getResolutions().add(resolution);
        requiredAction.setStatus(Status.resolved);
        return resolution;
    }

    private RequiredAction requireRequiredActionByUuid(UserData userData, String actionUuid) {

        return userData.getRequiredActions()
                       .stream()
                       .filter(ra -> ra.getUuid().equals(actionUuid))
                       .findFirst()
                       .orElseThrow(ExceptionBuilder.missingClass(RequiredAction.class, "uuid=" + actionUuid));
    }

}
