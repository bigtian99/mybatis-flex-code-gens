package club.bigtian.mf.plugin.core.preview;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 预览片段的"重载修正器"。
 * <p>
 * MyBatis-Flex 的方法存在大量重载，预览代码里的参数形态是按启发式生成的，例如把"动态条件开关"
 * 生成为 {@code el -> true}。但这只在目标重载声明了函数式接口参数时才成立；实际 API 里
 * {@code isNull(boolean)}、{@code eq(Object, boolean)}、{@code between(Object, Object, boolean)}
 * 这类重载声明的都是 {@code boolean}，于是 {@code .isNull(el -> true)} 直接编译不过。
 * <p>
 * 这里按项目里真实的 mybatis-flex 方法签名（见 {@link MybatisFlexMethods}）逐个调用点判断：
 * <ul>
 *     <li>该位置存在 boolean 参数重载 -&gt; 写成 {@code true}</li>
 *     <li>该位置不存在该参数个数的重载，但存在更少参数的重载 -&gt; 去掉这个多余参数</li>
 *     <li>其余情况保持原样</li>
 * </ul>
 */
public final class SqlPreviewChainFixer {

    /**
     * 形如 {@code el -> true} 的开关参数
     */
    private static final Pattern FLAG_LAMBDA = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*\\s*->\\s*(true|false)$");

    private SqlPreviewChainFixer() {
    }

    /**
     * 按真实方法签名修正链式调用里的重载参数
     *
     * @param chainText 链式调用文本
     * @param methods   重载索引，为 null 时原样返回
     * @return 修正后的文本
     */
    public static String fix(String chainText, MybatisFlexMethods methods) {
        if (StrUtil.isBlank(chainText) || methods == null) {
            return chainText;
        }
        try {
            return rewrite(chainText, methods);
        } catch (Throwable e) {
            return chainText;
        }
    }

    private static String rewrite(String chainText, MybatisFlexMethods methods) {
        StringBuilder result = new StringBuilder(chainText.length() + 16);
        int index = 0;
        int length = chainText.length();
        while (index < length) {
            char current = chainText.charAt(index);
            if (current == '"' || current == '\'') {
                int end = skipLiteral(chainText, index);
                result.append(chainText, index, end);
                index = end;
                continue;
            }
            if (current != '.') {
                result.append(current);
                index++;
                continue;
            }
            int nameStart = index + 1;
            int nameEnd = nameStart;
            while (nameEnd < length && (Character.isJavaIdentifierPart(chainText.charAt(nameEnd)))) {
                nameEnd++;
            }
            if (nameEnd == nameStart || nameEnd >= length || chainText.charAt(nameEnd) != '(') {
                result.append(current);
                index++;
                continue;
            }
            String methodName = chainText.substring(nameStart, nameEnd);
            int closeIndex = findMatching(chainText, nameEnd);
            if (closeIndex < 0) {
                result.append(current);
                index++;
                continue;
            }
            // 参数里可能还嵌着链式调用（例如 where(ACCOUNT.ID.isNull(el -> true))），先递归修正
            String arguments = rewrite(chainText.substring(nameEnd + 1, closeIndex), methods);
            List<String> parts = splitArguments(arguments);
            List<String> fixed = fixArguments(methodName, parts, methods);
            result.append('.').append(methodName).append('(').append(String.join(", ", fixed)).append(')');
            index = closeIndex + 1;
        }
        return result.toString();
    }

    private static List<String> fixArguments(String methodName, List<String> parts, MybatisFlexMethods methods) {
        if (parts.isEmpty()) {
            return parts;
        }
        List<String> result = new ArrayList<>(parts);
        // UpdateChain/UpdateWrapper 的 set(列, 值)：预览时值只是占位符（"?"），
        // 遇到 Date 等有类型处理器的列会在 toSQL() 里做类型转换，抛 ClassCastException。
        // 换成 null 就不会走 TypeHandler，SQL 依然能正常生成。
        if ("set".equals(methodName) && result.size() >= 2) {
            String value = result.get(1).trim();
            if ("\"?\"".equals(value) || "'?'".equals(value) || "?".equals(value)) {
                result.set(1, "null");
            }
        }
        for (int i = result.size() - 1; i >= 0; i--) {
            String trimmed = result.get(i).trim();
            Matcher matcher = FLAG_LAMBDA.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }
            if (methods.acceptsBooleanAt(methodName, i)) {
                // 该位置确实有 boolean 重载，写成字面量 true/false
                result.set(i, matcher.group(1));
            } else if (!methods.hasArity(methodName, result.size()) && methods.hasArity(methodName, result.size() - 1)) {
                // 没有这个参数个数的重载，但少一个参数的重载存在，说明这是多余的开关参数
                result.remove(i);
            }
        }
        return result;
    }

    private static int skipLiteral(String text, int start) {
        char quote = text.charAt(start);
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            if (current == quote) {
                return index + 1;
            }
            index++;
        }
        return text.length();
    }

    private static int findMatching(String text, int openIndex) {
        int depth = 0;
        int index = openIndex;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '"' || current == '\'') {
                index = skipLiteral(text, index);
                continue;
            }
            if (current == '(' || current == '{' || current == '[') {
                depth++;
            } else if (current == ')' || current == '}' || current == ']') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
            index++;
        }
        return -1;
    }

    /**
     * 按顶层逗号切分参数，忽略括号和字符串里的逗号
     */
    static List<String> splitArguments(String arguments) {
        List<String> parts = new ArrayList<>();
        if (StrUtil.isBlank(arguments)) {
            return parts;
        }
        int depth = 0;
        int start = 0;
        int index = 0;
        while (index < arguments.length()) {
            char current = arguments.charAt(index);
            if (current == '"' || current == '\'') {
                index = skipLiteral(arguments, index);
                continue;
            }
            if (current == '(' || current == '{' || current == '[') {
                depth++;
            } else if (current == ')' || current == '}' || current == ']') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(arguments.substring(start, index).trim());
                start = index + 1;
            }
            index++;
        }
        String last = arguments.substring(start).trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }
}
