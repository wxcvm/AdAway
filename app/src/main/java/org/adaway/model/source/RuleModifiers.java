package org.adaway.model.source;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则里「我们不实现的资源类型修饰符」的判据（G1/D 的跨端向量就断言这个判定）。
 *
 * <p>放在这里而不是留在 {@code SourceLoader} 的私有嵌套类里，是为了让它**可被单元测试调用**：
 * 跨端测试向量（{@code webserver/jni/rule_vectors.txt}）要求 Android 侧与 C 侧的判定逐条一致，
 * 而 C 侧的同一判据在 {@code webserver/jni/rule_norm.h} 里。两边的实现都只有这一份。</p>
 *
 * <p>判据：出现 {@code $}，且它位于主机名之后（即 {@code ^} 之后；没有 {@code ^} 时也算）。
 * 这类规则的正确处理是**跳过并计数**，而不是截断成主机名 —— 后者会把它变成「整个域名都拦」。</p>
 */
public final class RuleModifiers {

    private RuleModifiers() {
    }

    /** 因带未实现的资源类型修饰符而被跳过的规则数（线程安全）。 */
    private static final AtomicLong SKIPPED = new AtomicLong();

    /** 该行是否带我们不实现的资源类型修饰符（$script、$image、$third-party 等）。 */
    public static boolean hasUnsupportedModifier(String line) {
        int dollar = line.indexOf('$');
        if (dollar < 0) {
            return false;
        }
        int caret = line.indexOf('^');
        return caret < 0 || dollar > caret;
    }

    /** 记一条被跳过的规则。 */
    public static void noteSkipped() {
        SKIPPED.incrementAndGet();
    }

    /** 累计跳过多少条（供诊断与测试使用）。 */
    public static long skipped() {
        return SKIPPED.get();
    }
}
