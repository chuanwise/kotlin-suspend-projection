package dev.suspendprojection.compiler

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
    private val enforcement: DirectImplementationEnforcement,
    private val emitCompatibilityGuard: Boolean,
    private val emitInvalidPathGuard: Boolean,
) {
    private val jvmSyntheticClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmSynthetic"))
    private val jvmNameClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmName"))
    private val projectionMetaClassId =
        ClassId.topLevel(FqName("dev.suspendprojection.annotations.SuspendProjectionMeta"))
    private val blockingAwaitCallableId = CallableId(
        packageName = FqName("dev.suspendprojection.runtime.jvm"),
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
            .forEach { owner ->
            when (enforcement) {
                DirectImplementationEnforcement.GUARDED_DEFAULT ->
                    generateGuardedInterface(owner)

                DirectImplementationEnforcement.STRICT ->
                    generateStrictInterface(owner)
            }
        }

        if (enforcement == DirectImplementationEnforcement.STRICT) {
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

    private fun generateGuardedInterface(owner: IrClass) {
        eligibleSuspendFunctions(owner).forEach { original ->
            hideRawSuspendAbiFromJava(original)
            val directMethod = createExportBridge(owner, original)
            owner.declarations += directMethod.also { bridge ->
                addGeneratedMetadata(
                    generated = bridge,
                    owner = owner,
                    original = original,
                    direction = "exports",
                    flags = FLAG_SAME_NAME,
                )
            }
            addCanonicalImportDefault(original, directMethod)
        }
    }

    private fun generateStrictInterface(owner: IrClass) {
        eligibleSuspendFunctions(owner).forEach { original ->
            hideRawSuspendAbiFromJava(original)
            val directMethod = createExportBridge(owner, original, isAbstract = true)
            owner.declarations += directMethod
            addGeneratedMetadata(
                generated = directMethod,
                owner = owner,
                original = original,
                direction = "bidirectional",
                flags = FLAG_SAME_NAME or FLAG_DIRECT_IMPLEMENTATION or FLAG_ABSTRACT_CONTRACT,
            )
            addCanonicalImportDefault(original, directMethod)
        }
    }

    private fun generateStrictKotlinImplementation(owner: IrClass) {
        eligibleSuspendFunctions(owner)
            .filter(::overridesStrictProjectedFunction)
            .forEach { original ->
                hideRawSuspendAbiFromJava(original)
                owner.declarations += createExportBridge(owner, original).also { bridge ->
                    addGeneratedMetadata(
                        generated = bridge,
                        owner = owner,
                        original = original,
                        direction = "exports",
                        flags = FLAG_SAME_NAME or FLAG_DIRECT_IMPLEMENTATION,
                    )
                }
            }
    }

    private fun eligibleSuspendFunctions(owner: IrClass): List<IrSimpleFunction> =
        owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter {
                it.isSuspend &&
                    it.visibility == DescriptorVisibilities.PUBLIC
            }
            .toList()

    private fun overridesStrictProjectedFunction(function: IrSimpleFunction): Boolean =
        function.overriddenSymbols.any { overridden ->
            val parent = overridden.owner.parent as? IrClass ?: return@any false
            parent.isInterface && parent.declarations
                .filterIsInstance<IrSimpleFunction>()
                .any { candidate ->
                    !candidate.isSuspend &&
                        candidate.name == overridden.owner.name &&
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
        isAbstract: Boolean = false,
    ): IrSimpleFunction {
        return pluginContext.irFactory.buildFun {
            startOffset = SYNTHETIC_OFFSET
            endOffset = SYNTHETIC_OFFSET
            origin = IrDeclarationOrigin.DEFINED
            name = original.name
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
                addJvmName(this, original.name.asString())
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
                    emitCompatibilityGuard,
                )
                arguments[3] = IrConstImpl.boolean(
                    startOffset,
                    endOffset,
                    blockingAwait.owner.parameters[3].type,
                    emitInvalidPathGuard,
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

    private companion object {
        const val FLAG_SAME_NAME: Int = 1
        const val FLAG_DIRECT_IMPLEMENTATION: Int = 1 shl 1
        const val FLAG_ABSTRACT_CONTRACT: Int = 1 shl 2
    }
}
