package com.omniforge.core.persistence;

import com.omniforge.core.persistence.entity.License;
import com.omniforge.core.persistence.repository.LicenseRepository;
import com.omniforge.core.persistence.service.HardwareFingerprint;
import com.omniforge.core.persistence.service.LicenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * License v2（RSA + 有效期/订阅）测试。
 *
 * <p>测试内生成临时 RSA 密钥对并经构造器注入——生产使用内置公钥（无法获知私钥），
 * 测试用注入的密钥对充当"发行方"签发授权码。</p>
 */
class LicenseServiceTest {

    private LicenseRepository repository;
    private LicenseService service;
    private KeyPair keyPair;

    /** 有状态 mock：save 后 findTop 返回所保存的 License（模拟真实仓库行为） */
    private final License[] stored = new License[1];

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(LicenseRepository.class);
        when(repository.findTopByOrderByActivatedAtDesc()).thenAnswer(
                invocation -> stored[0] == null ? Optional.empty() : Optional.of(stored[0]));
        doAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return stored[0];
        }).when(repository).save(any(License.class));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        service = new LicenseService(repository, keyPair.getPublic());
    }

    /** 模拟发行方签发：OF2|指纹|到期日|签名（到期日 null = 永久） */
    private String issue(LocalDate expiresAt) throws Exception {
        String expiryText = expiresAt == null ? "" : expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String fingerprint = HardwareFingerprint.of();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((fingerprint + "|" + expiryText).getBytes("UTF-8"));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        return LicenseService.FORMAT_PREFIX + "|" + fingerprint + "|" + expiryText + "|" + signature;
    }

    @Test
    void 永久授权激活专业版() throws Exception {
        assertTrue(service.install(issue(null)));
        assertTrue(service.isPro());
        assertEquals(LicenseService.PRO_MAX_DEBATE_MODELS, service.maxDebateModels());
        assertTrue(service.proExpiry().isEmpty());
    }

    @Test
    void 订阅授权有效期内为专业版() throws Exception {
        assertTrue(service.install(issue(LocalDate.now().plusDays(30))));
        assertTrue(service.isPro());
        assertEquals(LicenseService.PRO_MAX_DEBATE_MODELS, service.maxDebateModels());
        assertEquals(LocalDate.now().plusDays(30), service.proExpiry().orElseThrow());
    }

    @Test
    void 订阅到期后动态降级社区版() throws Exception {
        // 激活一个昨天到期的授权码会被拒绝（install 时校验）；
        // 降级路径模拟"激活后时间流逝"：直接落一条已过期的授权记录再查询
        stored[0] = new License("OF2|x|" + LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE) + "|sig",
                true, LocalDate.now().minusDays(1));
        assertFalse(service.isPro());
        assertEquals(LicenseService.CE_MAX_DEBATE_MODELS, service.maxDebateModels());
        assertEquals(LocalDate.now().minusDays(1), service.proExpiry().orElseThrow());
    }

    @Test
    void 已过期授权码拒绝激活() throws Exception {
        assertFalse(service.install(issue(LocalDate.now().minusDays(1))));
        assertFalse(service.isPro());
    }

    @Test
    void 错误密钥对签名的授权码拒绝激活() throws Exception {
        // 用另一对密钥充当"伪造者"：公钥与服务持有不一致
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair forged = generator.generateKeyPair();
        String fingerprint = HardwareFingerprint.of();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(forged.getPrivate());
        signer.update((fingerprint + "|").getBytes("UTF-8"));
        String fake = LicenseService.FORMAT_PREFIX + "|" + fingerprint + "||"
                + Base64.getEncoder().encodeToString(signer.sign());
        assertFalse(service.install(fake));
    }

    @Test
    void 指纹不匹配拒绝激活() throws Exception {
        // 用他人指纹构造授权码（签名本身有效）
        String other = "0123456789abcdef".repeat(4);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((other + "|").getBytes("UTF-8"));
        String code = LicenseService.FORMAT_PREFIX + "|" + other + "||"
                + Base64.getEncoder().encodeToString(signer.sign());
        assertFalse(service.install(code));
    }

    @Test
    void 旧版HMAC格式不识别拒绝() {
        assertFalse(service.install("not-a-valid-key"));
        assertFalse(service.install("Base64HmacValueWithoutPrefix"));
        assertFalse(service.install(""));
        assertFalse(service.install(null));
    }

    @Test
    void 篡改到期日使签名失效() throws Exception {
        String code = issue(LocalDate.now().plusDays(30));
        String[] parts = code.split("\\|", -1);
        // 把到期日改成更远的日期（盗版延长订阅）——签名覆盖原文，应验签失败
        String tampered = parts[0] + "|" + parts[1] + "|2099-12-31|" + parts[3];
        assertFalse(service.install(tampered));
    }

    @Test
    void 社区版无License记录上限为3() {
        stored[0] = null;
        assertFalse(service.isPro());
        assertEquals(LicenseService.CE_MAX_DEBATE_MODELS, service.maxDebateModels());
    }

    @Test
    void 指纹格式为64位十六进制() {
        String fingerprint = HardwareFingerprint.of();
        assertEquals(64, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]+"));
    }
}
