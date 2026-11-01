package dev.shortlink.link;

import java.security.SecureRandom;

/**
 * D1 = ③ 随机 7 位 + 唯一索引。62 字符集，大小写敏感。
 */
public final class ShortCode {

    public static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final int BASE = ALPHABET.length();
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShortCode() {
    }

    public static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET.charAt(RANDOM.nextInt(BASE));
        }
        return new String(out);
    }

    /**
     * 格式校验。格式非法的请求在路由层就被挡掉（正则路径变量），
     * 一次存储查询都不会产生——这是 AC-2 里"不随访问量同比例增长"最便宜的一部分。
     */
    public static boolean isValid(String code, int length) {
        if (code == null || code.length() != length) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
