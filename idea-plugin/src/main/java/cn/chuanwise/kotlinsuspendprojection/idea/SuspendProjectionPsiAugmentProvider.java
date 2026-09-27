package cn.chuanwise.kotlinsuspendprojection.idea;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.asJava.classes.KtLightClass;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adds compiler-plugin declarations to the Java PSI view of annotated Kotlin interfaces.
 * Kotlin resolution uses the Kotlin declarations directly and therefore never sees these augments.
 */
public final class SuspendProjectionPsiAugmentProvider extends PsiAugmentProvider implements DumbAware {
    private static final String CONTINUATION_FQ_NAME = "kotlin.coroutines.Continuation";

    @Override
    @SuppressWarnings("deprecation")
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(
            @NotNull PsiElement element,
            @NotNull Class<Psi> type
    ) {
        if (type != PsiMethod.class || !(element instanceof KtLightClass lightClass)) {
            return List.of();
        }

        KtClassOrObject origin = lightClass.getKotlinOrigin();
        if (!(origin instanceof KtClass ktClass)
                || !ktClass.isInterface()
                || !isPublic(ktClass)) {
            return List.of();
        }

        return generateMethods(type, lightClass, ktClass);
    }

    private static <Psi extends PsiElement> List<Psi> generateMethods(
            @NotNull Class<Psi> type,
            @NotNull KtLightClass lightClass,
            @NotNull KtClass ktClass
    ) {
        if (!(lightClass instanceof PsiExtensibleClass extensibleClass)) {
            return List.of();
        }
        List<PsiMethod> sourceMethods = extensibleClass.getOwnMethods();
        Set<MethodKey> existingMethods = sourceMethods.stream()
                .map(MethodKey::of)
                .collect(Collectors.toSet());

        List<PsiMethod> generated = new ArrayList<>();
        for (PsiMethod rawMethod : sourceMethods) {
            KtNamedFunction function = kotlinOrigin(rawMethod);
            if (function == null
                    || !function.hasModifier(KtTokens.SUSPEND_KEYWORD)
                    || !isPublic(function)
                    || !ProjectionAnnotationPolicy.isSelected(function, ktClass)
                    || !hasContinuationParameter(rawMethod)) {
                continue;
            }

            for (ProjectionKind projection : ProjectionAnnotationPolicy.projections(function, ktClass)) {
                PsiMethod projected = createProjectedMethod(lightClass, rawMethod, function, projection);
                if (projected != null && existingMethods.add(MethodKey.of(projected))) {
                    generated.add(projected);
                }
            }
        }
        return generated.stream().map(type::cast).collect(Collectors.toList());
    }

    private static boolean isPublic(@NotNull org.jetbrains.kotlin.psi.KtModifierListOwner owner) {
        return !owner.hasModifier(KtTokens.PRIVATE_KEYWORD)
                && !owner.hasModifier(KtTokens.PROTECTED_KEYWORD)
                && !owner.hasModifier(KtTokens.INTERNAL_KEYWORD);
    }

    static KtNamedFunction kotlinOrigin(@NotNull PsiMethod method) {
        PsiElement navigation = method.getNavigationElement();
        if (navigation instanceof KtNamedFunction function) {
            return function;
        }
        PsiElement original = method.getOriginalElement().getNavigationElement();
        return original instanceof KtNamedFunction function ? function : null;
    }

    static boolean hasContinuationParameter(@NotNull PsiMethod method) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        if (parameters.length == 0) {
            return false;
        }
        PsiType type = parameters[parameters.length - 1].getType();
        return type instanceof PsiClassType classType
                && (CONTINUATION_FQ_NAME.equals(classType.rawType().getCanonicalText())
                || classType.getCanonicalText().startsWith(CONTINUATION_FQ_NAME + "<"));
    }

    private static PsiMethod createProjectedMethod(
            @NotNull PsiClass owner,
            @NotNull PsiMethod rawMethod,
            @NotNull KtNamedFunction function,
            @NotNull ProjectionKind projection
    ) {
        String sourceName = function.getName();
        if (sourceName == null) {
            return null;
        }

        PsiType returnType = suspendReturnType(rawMethod);
        if (returnType == null) {
            return null;
        }
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(owner.getProject());
        PsiType projectedReturnType = wrapReturnType(factory, owner, projection, returnType);
        if (projectedReturnType == null) {
            return null;
        }

        LightMethodBuilder method = new LightMethodBuilder(
                owner.getManager(),
                JavaLanguage.INSTANCE,
                projection.projectedName(sourceName)
        );
        method.setContainingClass(owner);
        method.addModifiers(PsiModifier.PUBLIC, PsiModifier.ABSTRACT);
        method.setMethodReturnType(projectedReturnType);
        for (PsiTypeParameter typeParameter : rawMethod.getTypeParameters()) {
            method.addTypeParameter(typeParameter);
        }
        PsiParameter[] parameters = rawMethod.getParameterList().getParameters();
        for (int index = 0; index < parameters.length - 1; index++) {
            PsiParameter parameter = parameters[index];
            String name = parameter.getName() == null ? "arg" + index : parameter.getName();
            method.addParameter(name, parameter.getType(), parameter.isVarArgs());
        }
        return method;
    }

    private static PsiType wrapReturnType(
            @NotNull PsiElementFactory factory,
            @NotNull PsiClass owner,
            @NotNull ProjectionKind projection,
            @NotNull PsiType returnType
    ) {
        String wrapperClass = projection.wrapperClass();
        if (wrapperClass == null) {
            return returnType;
        }
        PsiClass wrapper = JavaPsiFacade.getInstance(owner.getProject())
                .findClass(wrapperClass, owner.getResolveScope());
        return wrapper == null ? null : factory.createType(wrapper, returnType);
    }

    private static PsiType suspendReturnType(@NotNull PsiMethod rawMethod) {
        PsiParameter[] parameters = rawMethod.getParameterList().getParameters();
        PsiClassType continuation = (PsiClassType) parameters[parameters.length - 1].getType();
        PsiType[] arguments = continuation.getParameters();
        if (arguments.length != 1) {
            return PsiType.getJavaLangObject(rawMethod.getManager(), rawMethod.getResolveScope());
        }
        PsiType result = arguments[0];
        if (result instanceof PsiWildcardType wildcard && wildcard.getBound() != null) {
            result = wildcard.getBound();
        }
        return result;
    }

    private record MethodKey(String name, List<String> parameterTypes) {
        static MethodKey of(@NotNull PsiMethod method) {
            List<String> parameters = java.util.Arrays.stream(method.getParameterList().getParameters())
                    .map(parameter -> parameter.getType().getCanonicalText())
                    .toList();
            return new MethodKey(method.getName(), parameters);
        }
    }
}
