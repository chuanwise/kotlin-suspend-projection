package cn.chuanwise.kotlinsuspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
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
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal class ViaBlockingProjectionIrGenerator(
    private val pluginContext: IrPluginContext,
    private val configuration: SuspendProjectionPluginConfiguration,
) {
    private val jvmSyntheticClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmSynthetic"))
    private val jvmNameClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmName"))
    private val projectionMetaClassId = ClassId.topLevel(
        FqName("cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjectionMeta"),
    )
    private val suspendProjectionClassId = ClassId.topLevel(
        FqName("cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjection"),
    )

    fun generate(module: IrModuleFragment) {
        module.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach(::visitClass)
        }
    }

    private fun visitClass(owner: IrClass) {
        val projection = owner.generatedProjection()
        if (projection != null) {
            fillImportBodies(owner, projection)
            fillStrictDirectImplementationDefaults(owner, projection)
        } else if (owner.isInterface) {
            materializeMissingProjections(owner)
        }
        owner.declarations.filterIsInstance<IrClass>().forEach(::visitClass)
    }

    private fun fillStrictDirectImplementationDefaults(
        owner: IrClass,
        projection: JvmProjection,
    ) {
        val directProjection = configuration.directImplementation
        if (directProjection == JvmProjection.NONE) return
        if (configuration.directImplementationEnforcement != DirectImplementationEnforcement.STRICT) return

        val canonicalOwner = ((owner.parent as IrClass).parent as IrClass)
        owner.declarations.filterIsInstance<IrSimpleFunction>()
            .filter { it.isSuspend && it.isGeneratedByProjectionPlugin() }
            .forEach { canonical ->
                val original = canonical.overriddenSymbols.singleOrNull()?.owner
                    ?: error("Generated ${projection.viaTypeName} method has no canonical override")
                val direct = canonicalOwner.declarations.filterIsInstance<IrSimpleFunction>()
                    .singleOrNull { candidate ->
                        !candidate.isSuspend &&
                            candidate.name == directMethodName(original, directProjection) &&
                            normalizedRegularParameterTypes(candidate) ==
                            normalizedRegularParameterTypes(original)
                    }
                    ?: error("No strict direct projection matches ${original.render()}")
                val projected = findProjectedMethod(owner, canonical, projection)
                owner.declarations += if (projection == directProjection) {
                    createDirectImplementationDefault(
                        owner,
                        canonicalOwner,
                        projected,
                        direct,
                    )
                } else {
                    createAdaptedDirectImplementationDefault(
                        owner,
                        canonicalOwner,
                        canonical,
                        direct,
                        directProjection,
                    )
                }
            }
    }

    private fun createDirectImplementationDefault(
        owner: IrClass,
        canonicalOwner: IrClass,
        projected: IrSimpleFunction,
        direct: IrSimpleFunction,
    ): IrSimpleFunction = pluginContext.irFactory.buildFun {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        origin = IrDeclarationOrigin.GeneratedByPlugin(SuspendProjectionGeneratedDeclarationKey)
        name = direct.name
        returnType = projected.returnType
        modality = Modality.OPEN
        visibility = DescriptorVisibilities.PUBLIC
        isSuspend = false
    }.apply bridge@{
        parent = owner
        copyFunctionShape(projected, owner, owner, this@bridge)
        overriddenSymbols = listOf(direct.symbol)
        body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
            +irReturn(
                irCall(projected.symbol).apply {
                    this@bridge.typeParameters.forEachIndexed { index, parameter ->
                        typeArguments[index] = parameter.typeParameterDefaultType
                    }
                    this@bridge.parameters.forEachIndexed { index, parameter ->
                        arguments[index] = irGet(parameter)
                    }
                },
            )
        }
        if (projected.usesValueClassInSignature()) addJvmName(this, direct.name.asString())
    }

    private fun createAdaptedDirectImplementationDefault(
        owner: IrClass,
        canonicalOwner: IrClass,
        canonical: IrSimpleFunction,
        direct: IrSimpleFunction,
        projection: JvmProjection,
    ): IrSimpleFunction = pluginContext.irFactory.buildFun {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        origin = IrDeclarationOrigin.GeneratedByPlugin(SuspendProjectionGeneratedDeclarationKey)
        name = direct.name
        returnType = direct.returnType
        modality = Modality.OPEN
        visibility = DescriptorVisibilities.PUBLIC
        isSuspend = false
    }.apply bridge@{
        parent = owner
        copyFunctionShape(direct, canonicalOwner, owner, this@bridge)
        overriddenSymbols = listOf(direct.symbol)
        body = createDirectExportBody(owner, canonical, this@bridge, projection)
        if (direct.usesValueClassInSignature()) addJvmName(this, direct.name.asString())
    }

    private fun materializeMissingProjections(owner: IrClass) {
        if (owner.visibility != DescriptorVisibilities.PUBLIC) return
        val originals = eligibleSuspendFunctions(owner)
        if (originals.isEmpty()) return
        if (owner.declarations.filterIsInstance<IrClass>().any {
                it.name.asString() == configuration.generatedTypesNamespace
            }
        ) return

        val namespace = buildGeneratedInterface(
            owner,
            configuration.generatedTypesNamespace,
            listOf(pluginContext.irBuiltIns.anyType),
        )
        configuration.enabledImports.forEach { projection ->
            val via = buildGeneratedViaInterface(namespace, owner, projection)
            originals.forEach { original ->
                val projected = buildProjectedFunction(
                    via,
                    original,
                    projection.namedFunction(original.name),
                    isSuspend = false,
                    modality = Modality.ABSTRACT,
                    projection = projection,
                )
                val canonical = buildProjectedFunction(
                    via,
                    original,
                    original.name,
                    isSuspend = true,
                    modality = Modality.OPEN,
                    projection = JvmProjection.BLOCKING,
                ).apply { overriddenSymbols = listOf(original.symbol) }
                via.declarations += projected
                via.declarations += canonical
            }
            namespace.declarations += via
        }
        owner.declarations += namespace
        pluginContext.metadataDeclarationRegistrar.registerClassAsMetadataVisible(namespace)
    }

    private fun buildGeneratedInterface(
        parent: IrClass,
        name: String,
        superTypes: List<IrType>,
    ): IrClass = pluginContext.irFactory.buildClass {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        origin = IrDeclarationOrigin.GeneratedByPlugin(SuspendProjectionGeneratedDeclarationKey)
        this.name = Name.identifier(name)
        kind = ClassKind.INTERFACE
        modality = Modality.ABSTRACT
        visibility = DescriptorVisibilities.PUBLIC
    }.apply {
        this.parent = parent
        this.superTypes = superTypes
        thisReceiver = buildReceiverParameter { type = symbol.typeWith(emptyList()) }
    }

    private fun buildGeneratedViaInterface(
        namespace: IrClass,
        canonicalOwner: IrClass,
        projection: JvmProjection,
    ): IrClass = buildGeneratedInterface(namespace, projection.viaTypeName, emptyList()).apply {
        val copied = canonicalOwner.typeParameters.map { source ->
            addTypeParameter {
                name = source.name
                variance = source.variance
                isReified = source.isReified
            }
        }
        val substitutor = IrTypeSubstitutor(
            canonicalOwner.typeParameters.map { it.symbol },
            copied.map { it.typeParameterDefaultType },
        )
        canonicalOwner.typeParameters.zip(copied).forEach { (source, target) ->
            target.superTypes = source.superTypes.map(substitutor::substitute)
        }
        superTypes = listOf(canonicalOwner.symbol.typeWith(copied.map { it.typeParameterDefaultType }))
        thisReceiver?.type = symbol.typeWith(copied.map { it.typeParameterDefaultType })
    }

    private fun buildProjectedFunction(
        owner: IrClass,
        original: IrSimpleFunction,
        name: Name,
        isSuspend: Boolean,
        modality: Modality,
        projection: JvmProjection,
    ): IrSimpleFunction = pluginContext.irFactory.buildFun {
        startOffset = SYNTHETIC_OFFSET
        endOffset = SYNTHETIC_OFFSET
        origin = IrDeclarationOrigin.GeneratedByPlugin(SuspendProjectionGeneratedDeclarationKey)
        this.name = name
        returnType = original.returnType
        this.modality = modality
        visibility = DescriptorVisibilities.PUBLIC
        this.isSuspend = isSuspend
    }.apply function@{
        parent = owner
        val copied = original.typeParameters.map { source ->
            addTypeParameter {
                this.name = source.name
                variance = source.variance
                isReified = source.isReified
            }
        }
        val canonicalOwner = ((owner.parent as IrClass).parent as IrClass)
        val substitutor = IrTypeSubstitutor(
            canonicalOwner.typeParameters.map { it.symbol } + original.typeParameters.map { it.symbol },
            owner.typeParameters.map { it.typeParameterDefaultType } +
                copied.map { it.typeParameterDefaultType },
        )
        original.typeParameters.zip(copied).forEach { (source, target) ->
            target.superTypes = source.superTypes.map(substitutor::substitute)
        }
        val canonicalReturn = substitutor.substitute(original.returnType)
        returnType = if (isSuspend) canonicalReturn else projection.wrapIrType(pluginContext, canonicalReturn)
        parameters += buildReceiverParameter {
            kind = IrParameterKind.DispatchReceiver
            type = owner.symbol.typeWith(owner.typeParameters.map { it.typeParameterDefaultType })
        }.also { it.parent = this@function }
        original.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.forEach { parameter ->
            parameters += parameter.copyTo(this@function).apply {
                type = substitutor.substitute(parameter.type)
            }
        }
        if (!isSuspend && original.usesValueClassInSignature()) addJvmName(this, name.asString())
    }

    private fun fillImportBodies(owner: IrClass, projection: JvmProjection) {
        owner.declarations.filterIsInstance<IrSimpleFunction>()
            .filter { it.isSuspend && it.isGeneratedByProjectionPlugin() }
            .forEach { canonical ->
                val projected = findProjectedMethod(owner, canonical, projection)
                canonical.body = if (projection == JvmProjection.BLOCKING) {
                    DeclarationIrBuilder(pluginContext, canonical.symbol).irBlockBody {
                        +irReturn(
                            irCall(projected.symbol).apply {
                                canonical.typeParameters.forEachIndexed { index, parameter ->
                                    typeArguments[index] = parameter.typeParameterDefaultType
                                }
                                canonical.parameters.forEachIndexed { index, parameter ->
                                    arguments[index] = irGet(parameter)
                                }
                            },
                        )
                    }
                } else {
                    createAsyncImportBody(owner, canonical, projected, projection)
                }
                hideRawSuspendAbiFromJava(canonical)
                addGeneratedMetadata(owner, canonical, projected, projection)
            }
    }

    private fun createAsyncImportBody(
        owner: IrClass,
        canonical: IrSimpleFunction,
        projected: IrSimpleFunction,
        projection: JvmProjection,
    ) = DeclarationIrBuilder(pluginContext, canonical.symbol).irBlockBody {
        val provider = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = SpecialNames.NO_NAME_PROVIDED
            visibility = DescriptorVisibilities.LOCAL
            returnType = projected.returnType
            modality = Modality.FINAL
            isSuspend = false
        }.apply {
            parent = canonical
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +irReturn(
                    irCall(projected.symbol).apply {
                        canonical.typeParameters.forEachIndexed { index, parameter ->
                            typeArguments[index] = parameter.typeParameterDefaultType
                        }
                        canonical.parameters.forEachIndexed { index, parameter ->
                            arguments[index] = irGet(parameter)
                        }
                    },
                )
            }
        }
        val expression = IrFunctionExpressionImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            pluginContext.irBuiltIns.functionN(0).typeWith(projected.returnType),
            provider,
            IrStatementOrigin.LAMBDA,
        )
        val await = pluginContext.referenceFunctions(projection.runtimeImportCallableId).singleOrNull()
            ?: error("Runtime import adapter for $projection was not found")
        +irReturn(
            irCall(await).apply {
                typeArguments[0] = canonical.returnType
                arguments[0] = IrConstImpl.int(startOffset, endOffset, await.owner.parameters[0].type, 1)
                arguments[1] = IrConstImpl.string(
                    startOffset,
                    endOffset,
                    await.owner.parameters[1].type,
                    generatedPathId(owner, canonical, projection, "imports"),
                )
                arguments[2] = IrConstImpl.boolean(startOffset, endOffset, await.owner.parameters[2].type, configuration.emitCompatibilityGuard)
                arguments[3] = IrConstImpl.boolean(startOffset, endOffset, await.owner.parameters[3].type, configuration.emitInvalidPathGuard)
                arguments[4] = expression
            },
        )
    }

    private fun findProjectedMethod(
        owner: IrClass,
        canonical: IrSimpleFunction,
        projection: JvmProjection,
    ): IrSimpleFunction {
        val name = projection.namedFunction(canonical.name)
        return owner.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull { candidate ->
            !candidate.isSuspend && candidate.name == name &&
                normalizedRegularParameterTypes(candidate) == normalizedRegularParameterTypes(canonical)
        } ?: error("No ${projection.viaTypeName} projection matches ${canonical.render()}")
    }

    private fun createDirectExportBody(
        owner: IrClass,
        canonical: IrSimpleFunction,
        bridge: IrSimpleFunction,
        projection: JvmProjection,
    ) = DeclarationIrBuilder(pluginContext, bridge.symbol).irBlockBody {
        val resultType = if (projection == JvmProjection.BLOCKING) {
            bridge.returnType
        } else {
            ((bridge.returnType as IrSimpleType).arguments.single() as IrTypeProjection).type
        }
        val suspendLambda = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = SpecialNames.NO_NAME_PROVIDED
            visibility = DescriptorVisibilities.LOCAL
            returnType = resultType
            modality = Modality.FINAL
            isSuspend = true
        }.apply {
            parent = bridge
            body = DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                +irReturn(
                    irCall(canonical.symbol).apply {
                        bridge.typeParameters.forEachIndexed { index, parameter ->
                            typeArguments[index] = parameter.typeParameterDefaultType
                        }
                        bridge.parameters.forEachIndexed { index, parameter ->
                            arguments[index] = irGet(parameter)
                        }
                    },
                )
            }
        }
        val expression = IrFunctionExpressionImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            pluginContext.irBuiltIns.suspendFunctionN(0).typeWith(resultType),
            suspendLambda,
            IrStatementOrigin.LAMBDA,
        )
        val runtimeAdapter = pluginContext.referenceFunctions(projection.runtimeExportCallableId)
            .singleOrNull() ?: error("Runtime export adapter for $projection was not found")
        +irReturn(
            irCall(runtimeAdapter).apply {
                typeArguments[0] = resultType
                arguments[0] = IrConstImpl.int(startOffset, endOffset, runtimeAdapter.owner.parameters[0].type, 1)
                arguments[1] = IrConstImpl.string(
                    startOffset,
                    endOffset,
                    runtimeAdapter.owner.parameters[1].type,
                    generatedPathId(owner, canonical, projection, "exports"),
                )
                arguments[2] = IrConstImpl.boolean(startOffset, endOffset, runtimeAdapter.owner.parameters[2].type, configuration.emitCompatibilityGuard)
                arguments[3] = IrConstImpl.boolean(startOffset, endOffset, runtimeAdapter.owner.parameters[3].type, configuration.emitInvalidPathGuard)
                arguments[4] = expression
            },
        )
    }

    private fun copyFunctionShape(
        source: IrSimpleFunction,
        sourceOwner: IrClass,
        targetOwner: IrClass,
        target: IrSimpleFunction,
    ) {
        val copied = source.typeParameters.map { parameter ->
            target.addTypeParameter {
                name = parameter.name
                variance = parameter.variance
                isReified = parameter.isReified
            }
        }
        val substitutor = IrTypeSubstitutor(
            sourceOwner.typeParameters.map { it.symbol } + source.typeParameters.map { it.symbol },
            targetOwner.typeParameters.map { it.typeParameterDefaultType } +
                copied.map { it.typeParameterDefaultType },
        )
        source.typeParameters.zip(copied).forEach { (original, replacement) ->
            replacement.superTypes = original.superTypes.map(substitutor::substitute)
        }
        target.returnType = substitutor.substitute(source.returnType)
        target.parameters = source.parameters.map { parameter ->
            parameter.copyTo(target).apply { type = substitutor.substitute(parameter.type) }
        }
    }

    private fun directMethodName(
        original: IrSimpleFunction,
        projection: JvmProjection,
    ): Name = if (configuration.sameNameCaller == projection) {
        original.name
    } else {
        projection.namedFunction(original.name)
    }

    private fun IrClass.generatedProjection(): JvmProjection? {
        if (!isGeneratedByProjectionPlugin()) return null
        return configuration.enabledImports.singleOrNull { it.viaTypeName == name.asString() }
    }

    private fun IrDeclaration.isGeneratedByProjectionPlugin(): Boolean =
        (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey ===
            SuspendProjectionGeneratedDeclarationKey

    private fun eligibleSuspendFunctions(owner: IrClass): List<IrSimpleFunction> =
        owner.declarations.filterIsInstance<IrSimpleFunction>().filter {
            it.isSuspend && it.visibility == DescriptorVisibilities.PUBLIC && it.isSelected(owner)
        }

    private fun normalizedRegularParameterTypes(function: IrSimpleFunction): List<String> {
        val indices = function.typeParameters.mapIndexed { index, parameter -> parameter.symbol to index }.toMap()
        return function.parameters.filter { it.kind == IrParameterKind.Regular }
            .map { normalizeType(it.type, indices) }
    }

    private fun normalizeType(type: IrType, indices: Map<IrTypeParameterSymbol, Int>): String {
        val simple = type as? IrSimpleType ?: return type.render()
        val classifier = when (val symbol = simple.classifier) {
            is IrTypeParameterSymbol -> "T${indices[symbol] ?: -1}"
            is IrClassSymbol -> symbol.owner.fqNameWhenAvailable?.asString() ?: symbol.owner.name.asString()
            else -> symbol.toString()
        }
        val arguments = simple.arguments.joinToString(prefix = "<", postfix = ">") { argument ->
            when (argument) {
                is IrTypeProjection -> "${argument.variance}:${normalizeType(argument.type, indices)}"
                is IrStarProjection -> "*"
            }
        }
        return "$classifier$arguments:${simple.nullability}"
    }

    private fun IrSimpleFunction.usesValueClassInSignature(): Boolean =
        returnType.containsValueClass() || parameters.any {
            it.kind != IrParameterKind.DispatchReceiver && it.type.containsValueClass()
        }

    private fun IrType.containsValueClass(): Boolean {
        val simple = this as? IrSimpleType ?: return false
        if ((simple.classifier as? IrClassSymbol)?.owner?.isValue == true) return true
        return simple.arguments.any { it is IrTypeProjection && it.type.containsValueClass() }
    }

    private fun addJvmName(function: IrSimpleFunction, jvmName: String) {
        val annotationClass = pluginContext.referenceClass(jvmNameClassId)
            ?: error("kotlin.jvm.JvmName was not found")
        val constructor = annotationClass.constructors.single()
        function.annotations += DeclarationIrBuilder(pluginContext, function.symbol)
            .irAnnotation(constructor).apply {
                val parameter = constructor.owner.parameters.single()
                arguments[parameter] = IrConstImpl.string(startOffset, endOffset, parameter.type, jvmName)
            }
    }

    private fun hideRawSuspendAbiFromJava(canonical: IrSimpleFunction) {
        if (configuration.rawSuspendAbi == RawSuspendAbi.VISIBLE) return
        if (canonical.annotations.hasAnnotation(jvmSyntheticClassId.asSingleFqName())) return
        val annotationClass = pluginContext.referenceClass(jvmSyntheticClassId)
            ?: error("kotlin.jvm.JvmSynthetic was not found")
        canonical.annotations += DeclarationIrBuilder(pluginContext, canonical.symbol)
            .irAnnotation(annotationClass.constructors.single())
    }

    private fun IrSimpleFunction.isSelected(owner: IrClass): Boolean =
        configuration.selectionMode == SelectionMode.ALL ||
            annotations.hasAnnotation(suspendProjectionClassId.asSingleFqName()) ||
            owner.annotations.hasAnnotation(suspendProjectionClassId.asSingleFqName())

    private fun addGeneratedMetadata(
        owner: IrClass,
        canonical: IrSimpleFunction,
        projected: IrSimpleFunction,
        projection: JvmProjection,
    ) {
        val annotationClass = pluginContext.referenceClass(projectionMetaClassId)
            ?: error("SuspendProjectionMeta was not found")
        val constructor = annotationClass.constructors.single()
        val origin = generatedOriginId(owner, canonical)
        val values = listOf(
            1,
            1,
            "0.1.0-SNAPSHOT",
            origin,
            projection.optionValue,
            "imports",
            origin,
            0,
            projection.cancellationCapability,
        )
        projected.annotations += DeclarationIrBuilder(pluginContext, projected.symbol)
            .irAnnotation(constructor).apply {
                constructor.owner.parameters.zip(values).forEach { (parameter, value) ->
                    arguments[parameter] = when (value) {
                        is Int -> IrConstImpl.int(startOffset, endOffset, parameter.type, value)
                        is String -> IrConstImpl.string(startOffset, endOffset, parameter.type, value)
                        else -> error("Unsupported SuspendProjectionMeta value: $value")
                    }
                }
            }
    }

    private fun generatedPathId(
        owner: IrClass,
        canonical: IrSimpleFunction,
        projection: JvmProjection,
        direction: String,
    ): String = "${generatedOriginId(owner, canonical)}:${projection.optionValue}:$direction"

    private fun generatedOriginId(owner: IrClass, canonical: IrSimpleFunction): String = buildString {
        val canonicalOwner = (owner.parent as? IrClass)?.parent as? IrClass
        append(canonicalOwner?.fqNameWhenAvailable?.asString() ?: owner.fqNameWhenAvailable)
        append('#')
        append(canonical.name.asString())
        append('(')
        canonical.parameters.asSequence().filter { it.kind == IrParameterKind.Regular }
            .joinTo(this, separator = ",") { it.type.render() }
        append(')')
    }
}
