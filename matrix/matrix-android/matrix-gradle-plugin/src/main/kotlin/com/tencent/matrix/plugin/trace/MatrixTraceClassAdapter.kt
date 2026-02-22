package com.tencent.matrix.plugin.trace

import com.tencent.matrix.trace.TraceBuildConstants
import com.tencent.matrix.trace.item.TraceMethod
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter
import java.util.concurrent.ConcurrentHashMap

class MatrixTraceClassAdapter(
    api: Int,
    cv: ClassVisitor,
    private val collectedMethodMap: ConcurrentHashMap<String, TraceMethod>,
    private val collectedClassExtendMap: ConcurrentHashMap<String, String>,
    private val blockSet: Set<String>
) : ClassVisitor(api, cv) {

    private var className: String = ""
    private var superName: String? = null
    private var isABSClass = false
    private var hasWindowFocusMethod = false
    private var isActivityOrSubClass = false
    private var isNeedTrace = true

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        this.className = name
        this.superName = superName
        this.isActivityOrSubClass = isActivityOrSubClass(className, collectedClassExtendMap)
        this.isNeedTrace = checkNeedTrace(className)
        if ((access and Opcodes.ACC_ABSTRACT) > 0 || (access and Opcodes.ACC_INTERFACE) > 0) {
            this.isABSClass = true
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        desc: String,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        if (!hasWindowFocusMethod) {
            hasWindowFocusMethod = (name == TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD &&
                    desc == TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD_ARGS)
        }
        if (isABSClass) {
            return super.visitMethod(access, name, desc, signature, exceptions)
        } else {
            val methodVisitor = cv.visitMethod(access, name, desc, signature, exceptions)
            return MatrixTraceMethodAdapter(
                api, methodVisitor, access, name, desc, this.className,
                hasWindowFocusMethod, isActivityOrSubClass, isNeedTrace, collectedMethodMap
            )
        }
    }

    override fun visitEnd() {
        if (!hasWindowFocusMethod && isActivityOrSubClass && isNeedTrace) {
            insertWindowFocusChangeMethod(cv, className, superName)
        }
        super.visitEnd()
    }

    private fun checkNeedTrace(clsNameOrig: String): Boolean {
        var clsName = clsNameOrig.replace("/", ".")
        if (blockSet.contains(clsName)) {
            return false
        }
        for (packageName in blockSet) {
            if (clsName.startsWith(packageName.replace("/", "."))) {
                return false
            }
        }
        return true
    }

    private fun isActivityOrSubClass(
        className: String,
        mCollectedClassExtendMap: ConcurrentHashMap<String, String>
    ): Boolean {
        val formattedClassName = className.replace(".", "/")
        val isActivity = formattedClassName == TraceBuildConstants.MATRIX_TRACE_ACTIVITY_CLASS ||
                formattedClassName == TraceBuildConstants.MATRIX_TRACE_V4_ACTIVITY_CLASS ||
                formattedClassName == TraceBuildConstants.MATRIX_TRACE_V7_ACTIVITY_CLASS ||
                formattedClassName == TraceBuildConstants.MATRIX_TRACE_ANDROIDX_ACTIVITY_CLASS
        
        if (isActivity) {
            return true
        } else {
            if (!mCollectedClassExtendMap.containsKey(formattedClassName)) {
                return false
            } else {
                val parent = mCollectedClassExtendMap[formattedClassName]
                return if (parent != null) isActivityOrSubClass(parent, mCollectedClassExtendMap) else false
            }
        }
    }

    private fun insertWindowFocusChangeMethod(cv: ClassVisitor, classname: String, superClassName: String?) {
        val methodVisitor = cv.visitMethod(
            Opcodes.ACC_PUBLIC, TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD,
            TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD_ARGS, null, null
        )
        methodVisitor.visitCode()
        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0)
        methodVisitor.visitVarInsn(Opcodes.ILOAD, 1)
        methodVisitor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, superClassName ?: "java/lang/Object",
            TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD,
            TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD_ARGS, false
        )
        traceWindowFocusChangeMethod(methodVisitor, classname)
        methodVisitor.visitInsn(Opcodes.RETURN)
        methodVisitor.visitMaxs(2, 2)
        methodVisitor.visitEnd()
    }

    private fun traceWindowFocusChangeMethod(mv: MethodVisitor, classname: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "at",
            "(Landroid/app/Activity;Z)V", false
        )
    }
}

class MatrixTraceMethodAdapter(
    api: Int,
    mv: MethodVisitor,
    access: Int,
    val name: String,
    val desc: String,
    val className: String,
    val hasWindowFocusMethod: Boolean,
    val isActivityOrSubClass: Boolean,
    val isNeedTrace: Boolean,
    val collectedMethodMap: ConcurrentHashMap<String, TraceMethod>
) : AdviceAdapter(api, mv, access, name, desc) {

    private val methodName: String

    init {
        val traceMethod = TraceMethod.create(0, access, className, name, desc)
        this.methodName = traceMethod.getMethodName()
    }

    override fun onMethodEnter() {
        val traceMethod = collectedMethodMap[methodName]
        if (traceMethod != null) {
            mv.visitLdcInsn(traceMethod.id)
            mv.visitMethodInsn(
                INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "i", "(I)V", false
            )

            if (checkNeedTraceWindowFocusChangeMethod(traceMethod)) {
                traceWindowFocusChangeMethod(mv, className)
            }
        }
    }

    override fun onMethodExit(opcode: Int) {
        val traceMethod = collectedMethodMap[methodName]
        if (traceMethod != null) {
            mv.visitLdcInsn(traceMethod.id)
            mv.visitMethodInsn(
                INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "o", "(I)V", false
            )
        }
    }

    private fun checkNeedTraceWindowFocusChangeMethod(traceMethod: TraceMethod): Boolean {
        if (hasWindowFocusMethod && isActivityOrSubClass && isNeedTrace) {
            val windowFocusChangeMethod = TraceMethod.create(
                -1, Opcodes.ACC_PUBLIC, className,
                TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD,
                TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD_ARGS
            )
            if (windowFocusChangeMethod == traceMethod) {
                return true
            }
        }
        return false
    }

    private fun traceWindowFocusChangeMethod(mv: MethodVisitor, classname: String) {
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "at",
            "(Landroid/app/Activity;Z)V", false
        )
    }
}
