package cn.chuanwise.kotlinsuspendprojection.idea;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.kotlin.idea.KotlinFileType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class SuspendProjectionPsiAugmentProviderTest
        extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("""
                package kotlin.coroutines;
                public interface Continuation<T> {}
                """);
        myFixture.addClass("""
                package kotlin.jvm;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                public @interface JvmSynthetic {}
                """);
        myFixture.addClass("""
                package java.util.concurrent;
                public interface CompletionStage<T> {}
                """);
        myFixture.addClass("""
                package java.util.concurrent;
                public class CompletableFuture<T> implements CompletionStage<T> {}
                """);
        myFixture.addClass("""
                package java.util.concurrent;
                public interface Future<T> {}
                """);
        myFixture.addFileToProject(
                "cn/chuanwise/kotlinsuspendprojection/annotations/JvmSuspendProjection.kt",
                """
                package cn.chuanwise.kotlinsuspendprojection.annotations

                enum class JvmProjectionType {
                    BLOCKING,
                    COMPLETION_STAGE,
                    COMPLETABLE_FUTURE,
                    FUTURE,
                }

                @Target(AnnotationTarget.FILE, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
                @Retention(AnnotationRetention.BINARY)
                annotation class JvmSuspendProjection(
                    vararg val projections: JvmProjectionType,
                    val enable: Boolean = true,
                )
                """
        );
    }

    public void testJavaSeesDefaultBlockingProjectionBeforeCompilation() {
        addService("""
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                interface Text

                interface Service<R : Text> {
                    @JvmSuspendProjection
                    suspend fun <T : R> load(value: T): T?
                }
                """);

        PsiMethod projected = findProjectedMethod("load", 1);
        assertEquals("T", projected.getReturnType().getCanonicalText());
        assertEquals("T", projected.getParameterList().getParameters()[0].getType().getCanonicalText());
        assertEquals(findService(), projected.getContainingClass());

        myFixture.configureByText(
                "Impl.java",
                """
                import sample.Service;
                import sample.Text;

                final class Impl implements Service<Text> {
                    public <T extends Text> T load(T value) {
                        return value;
                    }
                }
                """
        );
        myFixture.checkHighlighting();
    }

    public void testClassAndFunctionProjectionPoliciesAreApplied() {
        addService("""
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType
                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                @JvmSuspendProjection(
                    JvmProjectionType.COMPLETION_STAGE,
                    JvmProjectionType.COMPLETABLE_FUTURE,
                    JvmProjectionType.FUTURE,
                )
                interface Service<R> {
                    suspend fun inherited(value: R): R

                    @JvmSuspendProjection(JvmProjectionType.BLOCKING)
                    suspend fun blocking(value: R): R

                    @JvmSuspendProjection(enable = false)
                    suspend fun disabled(value: R): R
                }
                """);

        PsiClass service = findService();
        Set<String> oneArgumentMethods = Arrays.stream(service.getMethods())
                .filter(method -> method.getParameterList().getParametersCount() == 1)
                .map(PsiMethod::getName)
                .collect(Collectors.toSet());

        assertTrue(oneArgumentMethods.contains("inheritedCompletionStage"));
        assertTrue(oneArgumentMethods.contains("inheritedCompletableFuture"));
        assertTrue(oneArgumentMethods.contains("inheritedFuture"));
        assertTrue(oneArgumentMethods.contains("blocking"));
        assertFalse(oneArgumentMethods.contains("disabledCompletionStage"));
        assertFalse(oneArgumentMethods.contains("disabledCompletableFuture"));
        assertFalse(oneArgumentMethods.contains("disabledFuture"));
    }

    public void testJavaCanResolveAsyncProjectionButKotlinCompletionDoesNotOfferIt() {
        addService("""
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType
                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                interface Service {
                    @JvmSuspendProjection(JvmProjectionType.COMPLETION_STAGE)
                    suspend fun load(value: String): String
                }
                """);

        myFixture.configureByText(
                "Use.java",
                """
                import sample.Service;

                final class Use {
                    void use(Service service) {
                        service.loadCompletionStage("value");
                    }
                }
                """
        );
        myFixture.checkHighlighting();
        assertEquals(
                "loadCompletionStage",
                findProjectedMethod("loadCompletionStage", 1).getName()
        );

        myFixture.configureByText(
                KotlinFileType.INSTANCE,
                """
                package sample

                fun use(service: Service) {
                    service.loadC<caret>
                }
                """
        );
        LookupElement[] variants = myFixture.completeBasic();
        if (variants != null) {
            assertFalse(Arrays.stream(variants)
                    .map(LookupElement::getLookupString)
                    .anyMatch("loadCompletionStage"::equals));
        }
    }

    public void testReceiverAndValueClassJvmTypesComeFromTheKotlinLightMethod() {
        myFixture.addFileToProject(
                "sample/ReceiverService.kt",
                """
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                @JvmInline
                value class UserId(val value: String)

                interface ReceiverService<R : CharSequence> {
                    @JvmSuspendProjection
                    suspend fun <T : R> T.lookup(id: UserId): T
                }
                """
        );

        PsiClass service = JavaPsiFacade.getInstance(getProject()).findClass(
                "sample.ReceiverService",
                GlobalSearchScope.projectScope(getProject())
        );
        assertNotNull(service);
        PsiMethod projected = Arrays.stream(service.findMethodsByName("lookup", false))
                .filter(method -> method.getParameterList().getParametersCount() == 2)
                .findFirst()
                .orElseThrow();

        assertEquals("T", projected.getParameterList().getParameters()[0]
                .getType().getCanonicalText());
        assertEquals("java.lang.String", projected.getParameterList().getParameters()[1]
                .getType().getCanonicalText());
        assertEquals("T", projected.getReturnType().getCanonicalText());
    }

    public void testUnrelatedAbstractMethodIsStillReportedForJavaImplementation() {
        addService("""
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                interface Value

                interface Service {
                    @JvmSuspendProjection
                    suspend fun load(value: Value): Value

                    fun required()
                }
                """);

        myFixture.configureByText(
                "Incomplete.java",
                """
                import sample.Service;
                import sample.Value;

                final class Incomplete implements Service {
                    public Value load(Value value) {
                        return value;
                    }
                }
                """
        );

        List<HighlightInfo> errors = myFixture.doHighlighting().stream()
                .filter(info -> info.getSeverity().getName().equals("ERROR"))
                .toList();
        assertTrue(errors.toString(), errors.stream()
                .map(HighlightInfo::getDescription)
                .anyMatch(description -> description != null
                        && description.contains("must either be declared abstract")));
    }

    private void addService(String source) {
        myFixture.addFileToProject("sample/Service.kt", source);
    }

    private PsiMethod findProjectedMethod(String name, int parameterCount) {
        PsiClass service = findService();
        return Arrays.stream(service.findMethodsByName(name, false))
                .filter(method -> method.getParameterList().getParametersCount() == parameterCount)
                .findFirst()
                .orElseThrow(() -> new AssertionError(describe(service)));
    }

    private PsiClass findService() {
        PsiClass service = JavaPsiFacade.getInstance(getProject()).findClass(
                "sample.Service",
                GlobalSearchScope.projectScope(getProject())
        );
        assertNotNull(service);
        assertTrue(
                PsiAugmentProvider.EP_NAME.getExtensionList().stream()
                        .anyMatch(SuspendProjectionPsiAugmentProvider.class::isInstance)
        );
        return service;
    }

    private static String describe(PsiClass service) {
        String methods = service instanceof PsiExtensibleClass extensible
                ? extensible.getOwnMethods().stream()
                    .map(method -> method.getName() + "/"
                            + method.getParameterList().getParametersCount() + "/"
                            + Arrays.stream(method.getParameterList().getParameters())
                                .map(parameter -> parameter.getType().getCanonicalText())
                                .collect(Collectors.joining("|")) + "/"
                            + method.getNavigationElement().getClass().getName())
                    .collect(Collectors.joining(", "))
                : "not extensible: " + service.getClass().getName();
        return "class=" + service.getClass().getName() + ", ownMethods=" + methods;
    }
}
