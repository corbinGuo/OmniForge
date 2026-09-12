package com.omniforge.core.persistence.service;

import com.omniforge.core.persistence.entity.License;
import com.omniforge.core.persistence.repository.LicenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;

/**
 * License 校验 v2（RSA 签名 + 有效期/订阅）。
 *
 * <p><b>授权码格式</b>：{@code OF2|<指纹>|<到期日>|<签名>}——
 * 指纹为 64 位十六进制硬件指纹；到期日 {@code yyyy-MM-dd}（含当天有效）或空串（永久授权）；
 * 签名 = {@code Base64(SHA256withRSA(发行方私钥, "<指纹>|<到期日>"))}。
 * 客户端仅内置 RSA 公钥验证（非对称：私钥只在发行方，开源代码无法自签授权码）。</p>
 *
 * <p><b>订阅模型</b>：带到期日的授权码到期后 {@link #isPro()} 动态判定为 false
 * （自动降级社区版，数据无损），续订 = 粘贴新授权码覆盖激活。
 * v1 HMAC 格式不再接受（发布前无存量授权码）。</p>
 *
 * <p>公钥可经构造器注入（测试用临时密钥对）；生产装配使用内置公钥。</p>
 */
public class LicenseService {

    /** 社区版单场辩论模型上限（v5.2；专业版为 10） */
    public static final int CE_MAX_DEBATE_MODELS = 3;
    /** 专业版单场辩论模型上限（v5.2 冻结） */
    public static final int PRO_MAX_DEBATE_MODELS = 10;

    /** 授权码版本前缀 */
    public static final String FORMAT_PREFIX = "OF2";

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /**
     * 内置发行方 RSA 公钥（X.509/DER + Base64）。
     * 非对称体系下公钥公开无害；对应私钥由发行方离线保管（见 LICENSE_ISSUANCE 文档）。
     */
    static final String BUILTIN_PUBLIC_KEY_BASE64 = ""
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAp2w5eDAd892xxHq6hC+neKL34AteCpTud8i"
            + "6RN/DudEuqsT9lVi/5SnRu5IzlRLMaoDeUR/gQxnz2XBIgqzA0R9D9uajICW6+7sdYZD+AbMBkiXN9"
            + "NwHIaLugCHsmN6YzQ4M8GBgWiuIxrvplX9Kx/JhweMU85CxU69i6qjPABJR36fMM7a4Bjq0czIfYJm"
            + "okpDrM2sQqf5XaYWcdp+8DfWuE08oDsRQ+/m3+xdMvk5Rm/PN7sFojqCyrl2Hes4G6sMjwIsC2i1Aw"
            + "uECF0hY0Ae/iuZdkKdeI6gfDuRwQscKCvKtETRlzui4NZvu13hQmavoT5VXUb5fhqaeYNafTwIDAQAB";

    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final LicenseRepository licenseRepository;
    private final PublicKey publicKey;

    public LicenseService(LicenseRepository licenseRepository) {
        this(licenseRepository, decodePublicKey(BUILTIN_PUBLIC_KEY_BASE64));
    }

    /** 测试/定制装配：注入验证公钥 */
    public LicenseService(LicenseRepository licenseRepository, PublicKey publicKey) {
        this.licenseRepository = licenseRepository;
        this.publicKey = publicKey;
    }

    /** 本机硬件指纹（授权申请时提交给发行方） */
    public String fingerprint() {
        return HardwareFingerprint.of();
    }

    /**
     * 当前是否专业版有效：<b>动态判定</b>——已激活且（永久授权或今天未过到期日）。
     * 到期次日自动回社区版，无需清理任务。
     */
    public boolean isPro() {
        Optional<License> latest = licenseRepository.findTopByOrderByActivatedAtDesc();
        return latest.map(license -> license.isPro()
                && (license.getExpiresAt() == null
                || !license.getExpiresAt().isBefore(LocalDate.now()))).orElse(false);
    }

    /** 最新授权的到期日（永久授权返回 empty）；未激活返回 empty */
    public Optional<LocalDate> proExpiry() {
        return licenseRepository.findTopByOrderByActivatedAtDesc()
                .map(License::getExpiresAt)
                .filter(date -> date != null);
    }

    /** 单场辩论模型上限（CE 3 / Pro 10，按 isPro 动态判定） */
    public int maxDebateModels() {
        return isPro() ? PRO_MAX_DEBATE_MODELS : CE_MAX_DEBATE_MODELS;
    }

    /**
     * 激活专业版：校验授权码格式、指纹绑定、RSA 签名后落库（续订 = 覆盖激活）。
     *
     * @return true 激活成功；格式不识别 / 指纹不符 / 签名无效 / 已过期授权码均拒绝
     */
    @Transactional
    public boolean install(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            return false;
        }
        String code = licenseKey.trim();
        String[] parts = code.split("\\|", -1);
        if (parts.length != 4 || !FORMAT_PREFIX.equals(parts[0])) {
            log.warn("License 激活失败：格式不识别（应为 {}|指纹|到期日|签名）", FORMAT_PREFIX);
            return false;
        }
        String fingerprint = parts[1];
        String expiryText = parts[2];
        String signature = parts[3];
        if (!fingerprint.equals(fingerprint())) {
            log.warn("License 激活失败：授权码绑定指纹与本机不符");
            return false;
        }
        LocalDate expiresAt = parseExpiry(expiryText);
        if (expiresAt == null && !expiryText.isEmpty()) {
            log.warn("License 激活失败：到期日格式非法（应为 yyyy-MM-dd 或空=永久）");
            return false;
        }
        if (expiresAt != null && expiresAt.isBefore(LocalDate.now())) {
            log.warn("License 激活失败：授权码已于 {} 过期（续订请索取新码）", expiresAt);
            return false;
        }
        if (!verifySignature(fingerprint, expiryText, signature)) {
            log.warn("License 激活失败：RSA 签名校验不通过");
            return false;
        }
        licenseRepository.save(new License(code, true, expiresAt));
        log.info("专业版已激活（指纹 {}…，{}）", fingerprint.substring(0, 12),
                expiresAt == null ? "永久授权" : "有效期至 " + expiresAt);
        return true;
    }

    /** 到期日解析：空串 = 永久（null）；非法格式返回 null 并由调用方区分（配合空串判断） */
    private static LocalDate parseExpiry(String expiryText) {
        if (expiryText == null || expiryText.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(expiryText, EXPIRY_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean verifySignature(String fingerprint, String expiryText, String signatureBase64) {
        try {
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((fingerprint + "|" + expiryText).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            log.debug("License 签名验证异常", e);
            return false;
        }
    }

    private static PublicKey decodePublicKey(String base64Der) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Der);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("内置 License 公钥解析失败", e);
        }
    }
}
