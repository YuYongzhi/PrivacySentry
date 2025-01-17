package com.yl.lib.plugin.sentry.transform

import com.yl.lib.plugin.sentry.extension.PrivacyExtension
import com.yl.lib.privacy_annotation.MethodInvokeOpcode
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.AdviceAdapter
import kotlin.math.log

/**
 * @author yulun
 * @sinice 2021-12-21 14:29
 * 收集待替换方法的代理类
 */
class CollectHookMethodClassAdapter : ClassVisitor {
    private var className: String = ""

    private var bHookClass: Boolean = false
    private var privacyExtension: PrivacyExtension? = null
    private var logger: Logger

    constructor(api: Int, classVisitor: ClassVisitor?, privacyExtension: PrivacyExtension?, logger: Logger) : super(
        api,
        classVisitor
    ) {
        this.logger = logger
        this.privacyExtension = privacyExtension
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        if (name != null) {
            className = name.replace("/", ".")
        }
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor?.equals("Lcom/yl/lib/privacy_annotation/PrivacyClassReplace;") == true) {
            var avr = cv.visitAnnotation(descriptor, visible)
            return CollectClassAnnotationVisitor(api, avr, className, logger)
        }
        if (descriptor?.equals("Lcom/yl/lib/privacy_annotation/PrivacyClassProxy;") == true) {
            bHookClass = true
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        if (bHookClass) {
            var methodVisitor = cv.visitMethod(access, name, descriptor, signature, exceptions)
            return CollectHookMethodAdapter(
                api,
                methodVisitor,
                access,
                name,
                descriptor,
                privacyExtension,
                className,
                logger
            )
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions)
    }

    override fun visitField(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        if (bHookClass && privacyExtension?.hookField == true) {
            var methodVisitor = cv.visitField(access, name, descriptor, signature, value)
            return CollectHookFieldVisitor(
                api,
                methodVisitor,
                className,
                name,
                descriptor
            )
        }
        return super.visitField(access, name, descriptor, signature, value)
    }
}

/**
 * 解析PrivacyClassReplace注解
 * @property logger Logger
 * @property className String
 * @property item ReplaceClassItem?
 */
class CollectClassAnnotationVisitor : AnnotationVisitor {
    private var logger: Logger
    private var className: String

    constructor(
        api: Int,
        annotationVisitor: AnnotationVisitor?,
        className: String,
        logger: Logger
    ) : super(api, annotationVisitor) {
        this.logger = logger
        this.className = className
    }

    var item: ReplaceClassItem? = null
    override fun visit(name: String?, value: Any?) {
        super.visit(name, value)
        if (name.equals("originClass")) {
            var classSourceName = value.toString()
            item = ReplaceClassItem(
                originClassName = classSourceName.substring(1, classSourceName.length - 1),
                proxyClassName = className
                )
            logger.info("CollectClassAnnotationVisitor-ReplaceClassItem - ${item.toString()}")
        }
    }

    override fun visitEnd() {
        super.visitEnd()
        item?.let {
            ReplaceClassManager.MANAGER.appendHookItem(item!!)
        }
    }
}

/**
 * 解析PrivacyClassProxy下的PrivacyMethodProxy注解的方法
 * @property privacyExtension PrivacyExtension?
 * @property className String
 * @property logger Logger
 */
class CollectHookMethodAdapter : AdviceAdapter {
    private var privacyExtension: PrivacyExtension? = null
    private var className: String
    private var logger: Logger

    constructor(
        api: Int,
        methodVisitor: MethodVisitor?,
        access: Int,
        name: String?,
        descriptor: String?,
        privacyExtension: PrivacyExtension?,
        className: String,
        logger: Logger
    ) : super(api, methodVisitor, access, name, descriptor) {
        this.privacyExtension = privacyExtension
        this.className = className
        this.logger = logger
    }


    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor?.equals("Lcom/yl/lib/privacy_annotation/PrivacyMethodProxy;") == true) {
            var avr = mv.visitAnnotation(descriptor, visible)
            return CollectMethodHookAnnotationVisitor(
                api,
                avr,
                HookMethodItem(
                    proxyClassName = className,
                    proxyMethodName = name,
                    proxyMethodReturnDesc = methodDesc

                ),
                logger = logger
            )
        }
        return super.visitAnnotation(descriptor, visible)
    }


}

/**
 * 解析PrivacyMethodProxy注解
 * @property hookMethodItem HookMethodItem?
 * @property logger Logger
 */
class CollectMethodHookAnnotationVisitor : AnnotationVisitor {
    private var hookMethodItem: HookMethodItem? = null
    private var logger: Logger

    constructor(
        api: Int,
        annotationVisitor: AnnotationVisitor?,
        hookMethodItem: HookMethodItem?,
        logger: Logger
    ) : super(api, annotationVisitor) {
        this.hookMethodItem = hookMethodItem
        this.logger = logger
    }

    override fun visit(name: String?, value: Any?) {
        super.visit(name, value)
        if (name.equals("originalClass")) {
            var classSourceName = value.toString()
            hookMethodItem?.originClassName =
                classSourceName.substring(1, classSourceName.length - 1)
        } else if (name.equals("originalMethod")) {
            hookMethodItem?.originMethodName = value.toString()
        } else if (name.equals("ignoreClass")) {
            hookMethodItem?.ignoreClass = value as Boolean
        } else if (name.equals("originalOpcode")) {
            hookMethodItem?.originMethodAccess = value as Int
        }
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String?) {
        super.visitEnum(name, descriptor, value)
        if (name.equals("originalOpcode")) {
            hookMethodItem?.originMethodAccess = value?.toInt()
        }
    }

    override fun visitEnd() {
        super.visitEnd()
        if (hookMethodItem?.originMethodAccess == MethodInvokeOpcode.INVOKESTATIC) {
            hookMethodItem?.originMethodDesc = hookMethodItem?.proxyMethodDesc
        } else if (hookMethodItem?.originMethodAccess == MethodInvokeOpcode.INVOKEVIRTUAL ||
            hookMethodItem?.originMethodAccess == MethodInvokeOpcode.INVOKEINTERFACE ||
            hookMethodItem?.originMethodAccess == MethodInvokeOpcode.INVOKESPECIAL
        ) {
            // 如果是调用实例方法，代理方法的描述会比原始方法多了一个实例，这里需要裁剪，方便做匹配 、、、
            hookMethodItem?.originMethodDesc =
                hookMethodItem?.proxyMethodDesc?.replace("L${hookMethodItem?.originClassName};", "")
        }
        HookMethodManager.MANAGER.appendHookMethod(hookMethodItem!!)
    }
}

/**
 * 解析PrivacyFieldProxy注解的变量
 * @property className String
 * @property fieldName String?
 * @property proxyDescriptor String?
 */
class CollectHookFieldVisitor : FieldVisitor {
    private var className: String
    private var fieldName: String?
    private var proxyDescriptor: String?

    constructor(
        api: Int,
        fieldVisitor: FieldVisitor,
        className: String,
        fieldName: String?,
        descriptor: String?
    ) : super(api, fieldVisitor) {
        this.className = className
        this.fieldName = fieldName
        this.proxyDescriptor = descriptor
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor?.equals("Lcom/yl/lib/privacy_annotation/PrivacyFieldProxy;") == true) {
            var avr = fv.visitAnnotation(descriptor, visible)
            return CollectFieldHookAnnotationVisitor(
                api,
                avr,
                HookFieldItem(
                    proxyClassName = className,
                    proxyFieldName = fieldName ?: "",
                    proxyFieldDesc = proxyDescriptor ?: ""
                )
            )
        }
        return super.visitAnnotation(descriptor, visible)
    }

}

/**
 * 解析注解PrivacyFieldProxy
 * @property hookFieldItem HookFieldItem?
 */
class CollectFieldHookAnnotationVisitor : AnnotationVisitor {
    private var hookFieldItem: HookFieldItem? = null

    constructor(
        api: Int,
        annotationVisitor: AnnotationVisitor?,
        hookFieldItem: HookFieldItem
    ) : super(api, annotationVisitor) {
        this.hookFieldItem = hookFieldItem
    }

    override fun visit(name: String?, value: Any?) {
        super.visit(name, value)
        if (name.equals("originalClass")) {
            var classSourceName = value.toString()
            hookFieldItem?.originClassName =
                classSourceName.substring(1, classSourceName.length - 1)
        } else if (name.equals("originalFieldName")) {
            hookFieldItem?.originFieldName = value.toString()
        }
    }

    override fun visitEnd() {
        super.visitEnd()
        hookFieldItem?.let {
            HookFieldManager.MANAGER.appendHookField(it)
        }
    }
}
