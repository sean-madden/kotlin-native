/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.common.onlyIf
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.needsSerializedIr
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.metadata.deserialization.BinaryVersion
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.konan.KonanProtoBuf
import org.jetbrains.kotlin.metadata.serialization.MutableVersionRequirementTable
import org.jetbrains.kotlin.serialization.DescriptorSerializer
import org.jetbrains.kotlin.serialization.KotlinSerializerExtensionBase
import org.jetbrains.kotlin.serialization.konan.KonanSerializerProtocol
import org.jetbrains.kotlin.serialization.konan.SourceFileMap
import org.jetbrains.kotlin.types.KotlinType

internal class KonanSerializerExtension(val context: Context, override val metadataVersion: BinaryVersion,
                                        val sourceFileMap: SourceFileMap) :
        KotlinSerializerExtensionBase(KonanSerializerProtocol), IrAwareExtension {

    val inlineDescriptorTable = DescriptorTable(context.irBuiltIns)
    override val stringTable = KonanStringTable()
    override fun shouldUseTypeTable(): Boolean = true

    override fun serializeType(type: KotlinType, proto: ProtoBuf.Type.Builder) {
        // TODO: For debugging purpose we store the textual 
        // representation of serialized types.
        // To be removed.
        proto.setExtension(KonanProtoBuf.typeText, type.toString())

        super.serializeType(type, proto)
    }

    override fun serializeEnumEntry(descriptor: ClassDescriptor, proto: ProtoBuf.EnumEntry.Builder) {
        // Serialization doesn't preserve enum entry order, so we need to serialize ordinal.
        val ordinal = context.specialDeclarationsFactory.getEnumEntryOrdinal(descriptor)
        proto.setExtension(KonanProtoBuf.enumEntryOrdinal, ordinal)
        super.serializeEnumEntry(descriptor, proto)
    }

    override fun serializeConstructor(descriptor: ConstructorDescriptor, proto: ProtoBuf.Constructor.Builder,
                                      childSerializer: DescriptorSerializer) {
        super.serializeConstructor(descriptor, proto, childSerializer)
        if (descriptor.needsSerializedIr) {
            addConstructorIR(proto, serializeInlineBody(descriptor, childSerializer))
        }
    }

    override fun serializeClass(descriptor: ClassDescriptor, proto: ProtoBuf.Class.Builder,
                                versionRequirementTable: MutableVersionRequirementTable,
                                childSerializer: DescriptorSerializer) {
        super.serializeClass(descriptor, proto, versionRequirementTable, childSerializer)
        context.ir.classesDelegatedBackingFields[descriptor]?.forEach {
            proto.addProperty(childSerializer.propertyProto(it))
        }
        // Invocation of the propertyProto above can add more types
        // to the type table that should also be serialized.
        childSerializer.typeTable.serialize()?.let { proto.mergeTypeTable(it) }
    }

    override fun serializeFunction(descriptor: FunctionDescriptor, proto: ProtoBuf.Function.Builder,
                                   childSerializer: DescriptorSerializer) {
        proto.setExtension(KonanProtoBuf.functionFile, sourceFileMap.assign(descriptor.source.containingFile))
        super.serializeFunction(descriptor, proto, childSerializer)
        if (descriptor.needsSerializedIr) {
            addFunctionIR(proto, serializeInlineBody(descriptor, childSerializer))
        }
    }

    override fun serializeProperty(descriptor: PropertyDescriptor, proto: ProtoBuf.Property.Builder,
                                   versionRequirementTable: MutableVersionRequirementTable,
                                   childSerializer: DescriptorSerializer) {
        val variable = originalVariables[descriptor]
        if (variable != null) {
            proto.setExtension(KonanProtoBuf.usedAsVariable, true)
        }
        proto.setExtension(KonanProtoBuf.propertyFile, sourceFileMap.assign(descriptor.source.containingFile))
        proto.setExtension(KonanProtoBuf.hasBackingField,
            context.ir.propertiesWithBackingFields.contains(descriptor))

        super.serializeProperty(descriptor, proto, versionRequirementTable, childSerializer)

        /* Konan specific chunk */
        descriptor.getter?.onlyIf({ needsSerializedIr }) {
            addGetterIR(proto, serializeInlineBody(it, childSerializer))
        }
        descriptor.setter?.onlyIf({ needsSerializedIr }) {
            addSetterIR(proto, serializeInlineBody(it, childSerializer))
        }
    }

    override fun addFunctionIR(proto: ProtoBuf.Function.Builder, serializedIR: String) 
        = proto.setInlineIr(inlineBody(serializedIR))

    override fun addConstructorIR(proto: ProtoBuf.Constructor.Builder, serializedIR: String) 
        = proto.setConstructorIr(inlineBody(serializedIR))

    override fun addGetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String) 
        = proto.setGetterIr(inlineBody(serializedIR))

    override fun addSetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String) 
        = proto.setSetterIr(inlineBody(serializedIR))

    override fun serializeInlineBody(descriptor: FunctionDescriptor, serializer: DescriptorSerializer): String {

        return IrSerializer( 
            context, inlineDescriptorTable, stringTable, serializer, descriptor).serializeInlineBody()
    }

    override fun releaseCoroutines(): Boolean =
            context.config.configuration.languageVersionSettings.supportsFeature(LanguageFeature.ReleaseCoroutines)
}

internal interface IrAwareExtension {

    fun serializeInlineBody(descriptor: FunctionDescriptor, serializer: DescriptorSerializer): String

    fun addFunctionIR(proto: ProtoBuf.Function.Builder, serializedIR: String): ProtoBuf.Function.Builder

    fun addConstructorIR(proto: ProtoBuf.Constructor.Builder, serializedIR: String): ProtoBuf.Constructor.Builder

    fun addSetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String): ProtoBuf.Property.Builder

    fun addGetterIR(proto: ProtoBuf.Property.Builder, serializedIR: String): ProtoBuf.Property.Builder
}