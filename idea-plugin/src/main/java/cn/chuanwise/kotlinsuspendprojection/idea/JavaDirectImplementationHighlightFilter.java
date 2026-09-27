package cn.chuanwise.kotlinsuspendprojection.idea;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.codeInsight.generation.OverrideImplementExploreUtil;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.asJava.classes.KtLightClass;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.util.Collection;

/** Removes the Java editor false positive caused by the compiler-generated suspend default bridge. */
public final class JavaDirectImplementationHighlightFilter implements HighlightInfoFilter, DumbAware {
    private static final String IMPLEMENT_METHODS_FIX =
            "com.intellij.codeInsight.daemon.impl.quickfix.ImplementMethodsFix";

    @Override
    public boolean accept(@NotNull HighlightInfo info, PsiFile file) {
        if (file == null
                || info.getSeverity() != HighlightSeverity.ERROR
                || !hasImplementMethodsFix(info)) {
            return true;
        }

        PsiElement leaf = file.findElementAt(info.getStartOffset());
        PsiClass implementation = PsiTreeUtil.getParentOfType(leaf, PsiClass.class, false);
        if (implementation == null || implementation.hasModifierProperty(PsiModifier.ABSTRACT)) {
            return true;
        }

        Collection<MethodSignature> missing = OverrideImplementExploreUtil
                .getMethodSignaturesToImplement(implementation);
        return missing.isEmpty() || !missing.stream()
                .allMatch(signature -> isCoveredByDirectProjection(implementation, signature));
    }

    private static boolean hasImplementMethodsFix(@NotNull HighlightInfo info) {
        Boolean found = info.findRegisteredQuickFix((descriptor, range) ->
                IMPLEMENT_METHODS_FIX.equals(descriptor.getAction().getClass().getName())
                        ? Boolean.TRUE
                        : null);
        return Boolean.TRUE.equals(found);
    }

    private static boolean isCoveredByDirectProjection(
            @NotNull PsiClass implementation,
            @NotNull MethodSignature missingSignature
    ) {
        for (PsiClass superClass : implementation.getSupers()) {
            for (PsiMethod method : superClass.findMethodsByName(missingSignature.getName(), true)) {
                if (matches(missingSignature, method)
                        && isCoveredByDirectProjection(implementation, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(
            @NotNull MethodSignature signature,
            @NotNull PsiMethod method
    ) {
        PsiType[] expected = signature.getParameterTypes();
        if (expected.length != method.getParameterList().getParametersCount()) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            PsiType actual = method.getParameterList().getParameters()[index].getType();
            if (!expected[index].getCanonicalText().equals(actual.getCanonicalText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCoveredByDirectProjection(
            @NotNull PsiClass implementation,
            @NotNull PsiMethod missingMethod
    ) {
        if (!(missingMethod.getContainingClass() instanceof KtLightClass lightClass)
                || !(lightClass.getKotlinOrigin() instanceof KtClass ktClass)
                || !ktClass.isInterface()
                || !SuspendProjectionPsiAugmentProvider.hasContinuationParameter(missingMethod)) {
            return false;
        }

        KtNamedFunction function = SuspendProjectionPsiAugmentProvider.kotlinOrigin(missingMethod);
        if (function == null
                || !function.hasModifier(KtTokens.SUSPEND_KEYWORD)
                || !ProjectionAnnotationPolicy.isSelected(function, ktClass)
                || !ProjectionAnnotationPolicy.projections(function, ktClass)
                    .contains(ProjectionKind.BLOCKING)) {
            return false;
        }

        String name = function.getName();
        if (name == null) {
            return false;
        }
        int parameterCount = missingMethod.getParameterList().getParametersCount() - 1;
        for (PsiMethod method : implementation.findMethodsByName(name, false)) {
            if (!method.hasModifierProperty(PsiModifier.ABSTRACT)
                    && method.getParameterList().getParametersCount() == parameterCount) {
                return true;
            }
        }
        return false;
    }
}
