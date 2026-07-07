package cn.managame.common.lang;

import java.util.Locale;

/**
 * 跨模块通用的字符串 / 参数前置校验原语。
 * <p>
 * {@code isBlank}、{@code normalize}、{@code firstNonBlank} 等原本在 registry、config
 * 等模块里各自重复实现，统一收敛到 game-common。本类保持零依赖，任何模块都可安全引用。
 */
public final class Strings {

    private Strings() {
    }

    /** {@code value} 为 null 或仅由空白字符组成时返回 {@code true}。 */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** {@link #isBlank(String)} 取反。 */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * 断言非空白，否则抛 {@link IllegalArgumentException}，成功时原样返回 {@code value}。
     * <p>
     * 模块若需要抛自己的异常类型（如 {@code RegistryOperationException}），应改为自行判
     * {@link #isBlank(String)} 后抛出，不要用本方法。
     */
    public static String requireNonBlank(String value, String fieldName) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /**
     * 归一化标识符：trim 后转小写（{@link Locale#ROOT}）。输入为 null 或空白时返回 {@code null}。
     * <p>
     * SPI 类型名匹配普遍需要这套“大小写 / 首尾空白不敏感”的比较。
     */
    public static String normalizeToLower(String value) {
        return isBlank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 返回第一个非空白的值；全部为空白（或数组为 null）时返回 {@code null}。 */
    public static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (isNotBlank(value)) {
                    return value;
                }
            }
        }
        return null;
    }
}
