package co.postscriptum.controller;

import co.postscriptum.controller.dto.UuidDTO;
import co.postscriptum.exception.BadRequestException;
import co.postscriptum.model.bo.Lang;
import co.postscriptum.model.bo.Message;
import co.postscriptum.model.bo.Message.Type;
import co.postscriptum.model.bo.UserData;
import co.postscriptum.model.dto.MessageDTO;
import co.postscriptum.security.UserEncryptionKeyService;
import co.postscriptum.service.MessageService;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.NotEmpty;
import org.hibernate.validator.constraints.SafeHtml;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/msg")
@AllArgsConstructor
public class MessageController {

    private final MessageService messageService;

    private final UserEncryptionKeyService userEncryptionKeyService;

    @PostMapping(value = "/remove_password")
    public MessageDTO removePassword(UserData userData, @Valid @RequestBody EncryptedMsgDTO dto) {

        Message message = messageService.removePassword(userData,
                                                        userEncryptionKeyService.requireEncryptionKey(),
                                                        dto.uuid,
                                                        dto.encryption_passwd);

        return messageService.convertToDto(userData, message);
    }

    @PostMapping(value = "/set_password")
    public MessageDTO setPassword(UserData userData, @Valid @RequestBody SetMsgPasswordDTO dto) {

        Message message = messageService.setPassword(userData,
                                                     userEncryptionKeyService.requireEncryptionKey(),
                                                     dto.uuid,
                                                     dto.encryption_passwd,
                                                     dto.encryption_passwd_new,
                                                     dto.hint);

        return messageService.convertToDto(userData, message);
    }

    @PostMapping(value = "/decrypt")
    public MessageDTO decrypt(UserData userData, @Valid @RequestBody EncryptedMsgDTO dto) {

        Message message = messageService.requireMessageByUuid(userData, dto.uuid);
        if (message.getEncryption() == null) {
            throw new BadRequestException("Expected encrypted message");
        }

        MessageDTO mdto = messageService.convertToDto(userData, message);

        mdto.setContent(messageService.getMessageContent(message,
                                                         userEncryptionKeyService.requireEncryptionKey(),
                                                         dto.encryption_passwd));

        return mdto;
    }

    @GetMapping(value = "/get_abstract")
    public List<MessageDTO> getAbstract(UserData userData) {
        return userData.getMessages()
                       .stream()
                       .map(message -> messageService.convertToDto(userData, message))
                       .collect(Collectors.toList());
    }

    @PostMapping(value = "/load_msg")
    public MessageDTO loadMsg(UserData userData, @Valid @RequestBody UuidDTO dto) {

        Message message = messageService.requireMessageByUuid(userData, dto.uuid);

        MessageDTO mdto = messageService.convertToDto(userData, message);

        if (message.getEncryption() == null) {
            mdto.setContent(messageService.getMessageContent(message,
                                                             userEncryptionKeyService.requireEncryptionKey(),
                                                             null));
        }

        return mdto;
    }

    @PostMapping(value = "/delete_msg")
    public void deleteMsg(UserData userData, @Valid @RequestBody UuidDTO dto) {
        messageService.deleteMessage(userData, dto.uuid);
    }

    @PostMapping(value = "/add_msg")
    public MessageDTO addMsg(UserData userData, @Valid @RequestBody AddMsgDTO dto) {

        SecretKey encryptionKey = userEncryptionKeyService.requireEncryptionKey();

        Message message = messageService.addMessage(userData, encryptionKey, dto);

        MessageDTO mdto = messageService.convertToDto(userData, message);

        if (message.getEncryption() == null) {
            mdto.setContent(messageService.getMessageContent(message, encryptionKey, null));
        }

        return mdto;
    }

    @PostMapping(value = "/update_msg")
    public MessageDTO updateMsg(UserData userData, @Valid @RequestBody UpdateMsgDTO dto) {

        SecretKey encryptionKey = userEncryptionKeyService.requireEncryptionKey();

        Message message = messageService.updateMessage(userData, encryptionKey, dto);

        MessageDTO mdto = messageService.convertToDto(userData, message);

        mdto.setContent(messageService.getMessageContent(message, encryptionKey, dto.encryption_passwd));

        return mdto;
    }

    @Getter
    @Setter
    private static class EncryptedMsgDTO extends UuidDTO {

        @NotEmpty
        @Size(max = 20)
        String encryption_passwd;
    }

    @Getter
    @Setter
    private static class SetMsgPasswordDTO extends UuidDTO {

        @Size(max = 20)
        String encryption_passwd;

        @NotEmpty
        @Size(max = 20)
        String encryption_passwd_new;

        @NotEmpty
        @Size(max = 100)
        String hint;

    }

    @Getter
    @Setter
    public static class AddMsgDTO {

        @NotNull
        @SafeHtml
        String content;

        @NotNull
        @Size(max = 50)
        String title;

        @NotNull
        @Size(max = 20)
        List<String> recipients;

        @NotNull
        @Size(max = 20)
        List<String> attachments;

        @NotNull
        Type type;

    }

    @Getter
    @Setter
    public static class UpdateMsgDTO extends UuidDTO {

        @SafeHtml
        String content;

        @Size(max = 50)
        String title;

        @Size(max = 20)
        List<String> recipients;

        @Size(max = 20)
        List<String> attachments;

        Type type;

        @Size(max = 20)
        String encryption_passwd;

        Lang lang;

    }

}
