package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirTypeParameterRef
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutorByMap
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class SuspendProjectionFirExtensionRegistrar(
    private val configuration: SuspendProjectionPluginConfiguration,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +FirDeclarationGenerationExtension.Factory { session ->
            SuspendProjectionFirDeclarationGenerator(session, configuration)
        }
    }
}

@OptIn(SymbolInternals::class)
internal class SuspendProjectionFirDeclarationGenerator(
    session: FirSession,
    private val configuration: SuspendProjectionPluginConfiguration,
) : FirDeclarationGenerationExtension(session) {
    private val projectionsName: Name = Name.identifier(configuration.generatedTypesNamespace)

    override fun getNestedClassifiersNames(
        classSymbol: FirClassSymbol<*>,
        context: NestedClassGenerationContext,
    ): Set<Name> = when {
        configuration.enabledImports.isNotEmpty() && classSymbol.isEligibleProjectionOwner() ->
            setOf(projectionsName)
        classSymbol.isGeneratedProjectionsNamespace() ->
            configuration.enabledImports.mapTo(linkedSetOf()) { it.viaName() }
        else -> emptySet()
    }

    override fun generateNestedClassLikeDeclaration(
        owner: FirClassSymbol<*>,
        name: Name,
        context: NestedClassGenerationContext,
    ): FirClassLikeSymbol<*>? = when {
        name == projectionsName && owner.isEligibleProjectionOwner() ->
            createNestedClass(
                owner,
                projectionsName,
                SuspendProjectionGeneratedDeclarationKey,
                ClassKind.INTERFACE,
            ) {
                modality = Modality.ABSTRACT
            }.symbol

        owner.isGeneratedProjectionsNamespace() &&
            configuration.enabledImports.any { it.viaName() == name } -> {
            val canonicalOwner = canonicalOwner(owner) ?: return null
            val canonicalTypeParameters = canonicalOwner.fir.typeParameters.map { it.symbol }
            createNestedClass(
                owner,
                name,
                SuspendProjectionGeneratedDeclarationKey,
                ClassKind.INTERFACE,
            ) {
                modality = Modality.ABSTRACT
                canonicalTypeParameters.forEach { typeParameter ->
                    typeParameter(
                        typeParameter.name,
                        typeParameter.variance,
                        typeParameter.isReified,
                    ) {
                        typeParameter.resolvedBounds.forEach { bound ->
                            bound { generatedTypeParameters ->
                                substituteType(
                                    bound.coneType,
                                    canonicalTypeParameters,
                                    generatedTypeParameters,
                                )
                            }
                        }
                    }
                }
                superType { generatedTypeParameters ->
                    canonicalOwner.constructType(
                        generatedTypeParameters.map { it.symbol.defaultType }.toTypedArray(),
                        false,
                    )
                }
            }.symbol
        }

        else -> null
    }

    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext,
    ): Set<Name> {
        val projection = classSymbol.generatedProjection() ?: return emptySet()
        return canonicalFunctions(classSymbol).flatMapTo(linkedSetOf()) { function ->
            listOf(function.name, projection.namedFunction(function.name))
        }
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?,
    ): List<FirNamedFunctionSymbol> {
        val owner = context?.owner ?: return emptyList()
        val projection = owner.generatedProjection() ?: return emptyList()
        val originals = canonicalFunctions(owner).filter { function ->
            function.name == callableId.callableName ||
                projection.namedFunction(function.name) == callableId.callableName
        }

        return originals.map { original ->
            createMemberFunction(
                owner,
                SuspendProjectionGeneratedDeclarationKey,
                callableId.callableName,
                { generatedTypeParameters ->
                    val canonicalReturnType = original.substituteType(
                        original.resolvedReturnType,
                        owner,
                        generatedTypeParameters,
                    )
                    if (callableId.callableName == original.name) {
                        canonicalReturnType
                    } else {
                        projection.wrap(session, canonicalReturnType)
                    }
                },
            ) {
                visibility = Visibilities.Public
                original.ownTypeParameterSymbols.forEach { typeParameter ->
                    typeParameter(
                        typeParameter.name,
                        typeParameter.variance,
                        typeParameter.isReified,
                    ) {
                        typeParameter.resolvedBounds.forEach { bound ->
                            bound { generatedTypeParameters ->
                                original.substituteType(
                                    bound.coneType,
                                    owner,
                                    generatedTypeParameters,
                                )
                            }
                        }
                    }
                }
                original.receiverParameterSymbol?.let { receiver ->
                    extensionReceiverType { generatedTypeParameters ->
                        original.substituteType(receiver.resolvedType, owner, generatedTypeParameters)
                    }
                }
                original.contextParameterSymbols.forEach { parameter ->
                    contextReceiver { generatedTypeParameters ->
                        original.substituteType(
                            parameter.resolvedReturnType,
                            owner,
                            generatedTypeParameters,
                        )
                    }
                }
                original.valueParameterSymbols.forEach { parameter ->
                    valueParameter(parameter.name, typeProvider = { generatedTypeParameters ->
                        original.substituteType(
                            parameter.resolvedReturnType,
                            owner,
                            generatedTypeParameters,
                        )
                    })
                }
                if (callableId.callableName == original.name) {
                    modality = Modality.OPEN
                    status {
                        isSuspend = true
                        isOverride = true
                    }
                    withGeneratedDefaultBody()
                } else {
                    modality = Modality.ABSTRACT
                }
            }.symbol
        }
    }

    private fun FirClassSymbol<*>.isEligibleProjectionOwner(): Boolean =
        classKind == ClassKind.INTERFACE &&
            rawStatus.visibility == Visibilities.Public &&
            projectionsName !in classId.relativeClassName.pathSegments() &&
            canonicalFunctions(this).isNotEmpty()

    private fun FirClassSymbol<*>.isGeneratedProjectionsNamespace(): Boolean =
        name == projectionsName && canonicalOwner(this)?.isEligibleProjectionOwner() == true

    private fun FirClassSymbol<*>.generatedProjection(): JvmProjection? {
        if (classId.outerClassId?.shortClassName != projectionsName) return null
        return configuration.enabledImports.singleOrNull { it.viaName() == name }
    }

    private fun canonicalOwner(symbol: FirClassSymbol<*>): FirClassSymbol<*>? {
        val ownerId = symbol.classId.outerClassId ?: return null
        val canonicalId = if (symbol.generatedProjection() != null) ownerId.outerClassId else ownerId
        return canonicalId?.let { session.symbolProvider.getClassLikeSymbolByClassId(it) as? FirClassSymbol<*> }
    }

    @OptIn(DirectDeclarationsAccess::class)
    private fun canonicalFunctions(symbol: FirClassSymbol<*>): List<FirNamedFunctionSymbol> {
        val canonical = if (symbol.generatedProjection() != null) canonicalOwner(symbol) else symbol
        return canonical?.declarationSymbols
            ?.filterIsInstance<FirNamedFunctionSymbol>()
            ?.filter { function ->
                function.rawStatus.isSuspend &&
                    function.rawStatus.visibility == Visibilities.Public &&
                    function.isSelected(canonical)
            }
            .orEmpty()
    }

    private fun FirNamedFunctionSymbol.isSelected(owner: FirClassSymbol<*>): Boolean =
        configuration.selectionMode == SelectionMode.ALL ||
            hasSuspendProjectionAnnotation() ||
            owner.hasSuspendProjectionAnnotation()

    private fun FirBasedSymbol<*>.hasSuspendProjectionAnnotation(): Boolean =
        resolvedAnnotationsWithArguments.any { annotation ->
            annotation.annotationTypeRef.coneTypeOrNull?.classId == SUSPEND_PROJECTION_CLASS_ID
        }

    private fun FirNamedFunctionSymbol.substituteType(
        type: ConeKotlinType,
        generatedOwner: FirClassSymbol<*>,
        generatedTypeParameters: List<FirTypeParameterRef>,
    ): ConeKotlinType {
        val canonicalOwner = canonicalOwner(generatedOwner)
        val ownerSubstitution = canonicalOwner?.fir?.typeParameters.orEmpty()
            .zip(generatedOwner.fir.typeParameters)
            .associate { (original, generated) -> original.symbol to generated.symbol.defaultType }
        val functionSubstitution = ownTypeParameterSymbols.zip(generatedTypeParameters)
            .associate { (original, generated) -> original to generated.symbol.defaultType }
        val substitution = ownerSubstitution + functionSubstitution
        return ConeSubstitutorByMap.create(substitution, session, false).substituteOrSelf(type)
    }

    private fun substituteType(
        type: ConeKotlinType,
        originalTypeParameters: List<org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol>,
        generatedTypeParameters: List<FirTypeParameterRef>,
    ): ConeKotlinType {
        val substitution = originalTypeParameters.zip(generatedTypeParameters)
            .associate { (original, generated) -> original to generated.symbol.defaultType }
        return ConeSubstitutorByMap.create(substitution, session, false).substituteOrSelf(type)
    }

    private fun JvmProjection.viaName(): Name = Name.identifier(
        when (this) {
            JvmProjection.BLOCKING -> "ViaBlocking"
            JvmProjection.COMPLETION_STAGE -> "ViaCompletionStage"
            JvmProjection.COMPLETABLE_FUTURE -> "ViaCompletableFuture"
            JvmProjection.FUTURE -> "ViaFuture"
            JvmProjection.NONE -> error("NONE has no generated implementation type")
        },
    )

    private fun JvmProjection.namedFunction(name: Name): Name = Name.identifier(
        name.asString() + when (this) {
            JvmProjection.BLOCKING -> "Blocking"
            JvmProjection.COMPLETION_STAGE -> "CompletionStage"
            JvmProjection.COMPLETABLE_FUTURE -> "CompletableFuture"
            JvmProjection.FUTURE -> "Future"
            JvmProjection.NONE -> error("NONE has no projected function")
        },
    )

    private fun JvmProjection.wrap(session: FirSession, type: ConeKotlinType): ConeKotlinType {
        if (this == JvmProjection.BLOCKING) return type
        val classId = ClassId.topLevel(
            FqName(
                when (this) {
                    JvmProjection.COMPLETION_STAGE -> "java.util.concurrent.CompletionStage"
                    JvmProjection.COMPLETABLE_FUTURE -> "java.util.concurrent.CompletableFuture"
                    JvmProjection.FUTURE -> "java.util.concurrent.Future"
                    else -> error("Projection $this does not wrap a return type")
                },
            ),
        )
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId)
            ?: error("Projection return type $classId was not found")
        return symbol.constructType(arrayOf(type), false)
    }

    private companion object {
        val SUSPEND_PROJECTION_CLASS_ID: ClassId = ClassId.topLevel(
            FqName("cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjection"),
        )
    }
}
