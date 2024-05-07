package com.mato.stg4.generator

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.mato.stg4.annotation.ACFunction
import com.mato.stg4.annotation.AutoConvert
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import org.json.JSONObject
import kotlin.reflect.KClass

/**
 * @date: 2024-02-29
 * @author: 孙路路 sunlulu.tomato
 */
@OptIn(KspExperimental::class)
class ACGenerator(
    private val env: SymbolProcessorEnvironment,
    private val ksClass: KSClassDeclaration,
) : KSPLogger by env.logger {
    private val className = ksClass.simpleName.asString()
    private val packageName = ksClass.packageName.asString()
    private val autoConverter = ksClass.getAnnotationsByType(AutoConvert::class).first()
    private val filename = className + autoConverter.filePostfix
    private val fileBuilder = FileSpec.builder(packageName, filename)
    private val indent = env.options.getOrDefault("ac.indent", "4").toInt()

    init {
        // config it via gradle plugin?
        fileBuilder.indent(" ".repeat(indent))
        fileBuilder.addFileComment("Read-only, generated by AutoConverter.")
        // add functions
        autoConverter.functions.distinct().forEach {
            fileBuilder.addFunction(it.toFunSpec())
        }
    }

    fun generate() {
        if (autoConverter.functions.isEmpty()) {
            return
        }
        val code = fileBuilder.build().toString().toByteArray()
        val codeString = String(code)
        val output = env.codeGenerator.createNewFile(
            dependencies = Dependencies(false),
            packageName = ksClass.packageName.asString(),
            fileName = filename
        )

        env.logger.info("code is : $codeString")
        output.write(code)
        output.close()

        // After the file is generated, make it read-only
        env.codeGenerator.generatedFile.find {
            it.nameWithoutExtension == filename
        }?.setReadOnly()?.let {
            env.logger.info("Generated file: $filename is set read-only.")
        }
    }

    private fun ACFunction.toFunSpec(): FunSpec = when (this) {
        ACFunction.ToJSONObject -> {
            // generate fun toJSONObject
            val name = "toJSONObject"
            val receiverType = ClassName(packageName, className)
            val builder = FunSpec.builder(name)
                .receiver(receiverType)
                .returns(
                    Result::class.asClassName().parameterizedBy(JSONObject::class.asClassName())
                )

            // begin control flow of runCatching
            builder.beginControlFlow("return kotlin.runCatching {")
            // begin control flow of also
            builder.beginControlFlow("%T().also {", JSONObject::class.asClassName())
            ksClass.getAllProperties().forEach {
                val propName = it.simpleName.asString()
                val key = autoConverter.namingStrategy.resolve(propName)
                if (it.isAnnotatedClass()) {
                    // enable nested classes
                    val nestedPackageName = it.type.resolve().declaration.packageName.asString()
                    fileBuilder.addImport(nestedPackageName, name)
                    if (it.type.resolve().isMarkedNullable) {
                        builder.addStatement(
                            "it.put(%S, this.%L?.$name()?.getOrThrow())",
                            key,
                            propName
                        )
                    } else {
                        builder.addStatement(
                            "it.put(%S, this.%L.$name().getOrThrow())",
                            key,
                            propName
                        )
                    }
                } else {
                    builder.addStatement("it.put(%S, this.%L)", key, propName)
                }
            }
            builder.endControlFlow() // end control flow of also
            builder.endControlFlow() // end control flow of runCatching
            builder.build()
        }

        ACFunction.FromJSONObject -> {
            // generate fun fromJSONObject
            val name = "fromJSONObject"
            val payload = "payload"
            val builder = FunSpec.builder(name)
                // KSClass<T>
                .receiver(KClass::class.asClassName().parameterizedBy(ksClass.toClassName()))
                // Result<T>
                .returns(Result::class.asClassName().parameterizedBy(ksClass.toClassName()))
                .addParameter(payload, JSONObject::class)

            // begin control flow of runCatching
            builder.beginControlFlow("return kotlin.runCatching {")
            // begin control flow of init
            builder.addStatement("%T(", ksClass.toClassName())

            ksClass.primaryConstructor?.parameters?.forEachIndexed { index, ksValueParameter ->
                val propName = ksValueParameter.name?.asString() ?: return@forEachIndexed
                val payloadKey = autoConverter.namingStrategy.resolve(propName)
                val isLast = index + 1 == ksClass.primaryConstructor?.parameters?.size

                if (ksValueParameter.isAnnotatedParameter) {
                    val nestedPackageName =
                        ksValueParameter.type.resolve().declaration.packageName.asString()
                    // add import
                    fileBuilder.addImport(nestedPackageName, name)
                    if (ksValueParameter.type.resolve().isMarkedNullable) {
                        builder.addStatement(
                            "%L = ${payload}.optJSONObject(%S)?.let { %T::class.$name(it).getOrThrow() }"
                                .withIndent(indent)
                                .withComma(!isLast),
                            propName,
                            payloadKey,
                            ksValueParameter.type.resolve().toClassName()
                        )
                    } else {
                        builder.addStatement(
                            "%L = %T::class.$name(${payload}.getJSONObject(%S)).getOrThrow()"
                                .withIndent(indent).withComma(!isLast),
                            propName, // %L
                            ksValueParameter.type.resolve().toClassName(), // %T
                            payloadKey // %S
                        )
                    }
                } else {
                    val cast = if (ksValueParameter.type.resolve().isMarkedNullable) {
                        "as?"
                    } else {
                        "as"
                    }
                    val type = ksValueParameter.type.resolve()
                    val typeName = type.declaration.simpleName.asString()
                    if (ksValueParameter.isGenericType) {
                        val typeArgs = type.arguments.map {
                            it.type?.resolve()?.declaration?.simpleName?.asString()
                        }
                        val genericTypeName =
                            "$typeName<${typeArgs.joinToString(separator = ", ")}>"
                        builder.addStatement(
                            "%L = ${payload}.get(%S) $cast $genericTypeName"
                                .withIndent(indent).withComma(!isLast),
                            propName,
                            payloadKey
                        )
                    } else {
                        builder.addStatement(
                            "%L = ${payload}.get(%S) $cast $typeName"
                                .withIndent(indent).withComma(!isLast),
                            propName,
                            payloadKey
                        )
                    }
                }
            }

            builder.addStatement(")") // end control flow of init
            builder.endControlFlow() // end control flow of runCatching
            builder.build()
        }

        ACFunction.SayHello -> {
            // generate fun toJSONObject
            val name = "SayHello"
            val receiverType = ClassName(packageName, className)
            val builder = FunSpec.builder(name)
                .receiver(receiverType)
                .returns(
                    Result::class.asClassName().parameterizedBy(Unit::class.asClassName())
                )

            // begin control flow of runCatching
            builder.beginControlFlow("return kotlin.runCatching {")
            // begin control flow of also
            builder.addStatement("println(\"Hello\")")
            builder.endControlFlow() // end control flow of also
            builder.build()
        }
    }

}