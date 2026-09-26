package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal class SameNameBlockingProjectionGenerator(
    private val pluginContext: IrPluginContext,
    private val configuration: SuspendProjectionPluginConfiguration,
) {
    private val jvmSyntheticClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmSynthetic"))
    private val jvmNameClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmName"))
    private val projectionMetaClassId =
        ClassId.topLevel(FqName("cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjectionMeta"))
    private val suspendProjectionClassId =
        ClassId.topLevel(FqName("cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjection"))
    private val subclassOptInRequiredClassId =
        ClassId.topLevel(FqName("kotlin.SubclassOptInRequired"))
    private val uninstrumentedWarningClassId = ClassId.topLevel(
        FqName(
            "cn.chuanwise.kotlinsuspendprojection.annotations." +
                "UninstrumentedProjectionImplementationWarning",
        ),
    )
    private val uninstrumentedErrorClassId = ClassId.topLevel(
        FqName(
            "cn.chuanwise.kotlinsuspendprojection.annotations." +
                "UninstrumentedProjectionImplementationError",
        ),
    )
    private val blockingAwaitCallableId = CallableId(
        packageName = FqName("cn.chuanwise.kotlinsuspendprojection.runtime.jvm"),
        callableName = Name.identifier("awaitSuspendProjection"),
    )

    fun generate(module: IrModuleFragment) {
        val classes = buildList {
            module.files.forEach { file ->
                file.declarations.filterIsInstance<IrClass>().forEach { owner ->
                    collectClasses(owner, this)
                }
            }
        }

        classes.filter(IrClass::isInterface)
            .filter { it.visibility == DescriptorVisibilities.PUBLIC }
            .filterNot(::isProjectionGeneratedClass)
            .forEach(::generateInterface)

        if (
            configuration.directImplementation == JvmProjection.BLOCKING &&
            configuration.directImplementationEnforcement ==
            DirectImplementationEnforcement.STRICT
        ) {
            classes.filterNot(IrClass::isInterface)
                .filterNot(::isProjectionGeneratedClass)
                .forEach(::generateStrictKotlinImplementation)
        }
    }

    private fun collectClasses(owner: IrClass, destination: MutableList<IrClass>) {
        destination += owner
        owner.declarations.filterIsInstance<IrClass>().forEach { nested ->
            collectClasses(nested, destination)
        }
    }

    private fun isProjectionGeneratedClass(owner: IrClass): Boolean =
        (owner.origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey ===
            SuspendProjectionGeneratedDeclarationKey

    private fun generateInterface(owner: IrClass) {
        val eligibleFunctions = eligibleSuspendFunctions(owner)
        if (eligibleFunctions.isEmpty()) return

        addSubclassOptInRequirement(owner)
        eligibleFunctions.forEach { original ->
            hideRawSuspendAbiFromJava(original)
            val directName = directBridgeName(original)
            val bridgeFlags = linkedMapOf<Name, Int>()
            if (configuration.blockingExportsEnabled) {
                if (configuration.sameNameCaller == JvmProjection.BLOCKING) {
                    bridgeFlags[original.name] = FLAG_SAME_NAME
                }
                if (configuration.blockingEmitNamedCaller) {
                    bridgeFlags.merge(blockingName(original.name), FLAG_NAMED_CALLER, Int::or)
                }
            }
            if (directName != null) {
                bridgeFlags.merge(directName, FLAG_DIRECT_IMPLEMENTATION, Int::or)
            }

            val generated = bridgeFlags.mapValues { (name, flags) ->
                val isDirect = name == directName
                val isAbstract = isDirect &&
                    configuration.directImplementationEnforcement ==
                    DirectImplementationEnforcement.STRICT
                createExportBridge(owner, original, name, isAbstract).also { bridge ->
                    owner.declarations += bridge
                    addGeneratedMetadata(
                        generated = bridge,
                        owner = owner,
                        original = original,
                        direction = if (isDirect) "bidirectional" else "exports",
                        flags = flags or if (isAbstract) FLAG_ABSTRACT_CONTRACT else 0,
                    )
                }
            }
            directName?.let { name ->
                addCanonicalImportDefault(original, generated.getValue(name))
            }
        }
    }

    private fun addSubclassOptInRequirement(owner: IrClass) {
        if (configuration.directImplementation != JvmProjection.BLOCKING) return
        val markerClassId = when (configuration.uninstrumentedKotlin) {
            UninstrumentedKotlin.WARNING -> uninstrumentedWarningClassId
            UninstrumentedKotlin.ERROR -> uninstrumentedErrorClassId
            UninstrumentedKotlin.OFF -> return
        }
        if (owner.annotations.hasAnnotation(subclassOptInRequiredClassId.asSingleFqName())) return

        val annotationClass = pluginContext.referenceClass(subclassOptInRequiredClassId)
            ?: error("kotlin.SubclassOptInRequired was not found")
        val markerClass = pluginContext.referenceClass(markerClassId)
            ?: error("The configured uninstrumented Kotlin marker was not found")
        val constructor = annotationClass.constructors.single()
        val parameter = constructor.owner.parameters.single()
        owner.annotations += DeclarationIrBuilder(pluginContext, owner.symbol)
            .irAnnotation(constructor)
            .apply {
                arguments[parameter] = IrClassReferenceImpl(
                    startOffset,
                    endOffset,
                    parameter.type,
                    markerClass,
                    markerClass.owner.defaultType,
                )
            }
    }

    private fun generateStrictKotlinImplementation(owner: IrClass) {
        eligibleSuspendFunctions(owner)
            .filter(::overridesStrictProjectedFunction)
            .forEach { original ->
                hideRawSuspendAbiFromJava(original)
                val bridgeName = checkNotNull(directBridgeName(original))
                owner.declarations += createExportBridge(owner, original, bridgeName).also { bridge ->
                    addGeneratedMetadata(
                        generated = bridge,
                        owner = owner,
                        original = original,
                        direction = "exports",
                        flags = FLAG_DIRECT_IMPLEMENTATION or
                            if (bridgeName == original.name) FLAG_SAME_NAME else FLAG_NAMED_CALLER,
                    )
                }
            }
    }

    private fun eligibleSuspendFunctions(owner: IrClass): List<IrSimpleFunction> =
        owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter {
                it.isSuspend &&
                    it.visibility == DescriptorVisibilities.PUBLIC &&
                    it.isSelected(owner)
            }
            .toList()

    private fun overridesStrictProjectedFunction(function: IrSimpleFunction): Boolean =
        function.overriddenSymbols.any { overridden ->
            val parent = overridden.owner.parent as? IrClass ?: return@any false
            parent.isInterface && parent.declarations
                .filterIsInstance<IrSimpleFunction>()
                .any { candidate ->
                    !candidate.isSuspend &&
                        candidate.name == directBridgeName(overridden.owner) &&
                        candidate.modality == Modality.ABSTRACT &&
                        normalizedRegularParameterTypes(candidate) ==
                        normalizedRegularParameterTypes(overridden.owner)
                }
        }

    private fun normalizedRegularParameterTypes(function: IrSimpleFunction): List<String> {
        val typeParameterIndices = function.typeParameters
            .mapIndexed { index, parameter -> parameter.symbol to index }
            .toMap()
        return function.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .map { normalizeType(it.type, typeParameterIndices) }
    }

    private fun normalizeType(
        type: IrType,
        typeParameterIndices: Map<IrTypeParameterSymbol, Int>,
    ): String {
        val simple = type as? IrSimpleType ?: return type.render()
        val classifier = when (val symbol = simple.classifier) {
            is IrTypeParameterSymbol -> "T${typeParameterIndices[symbol] ?: -1}"
            is IrClassSymbol -> symbol.owner.fqNameWhenAvailable?.asString() ?: symbol.owner.name.asString()
            else -> symbol.toString()
        }
        val arguments = simple.arguments.joinToString(prefix = "<", postfix = ">") { argument ->
            when (argument) {
                is IrTypeProjection -> "${argument.variance}:${normalizeType(argument.type, typeParameterIndices)}"
                is IrStarProjection -> "*"
                else -> argument.toString()
            }
        }
        return "$classifier$arguments:${simple.nullability}"
    }

    private fun hideRawSuspendAbiFromJava(original: IrSimpleFunction) {
        if (configuration.rawSuspendAbi == RawSuspendAbi.VISIBLE) return
        if (original.annotations.hasAnnotation(jvmSyntheticClassId.asSingleFqName())) return

        val annotationClass = pluginContext.referenceClass(jvmSyntheticClassId)
            ?: error("kotlin.jvm.JvmSynthetic was not found")
        val constructor = annotationClass.constructors.single()
        original.annotations += DeclarationIrBuilder(pluginContext, original.symbol)
            .irAnnotation(constructor)
    }

    private fun createExportBridge(
        owner: IrClass,
        original: IrSimpleFunction,
        bridgeName: Name,
        isAbstract: Boolean = false,
    ): IrSimpleFunction {
        return pluginContext.irFactory.buildFun {
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            origin = IrDeclarationOrigin.DEFINED
            name = bridgeName
            returnType = original.returnType
            modality = if (isAbstract) Modality.ABSTRACT else Modality.OPEN
            visibility = DescriptorVisibilities.PUBLIC
            isSuspend = false
        }.apply bridge@{
            parent = owner
            val copiedTypeParameters = original.typeParameters.map { source ->
                addTypeParameter {
                    this.name = source.name
                    variance = source.variance
                    isReified = source.isReified
                }
            }
            val substitutor = IrTypeSubstitutor(
                owner.typeParameters.map { it.symbol } + original.typeParameters.map { it.symbol },
                owner.typeParameters.map { it.typeParameterDefaultType } +
                    copiedTypeParameters.map { it.typeParameterDefaultType },
            )
            original.typeParameters.zip(copiedTypeParameters).forEach { (source, copied) ->
                copied.superTypes = source.superTypes.map(substitutor::substitute)
            }
            returnType = substitutor.substitute(original.returnType)
            parameters = original.parameters.map { parameter ->
                parameter.copyTo(this@bridge).apply {
                    type = substitutor.substitute(parameter.type)
                }
            }
            if (!isAbstract) {
                body = createExportBody(owner, original, this@bridge)
            }
            if (original.usesValueClassInSignature()) {
                addJvmName(this, bridgeName.asString())
            }
        }
    }

    private fun IrSimpleFunction.usesValueClassInSignature(): Boolean =
        returnType.containsValueClass() ||
            parameters.any { parameter ->
                parameter.kind != IrParameterKind.DispatchReceiver &&
                    parameter.type.containsValueClass()
            }

    private fun IrType.containsValueClass(): Boolean {
        val simple = this as? IrSimpleType ?: return false
        val owner = (simple.classifier as? IrClassSymbol)?.owner
        if (owner?.isValue == true) return true
        return simple.arguments.any { argument ->
            argument is IrTypeProjection && argument.type.containsValueClass()
        }
    }

    private fun addJvmName(function: IrSimpleFunction, jvmName: String) {
        val annotationClass = pluginContext.referenceClass(jvmNameClassId)
            ?: error("kotlin.jvm.JvmName was not found")
        val constructor = annotationClass.constructors.single()
        function.annotations += DeclarationIrBuilder(pluginContext, function.symbol)
            .irAnnotation(constructor)
            .apply {
                val parameter = constructor.owner.parameters.single()
                arguments[parameter] = IrConstImpl.string(
                    startOffset,
                    endOffset,
                    parameter.type,
                    jvmName,
                )
            }
    }

    private fun createExportBody(
        owner: IrClass,
        original: IrSimpleFunction,
        bridge: IrSimpleFunction,
    ) = DeclarationIrBuilder(pluginContext, bridge.symbol).irBlockBody {
        val suspendLambda = createSuspendLambda(bridge, original)
        val lambdaType = pluginContext.irBuiltIns.suspendFunctionN(0)
            .typeWith(bridge.returnType)
        val lambdaExpression = IrFunctionExpressionImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            lambdaType,
            suspendLambda,
            IrStatementOrigin.LAMBDA,
        )
        val blockingAwait = pluginContext.referenceFunctions(blockingAwaitCallableId).singleOrNull()
            ?: error(
                "awaitSuspendProjection was not found. " +
                    "The runtime-jvm artifact must be on the producer compilation classpath.",
            )

        +irReturn(
            irCall(blockingAwait).apply {
                typeArguments[0] = bridge.returnType
                arguments[0] = IrConstImpl.int(
                    startOffset,
                    endOffset,
                    blockingAwait.owner.parameters[0].type,
                    1,
                )
                arguments[1] = IrConstImpl.string(
                    startOffset,
                    endOffset,
                    blockingAwait.owner.parameters[1].type,
                    generatedPathId(owner, original),
                )
                arguments[2] = IrConstImpl.boolean(
                    startOffset,
                    endOffset,
                    blockingAwait.owner.parameters[2].type,
                    configuration.emitCompatibilityGuard,
                )
                arguments[3] = IrConstImpl.boolean(
                    startOffset,
                    endOffset,
                    blockingAwait.owner.parameters[3].type,
                    configuration.emitInvalidPathGuard,
                )
                arguments[4] = lambdaExpression
            },
        )
    }

    private fun addCanonicalImportDefault(
        canonical: IrSimpleFunction,
        directMethod: IrSimpleFunction,
    ) {
        canonical.modality = Modality.OPEN
        canonical.body = DeclarationIrBuilder(pluginContext, canonical.symbol).irBlockBody {
            +irReturn(
                irCall(directMethod.symbol).apply {
                    canonical.typeParameters.forEachIndexed { index, typeParameter ->
                        typeArguments[index] = typeParameter.typeParameterDefaultType
                    }
                    canonical.parameters.forEachIndexed { index, parameter ->
                        arguments[index] = irGet(parameter)
                    }
                },
            )
        }
    }

    private fun createSuspendLambda(
        bridge: IrSimpleFunction,
        original: IrSimpleFunction,
    ): IrSimpleFunction = pluginContext.irFactory.buildFun {
        origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        name = SpecialNames.NO_NAME_PROVIDED
        visibility = DescriptorVisibilities.LOCAL
        returnType = bridge.returnType
        modality = Modality.FINAL
        isSuspend = true
    }.apply lambda@{
        parent = bridge
        body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
            +irReturn(
                irCall(original.symbol).apply {
                    bridge.typeParameters.forEachIndexed { index, typeParameter ->
                        typeArguments[index] = typeParameter.typeParameterDefaultType
                    }
                    bridge.parameters.forEachIndexed { index, bridgeParameter ->
                        arguments[index] = irGet(bridgeParameter)
                    }
                },
            )
        }
    }

    private fun addGeneratedMetadata(
        generated: IrSimpleFunction,
        owner: IrClass,
        original: IrSimpleFunction,
        direction: String,
        flags: Int,
    ) {
        val annotationClass = pluginContext.referenceClass(projectionMetaClassId)
            ?: error(
                "SuspendProjectionMeta was not found. " +
                    "The annotations artifact must be on the producer compilation classpath.",
            )
        val constructor = annotationClass.constructors.single()
        val origin = generatedOriginId(owner, original)
        val values = listOf(
            1,
            1,
            "0.1.0-SNAPSHOT",
            origin,
            "blocking",
            direction,
            origin,
            flags,
            "waiter-interruption-only",
        )

        generated.annotations += DeclarationIrBuilder(pluginContext, generated.symbol)
            .irAnnotation(constructor)
            .apply {
                constructor.owner.parameters.zip(values).forEach { (parameter, value) ->
                    arguments[parameter] = when (value) {
                        is Int -> IrConstImpl.int(startOffset, endOffset, parameter.type, value)
                        is String -> IrConstImpl.string(startOffset, endOffset, parameter.type, value)
                        else -> error("Unsupported SuspendProjectionMeta value: $value")
                    }
                }
            }
    }

    private fun generatedPathId(owner: IrClass, original: IrSimpleFunction): String =
        "${generatedOriginId(owner, original)}:blocking:exports"

    private fun generatedOriginId(owner: IrClass, original: IrSimpleFunction): String = buildString {
        append(owner.fqNameWhenAvailable?.asString() ?: owner.name.asString())
        append('#')
        append(original.name.asString())
        append('(')
        original.parameters
            .asSequence()
            .filter { it.kind == IrParameterKind.Regular }
            .joinTo(this, separator = ",") { it.type.render() }
        append(')')
    }

    private fun IrSimpleFunction.isSelected(owner: IrClass): Boolean =
        configuration.selectionMode == SelectionMode.ALL ||
            annotations.hasAnnotation(suspendProjectionClassId.asSingleFqName()) ||
            owner.annotations.hasAnnotation(suspendProjectionClassId.asSingleFqName())

    private fun directBridgeName(original: IrSimpleFunction): Name? =
        if (configuration.directImplementation != JvmProjection.BLOCKING) {
            null
        } else if (configuration.sameNameCaller == JvmProjection.BLOCKING) {
            original.name
        } else {
            blockingName(original.name)
        }

    private fun blockingName(name: Name): Name = Name.identifier(name.asString() + "Blocking")

    private companion object {
        const val FLAG_SAME_NAME: Int = 1
        const val FLAG_DIRECT_IMPLEMENTATION: Int = 1 shl 1
        const val FLAG_ABSTRACT_CONTRACT: Int = 1 shl 2
        const val FLAG_NAMED_CALLER: Int = 1 shl 3
    }
}
