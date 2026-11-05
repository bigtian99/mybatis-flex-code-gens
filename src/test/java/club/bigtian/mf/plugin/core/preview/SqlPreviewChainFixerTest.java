package club.bigtian.mf.plugin.core.preview;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;

/**
 * 重载修正器测试。
 * <p>
 * 关注点是：MyBatis-Flex 里同名方法大量重载时，预览片段里的 {@code el -> true}
 * 必须落在真实存在的那一个重载上，否则生成的预览代码根本编译不过。
 */
public class SqlPreviewChainFixerTest {

    private MybatisFlexMethods methods() {
        Map<String, List<Class<?>[]>> table = new HashMap<>();
        // isNull(boolean) / isNotNull(boolean)
        table.put("isNull", List.<Class<?>[]>of(new Class<?>[]{boolean.class}));
        table.put("isNotNull", List.<Class<?>[]>of(new Class<?>[]{boolean.class}));
        // eq(Object) / eq(Object, boolean) / eq(Object, BooleanSupplier)
        table.put("eq", List.<Class<?>[]>of(
                new Class<?>[]{Object.class},
                new Class<?>[]{Object.class, boolean.class},
                new Class<?>[]{Object.class, BooleanSupplier.class}));
        // between(Object, Object) / between(Object, Object, boolean)
        table.put("between", List.<Class<?>[]>of(
                new Class<?>[]{Object.class, Object.class},
                new Class<?>[]{Object.class, Object.class, boolean.class}));
        // where(QueryCondition) / where(Consumer) —— 函数式接口重载必须保持 lambda
        table.put("where", List.<Class<?>[]>of(
                new Class<?>[]{String.class},
                new Class<?>[]{Consumer.class}));
        // in(Object...) 变参
        table.put("in", List.<Class<?>[]>of(new Class<?>[]{Object[].class}));
        table.put("like", List.<Class<?>[]>of(new Class<?>[]{Object.class}, new Class<?>[]{Object.class, boolean.class}));
        return MybatisFlexMethods.forTesting(table);
    }

    @Test
    public void shouldTurnFlagLambdaIntoBooleanWhenBooleanOverloadExists() {
        MybatisFlexMethods methods = methods();
        assertEquals(".isNull(true)", SqlPreviewChainFixer.fix(".isNull(el -> true)", methods));
        assertEquals(".isNotNull(false)", SqlPreviewChainFixer.fix(".isNotNull(el -> false)", methods));
        assertEquals(".eq(1, true)", SqlPreviewChainFixer.fix(".eq(1,el->true)", methods));
        assertEquals(".between(1, 2, true)", SqlPreviewChainFixer.fix(".between(1, 2, el -> true)", methods));
    }

    @Test
    public void shouldKeepLambdaForFunctionalInterfaceOverload() {
        MybatisFlexMethods methods = methods();
        assertEquals(".where(el -> true)", SqlPreviewChainFixer.fix(".where(el -> true)", methods));
    }

    @Test
    public void shouldFixNestedChains() {
        MybatisFlexMethods methods = methods();
        String input = "QueryWrapper.create().from(ACCOUNT).where(ACCOUNT.ID.isNull(el -> true))"
                + ".and(ACCOUNT.AGE.between(1, 2, el -> true))";
        String expected = "QueryWrapper.create().from(ACCOUNT).where(ACCOUNT.ID.isNull(true))"
                + ".and(ACCOUNT.AGE.between(1, 2, true))";
        assertEquals(expected, SqlPreviewChainFixer.fix(input, methods));
    }

    @Test
    public void shouldNotTouchStringsWithCommasOrParens() {
        MybatisFlexMethods methods = methods();
        assertEquals(".like(\"a,b\")", SqlPreviewChainFixer.fix(".like(\"a,b\")", methods));
        assertEquals(".like(\"a(b),c\")", SqlPreviewChainFixer.fix(".like(\"a(b),c\")", methods));
    }

    @Test
    public void shouldNotTouchUnknownMethods() {
        MybatisFlexMethods methods = methods();
        assertEquals(".custom(el -> true)", SqlPreviewChainFixer.fix(".custom(el -> true)", methods));
    }

    @Test
    public void shouldKeepVarargsArgument() {
        MybatisFlexMethods methods = methods();
        assertEquals(".in(new Object[]{\"?\"})", SqlPreviewChainFixer.fix(".in(new Object[]{\"?\"})", methods));
    }

    @Test
    public void shouldReturnInputWhenNoIndexAvailable() {
        assertEquals(".isNull(el -> true)", SqlPreviewChainFixer.fix(".isNull(el -> true)", null));
    }

    @Test
    public void shouldReplacePlaceholderSetValueWithNull() {
        MybatisFlexMethods methods = methods();
        assertEquals(".set(IMAGE_USER.LAST_REQUEST_TIME, null)",
                SqlPreviewChainFixer.fix(".set(IMAGE_USER.LAST_REQUEST_TIME, \"?\")", methods));
        assertEquals("UpdateChain.of(m).set(A, null).set(B, null)",
                SqlPreviewChainFixer.fix("UpdateChain.of(m).set(A, \"?\").set(B, '?')", methods));
        // 真实值不要动
        assertEquals(".set(A, 1)", SqlPreviewChainFixer.fix(".set(A, 1)", methods));
        assertEquals(".set(A, now)", SqlPreviewChainFixer.fix(".set(A, now)", methods));
    }

    @Test
    public void splitArgumentsShouldRespectNesting() {
        assertEquals(Arrays.asList("a", "\"b,c\"", "d(e, f)"),
                SqlPreviewChainFixer.splitArguments("a, \"b,c\", d(e, f)"));
    }
}
