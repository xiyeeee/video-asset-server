package com.xiye.videoasset.auth.service;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
class VerificationMailSender {
    private static final Logger LOG = LoggerFactory.getLogger(VerificationMailSender.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final String host;
    private final boolean enabled;
    private final String template;

    VerificationMailSender(ObjectProvider<JavaMailSender> sender,
                           @Value("${app.auth.mail-enabled:false}") boolean enabled,
                           @Value("${app.auth.mail-from:}") String from,
                           @Value("${spring.mail.host:}") String host,
                           @Value("classpath:templates/mail/register-code.txt") Resource template) throws IOException {
        this.sender = sender;
        this.enabled = enabled;
        this.from = from;
        this.host = host;
        this.template = template.getContentAsString(StandardCharsets.UTF_8);
    }

    void requireConfiguration() {
        if (!enabled || from.isBlank() || host.isBlank() || sender.getIfAvailable() == null) {
            throw new BusinessException(ErrorCode.MAIL_UNAVAILABLE);
        }
    }

    boolean send(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("视频转游戏素材 · 注册验证码");
        message.setText(template.replace("{code}", code));

        try {
            sender.getObject().send(message);
            return true;
        } catch (MailException failure) {
            LOG.warn("注册验证码邮件投递失败 exceptionType={}", failure.getClass().getSimpleName());
            return false;
        }
    }
}
