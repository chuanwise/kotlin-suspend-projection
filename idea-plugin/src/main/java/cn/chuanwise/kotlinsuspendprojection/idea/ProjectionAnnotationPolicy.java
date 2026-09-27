package cn.chuanwise.kotlinsuspendprojection.idea;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtModifierListOwner;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.ValueArgument;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProjectionAnnotationPolicy {
    private static final String ANNOTATION_FQ_NAME =
            "cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection";
    private static final String ANNOTATION_SHORT_NAME = "JvmSuspendProjection";
    private static final Pattern PROJECTION_NAME = Pattern.compile(
            "(?:JvmProjectionType\\.)?(BLOCKING|COMPLETION_STAGE|COMPLETABLE_FUTURE|FUTURE)\\b"
    );
    private static final Pattern ENABLE_FALSE = Pattern.compile("\\benable\\s*=\\s*false\\b");

    private ProjectionAnnotationPolicy() {
    }

    static boolean isSelected(@NotNull KtNamedFunction function, @NotNull KtClassOrObject owner) {
        KtFile file = function.getContainingKtFile();
        Directive directive = directive(function, file);
        if (directive == null) {
            directive = directive(owner, file);
        }
        if (directive == null) {
            directive = directive(file.getAnnotationEntries(), file);
        }
        return directive != null && directive.enable;
    }

    static @NotNull Set<ProjectionKind> projections(
            @NotNull KtNamedFunction function,
            @NotNull KtClassOrObject owner
    ) {
        KtFile file = function.getContainingKtFile();
        Set<ProjectionKind> projections = explicitProjections(function, file);
        if (projections == null) {
            projections = explicitProjections(owner, file);
        }
        if (projections == null) {
            projections = explicitProjections(file.getAnnotationEntries(), file);
        }
        return projections == null ? EnumSet.of(ProjectionKind.BLOCKING) : projections;
    }

    private static @Nullable Directive directive(
            @NotNull KtModifierListOwner owner,
            @NotNull KtFile file
    ) {
        return directive(owner.getAnnotationEntries(), file);
    }

    private static @Nullable Directive directive(
            @NotNull List<KtAnnotationEntry> entries,
            @NotNull KtFile file
    ) {
        KtAnnotationEntry entry = projectionAnnotation(entries, file);
        return entry == null ? null : new Directive(!ENABLE_FALSE.matcher(entry.getText()).find());
    }

    private static @Nullable Set<ProjectionKind> explicitProjections(
            @NotNull KtModifierListOwner owner,
            @NotNull KtFile file
    ) {
        return explicitProjections(owner.getAnnotationEntries(), file);
    }

    private static @Nullable Set<ProjectionKind> explicitProjections(
            @NotNull List<KtAnnotationEntry> entries,
            @NotNull KtFile file
    ) {
        KtAnnotationEntry entry = projectionAnnotation(entries, file);
        if (entry == null) {
            return null;
        }

        EnumSet<ProjectionKind> result = EnumSet.noneOf(ProjectionKind.class);
        for (ValueArgument argument : entry.getValueArguments()) {
            if (argument.getArgumentName() != null
                    && "enable".equals(argument.getArgumentName().getAsName().asString())) {
                continue;
            }
            if (argument.getArgumentExpression() == null) {
                continue;
            }
            Matcher matcher = PROJECTION_NAME.matcher(argument.getArgumentExpression().getText());
            while (matcher.find()) {
                result.add(ProjectionKind.valueOf(matcher.group(1)));
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static @Nullable KtAnnotationEntry projectionAnnotation(
            @NotNull List<KtAnnotationEntry> entries,
            @NotNull KtFile file
    ) {
        for (KtAnnotationEntry entry : entries) {
            String shortName = entry.getShortName() == null ? null : entry.getShortName().asString();
            if (ANNOTATION_SHORT_NAME.equals(shortName)
                    || ANNOTATION_FQ_NAME.equals(entry.getTypeReference() == null
                    ? null
                    : entry.getTypeReference().getText())
                    || isImportedAlias(shortName, file)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isImportedAlias(@Nullable String shortName, @NotNull KtFile file) {
        if (shortName == null) {
            return false;
        }
        for (KtImportDirective directive : file.getImportDirectives()) {
            FqName imported = directive.getImportedFqName();
            if (imported != null
                    && ANNOTATION_FQ_NAME.equals(imported.asString())
                    && shortName.equals(directive.getAliasName())) {
                return true;
            }
        }
        return false;
    }

    private record Directive(boolean enable) {
    }
}
