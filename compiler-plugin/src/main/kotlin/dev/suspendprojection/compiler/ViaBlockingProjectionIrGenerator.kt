package dev.suspendprojection.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrStarProjection
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType as typeParameterDefaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class ViaBlockingProjectionIrGenerator(
    private val pluginContext: IrPluginContext,
) {
    private val jvmSyntheticClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmSynthetic"))
    private val jvmNameClassId = ClassId.topLevel(FqName("kotlin.jvm.JvmName"))
    private val projectionMetaClassId =
        ClassId.topLevel(FqName("dev.suspendprojection.annotations.SuspendProjectionMeta"))

    fun generate(module: IrModuleFragment) {
        module.files.forEach { file ->
            file.declarations.filterIsInstance<IrClass>().forEach(::visitClass)
        }
    }

    private fun visitClass(owner: IrClass) {
        if (owner.isGeneratedViaBlocking()) {
            fillImportBodies(owner)
        } else if (owner.isInterface) {
            materializeMissingProjections(owner)
        }
        owner.declarations.filterIsInstance<IrClass>().forEach(::visitClass)
    }

    private fun materializeMissingProjections(owner: IrClass) {
        if (owner.visibility != DescriptorVisibilities.PUBLIC) return
        val originals = owner.declarations.filterIsInstance<IrSimpleFunction>()
            .filter {
                it.isSuspend &&
                    it.visibility == DescriptorVisibilities.PUBLIC
            }
        if (originals.isEmpty()) return
        if (owner.declarations.filterIsInstance<IrClass>().any { it.name.asString() == "Projections" }) {
            return
        }

        val projections = buildGeneratedInterface(owner, "Projections", listOf(pluginContext.irBuiltIns.anyType))
        val viaBlocking = buildGeneratedViaBlockingInterface(projections, owner)
        originals.forEach { original ->
            val blocking = buildProjectedFunction(
                owner = viaBlocking,
                original = original,
                name = Name.identifier(original.name.asString() + "Blocking"),
                isSuspend = false,
                modality = Modality.ABSTRACT,
            )
            val canonical = buildProjectedFunction(
                owner = viaBlocking,
                original = original,
                name = original.name,
                isSuspend = true,
                modality = Modality.OPEN,
            ).apply {
                overriddenSymbols = listOf(original.symbol)
            }
            viaBlocking.declarations += blocking
            viaBlocking.declarations += canonical
        }
        projections.declarations += viaBlocking
        owner.declarations += projections
        pluginContext.metadataDeclarationRegistrar.registerClassAsMetadataVisible(projections)
    }

    private fun buildGeneratedInterface(
        parent: IrClass,
        name: String,
        superTypes: List<org.jetbrains.kotlin.ir.types.IrType>,
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
        thisReceiver = buildReceiverParameter {
            type = symbol.typeWith(emptyList())
        }
    }

    private fun buildGeneratedViaBlockingInterface(
        projections: IrClass,
        canonicalOwner: IrClass,
    ): IrClass = buildGeneratedInterface(projections, "ViaBlocking", emptyList()).apply {
        val copiedTypeParameters = canonicalOwner.typeParameters.map { source ->
            addTypeParameter {
                name = source.name
                variance = source.variance
                isReified = source.isReified
            }
        }
        val substitutor = IrTypeSubstitutor(
            canonicalOwner.typeParameters.map { it.symbol },
            copiedTypeParameters.map { it.typeParameterDefaultType },
        )
        canonicalOwner.typeParameters.zip(copiedTypeParameters).forEach { (source, copied) ->
            copied.superTypes = source.superTypes.map(substitutor::substitute)
        }
        superTypes = listOf(
            canonicalOwner.symbol.typeWith(copiedTypeParameters.map { it.typeParameterDefaultType }),
        )
        thisReceiver?.type = symbol.typeWith(copiedTypeParameters.map { it.typeParameterDefaultType })
    }

    private fun buildProjectedFunction(
        owner: IrClass,
        original: IrSimpleFunction,
        name: Name,
        isSuspend: Boolean,
        modality: Modality,
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
        val copiedTypeParameters = original.typeParameters.map { source ->
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
                copiedTypeParameters.map { it.typeParameterDefaultType },
        )
        original.typeParameters.zip(copiedTypeParameters).forEach { (source, copied) ->
            copied.superTypes = source.superTypes.map(substitutor::substitute)
        }
        returnType = substitutor.substitute(original.returnType)
        parameters += buildReceiverParameter {
            kind = IrParameterKind.DispatchReceiver
            type = owner.symbol.typeWith(owner.typeParameters.map { it.typeParameterDefaultType })
        }.also { it.parent = this@function }
        original.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.forEach { parameter ->
            parameters += parameter.copyTo(this@function).apply {
                type = substitutor.substitute(parameter.type)
            }
        }
        if (!isSuspend && original.usesValueClassInSignature()) {
            addJvmName(this, name.asString())
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

    private fun fillImportBodies(owner: IrClass) {
        owner.declarations.filterIsInstance<IrSimpleFunction>()
            .filter { it.isSuspend && it.isGeneratedByProjectionPlugin() }
            .forEach { canonical ->
                val blockingName = Name.identifier(canonical.name.asString() + "Blocking")
                val blocking = owner.declarations.filterIsInstance<IrSimpleFunction>()
                    .singleOrNull { candidate ->
                        !candidate.isSuspend &&
                            candidate.name == blockingName &&
                            normalizedRegularParameterTypes(candidate) ==
                            normalizedRegularParameterTypes(canonical)
                    }
                    ?: error(
                        buildString {
                            append("No ViaBlocking projection matches ")
                            append(canonical.render())
                            append("; candidates: ")
                            owner.declarations.filterIsInstance<IrSimpleFunction>()
                                .filter { it.name == blockingName }
                                .joinTo(this) { it.render() }
                        },
                    )
                canonical.body = DeclarationIrBuilder(pluginContext, canonical.symbol).irBlockBody {
                    +irReturn(
                        irCall(blocking.symbol).apply {
                            canonical.typeParameters.forEachIndexed { index, typeParameter ->
                                typeArguments[index] = typeParameter.typeParameterDefaultType
                            }
                            canonical.parameters.forEachIndexed { index, parameter ->
                                arguments[index] = irGet(parameter)
                            }
                        },
                    )
                }
                hideRawSuspendAbiFromJava(canonical)
                addGeneratedMetadata(owner, canonical, blocking)
            }
    }

    private fun IrClass.isGeneratedViaBlocking(): Boolean =
        name.asString() == "ViaBlocking" && isGeneratedByProjectionPlugin()

    private fun org.jetbrains.kotlin.ir.declarations.IrDeclaration.isGeneratedByProjectionPlugin(): Boolean =
        (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey ===
            SuspendProjectionGeneratedDeclarationKey

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

    private fun hideRawSuspendAbiFromJava(canonical: IrSimpleFunction) {
        if (canonical.annotations.hasAnnotation(jvmSyntheticClassId.asSingleFqName())) return
        val annotationClass = pluginContext.referenceClass(jvmSyntheticClassId)
            ?: error("kotlin.jvm.JvmSynthetic was not found")
        canonical.annotations += DeclarationIrBuilder(pluginContext, canonical.symbol)
            .irAnnotation(annotationClass.constructors.single())
    }

    private fun addGeneratedMetadata(
        owner: IrClass,
        canonical: IrSimpleFunction,
        blocking: IrSimpleFunction,
    ) {
        val annotationClass = pluginContext.referenceClass(projectionMetaClassId)
            ?: error("SuspendProjectionMeta was not found")
        val annotationConstructor = annotationClass.constructors.single()
        val origin = buildString {
            val canonicalOwner = (owner.parent as? IrClass)?.parent as? IrClass
            append(canonicalOwner?.fqNameWhenAvailable?.asString() ?: owner.fqNameWhenAvailable)
            append('#')
            append(canonical.name.asString())
            append('(')
            canonical.parameters.asSequence()
                .filter { it.kind == IrParameterKind.Regular }
                .joinTo(this, separator = ",") { it.type.render() }
            append(')')
        }
        val values = listOf(
            1,
            1,
            "0.1.0-SNAPSHOT",
            origin,
            "blocking",
            "imports",
            origin,
            0,
            "none",
        )
        blocking.annotations += DeclarationIrBuilder(pluginContext, blocking.symbol)
            .irAnnotation(annotationConstructor)
            .apply {
                annotationConstructor.owner.parameters.zip(values).forEach { (parameter, value) ->
                    arguments[parameter] = when (value) {
                        is Int -> IrConstImpl.int(startOffset, endOffset, parameter.type, value)
                        is String -> IrConstImpl.string(startOffset, endOffset, parameter.type, value)
                        else -> error("Unsupported SuspendProjectionMeta value: $value")
                    }
                }
            }
    }
}
